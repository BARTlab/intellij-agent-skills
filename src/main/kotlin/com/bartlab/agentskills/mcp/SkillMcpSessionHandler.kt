package com.bartlab.agentskills.mcp

import com.intellij.openapi.diagnostic.Logger
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.json.schema.JsonSchemaValidator
import io.modelcontextprotocol.server.McpAsyncServer
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage
import io.modelcontextprotocol.spec.McpServerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SkillMcpSessionHandler(
    private val mapper: McpJsonMapper,
    private val validator: JsonSchemaValidator,
    private val toolRegistry: SkillMcpToolRegistry,
    private val log: Logger,
    private val contextRunner: SkillMcpContextRunner
) {
    private val sseSessions = ConcurrentHashMap<String, KtorSseSession>()
    private val messageParser = SkillMcpJsonMessageParser(mapper)

    suspend fun handleSse(call: ApplicationCall) {
        val sessionId = UUID.randomUUID().toString()
        val headers = call.request.headers
        val isSseRequested = headers[HttpHeaders.Accept]?.contains("text/event-stream") == true

        log.info("MCP: New connection request (SSE=$isSseRequested), assigned sessionId: $sessionId, Headers: ${headers.entries()}")

        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()

        val (mcpServer, session, _) = contextRunner.run {
            val transport = KtorSessionTransport(sink, mapper, log)
            val provider = KtorSessionProvider(transport)

            val mcpServer = McpServer.async(provider)
                .serverInfo("agent-skills", "0.2.1")
                .jsonMapper(mapper)
                .jsonSchemaValidator(validator)
                .capabilities(McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .run {
                        try {
                            this.completions()
                        } catch (_: Throwable) {
                            log.warn("MCP: completions not supported by SDK, disabling")
                            this
                        }
                    }
                    .build())
                .also { toolRegistry.registerTools(it, sessionId) }
                .build()

            val session = provider.createSession()
            Triple(mcpServer, session, provider)
        }

        val ktorSession = KtorSseSession(sessionId, session, mcpServer)
        sseSessions[sessionId] = ktorSession

        try {
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.response.headers.append(HttpHeaders.Connection, "keep-alive")

            val currentContext = currentCoroutineContext()
            call.respondTextWriter(ContentType.Text.EventStream) {
                write(": welcome to agent-skills mcp\n\n")
                flush()

                val messagesUrl = "messages/$sessionId"

                log.info("MCP: Sending endpoint event for session $sessionId: $messagesUrl")
                write("event: endpoint\ndata: $messagesUrl\n\n")
                flush()

                val channel = Channel<String>(Channel.BUFFERED)
                val disposable = sink.asFlux().subscribe { msg ->
                    if (!channel.trySend(msg).isSuccess) {
                        log.warn("MCP: Failed to send message to channel for session $sessionId (buffer full?)")
                    }
                }

                try {
                    while (currentContext.isActive) {
                        val msg = withTimeoutOrNull(15_000) {
                            channel.receive()
                        }
                        if (msg != null) {
                            write("event: message\ndata: $msg\n\n")
                        } else {
                            write(": keep-alive\n\n")
                        }
                        flush()
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        log.debug("SSE connection for session $sessionId ended: ${e.message}")
                    }
                } finally {
                    disposable.dispose()
                    channel.close()
                }
            }
        } catch (e: Exception) {
            log.error("MCP: SSE handler failed for session $sessionId", e)
        } finally {
            sseSessions.remove(sessionId)
            closeSession(sessionId, ktorSession)
        }
    }

    suspend fun handlePostMessage(call: ApplicationCall) {
        val sessionId = runCatching {
            call.parameters["sessionId"] ?: call.request.queryParameters["sessionId"]
        }.getOrNull()

        log.info("MCP: Received POST at ${call.request.uri}. sessionId: $sessionId, Content-Type: ${call.request.contentType()}")

        val body = try {
            call.receive<String>()
        } catch (e: Exception) {
            log.error("MCP: Failed to receive body for session $sessionId", e)
            call.respondText("Failed to read body", status = HttpStatusCode.BadRequest)
            return
        }

        log.debug("MCP: POST Body: $body")

        var ktorSession = if (sessionId != null) sseSessions[sessionId] else null

        if (ktorSession == null) {
            val activeSessions = sseSessions.values.toList()
            ktorSession = when {
                activeSessions.size == 1 -> {
                    log.info("MCP: Falling back to the only active session: ${activeSessions.first().id}")
                    activeSessions.first()
                }
                activeSessions.isEmpty() -> {
                    log.warn("MCP: No active sessions for POST request")
                    null
                }
                else -> {
                    log.warn("MCP: Session not found: $sessionId, active sessions: ${activeSessions.size}")
                    null
                }
            }
        }

        if (ktorSession == null) {
            log.warn("MCP: Session not found or ambiguous: $sessionId")
            call.respondText("Unknown or expired session", status = HttpStatusCode.BadRequest)
            return
        }

        try {
            contextRunner.run {
                val msg = messageParser.parse(body)
                ktorSession.session.handle(msg).subscribe({
                    log.debug("MCP: Successfully handled message from session ${ktorSession!!.id}")
                }, { err ->
                    log.error("MCP: Error handling message for session ${ktorSession!!.id}", err)
                })
            }
            call.respondText("Accepted", status = HttpStatusCode.Accepted)
        } catch (e: Exception) {
            log.error("MCP: Failed to parse/handle message for session $sessionId", e)
            call.respondText("Invalid JSON-RPC: ${e.message}", status = HttpStatusCode.BadRequest)
        }
    }

    fun closeAllSessions() {
        sseSessions.values.forEach { session ->
            closeSession(session.id, session)
        }
        sseSessions.clear()
    }

    private fun closeSession(sessionId: String, session: KtorSseSession) {
        try {
            session.session.close()
        } catch (e: Exception) {
            log.warn("MCP: Error closing session $sessionId: ${e.message}")
        }
        try {
            session.mcpServer.close()
        } catch (e: Exception) {
            log.warn("MCP: Error closing mcpServer for $sessionId: ${e.message}")
        }
        log.info("MCP: SSE connection closed and session $sessionId cleaned up")
    }

    private class KtorSseSession(
        val id: String,
        val session: McpServerSession,
        val mcpServer: McpAsyncServer
    )

    private class KtorSessionProvider(private val transport: KtorSessionTransport) : io.modelcontextprotocol.spec.McpServerTransportProvider {
        private var factory: McpServerSession.Factory? = null
        override fun setSessionFactory(factory: McpServerSession.Factory) { this.factory = factory }
        fun createSession(): McpServerSession = requireNotNull(factory).create(transport)
        override fun closeGracefully(): Mono<Void> = Mono.empty()
        override fun notifyClients(method: String, params: Any): Mono<Void> = Mono.empty()
    }

    private class KtorSessionTransport(
        private val sink: Sinks.Many<String>,
        private val mapper: McpJsonMapper,
        private val log: Logger
    ) : io.modelcontextprotocol.spec.McpServerTransport {
        override fun sendMessage(message: JSONRPCMessage): Mono<Void> {
            val json = mapper.writeValueAsString(message)
            log.info("MCP: Sending message: $json")
            sink.tryEmitNext(json)
            return Mono.empty()
        }

        override fun <T> unmarshalFrom(data: Any?, typeRef: TypeRef<T>?): T = mapper.convertValue(data, typeRef)
        override fun closeGracefully(): Mono<Void> = Mono.empty()
        override fun close() {}
    }
}