package com.bartlab.agentskills.mcp

import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.bartlab.agentskills.service.SkillScannerService
import com.bartlab.agentskills.service.SkillPromptXmlService
import com.bartlab.agentskills.settings.SkillSettingsState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpAsyncServer
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.json.schema.jackson.JacksonJsonSchemaValidatorSupplier
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpServerSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

@Service(Service.Level.PROJECT)
class SkillMcpServerService(private val project: Project) {
    private val log = Logger.getInstance(SkillMcpServerService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = "0.0.0.0"
    private val port: Int
        get() = SkillSettingsState.getInstance(project).state.mcpPort

    private var ktorEngine: ApplicationEngine? = null
    private val engineLock = Any()

    private val mapper: McpJsonMapper by lazy {
        withMcpContext { JacksonMcpJsonMapper(ObjectMapper().registerKotlinModule()) }
    }
    private val validator by lazy {
        withMcpContext { JacksonJsonSchemaValidatorSupplier().get() }
    }

    private fun <T> withMcpContext(block: () -> T): T {
        val oldLoader = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = SkillMcpServerService::class.java.classLoader
            return block()
        } finally {
            Thread.currentThread().contextClassLoader = oldLoader
        }
    }

    fun syncWithSettings() {
        stop()
        if (SkillSettingsState.getInstance(project).state.integrateAiAssistant) {
            // Даем немного времени на освобождение порта
            scope.launch {
                delay(300)
                ensureStarted()
            }
        }
    }

    fun stop() {
        scope.cancel()
        synchronized(engineLock) {
            ktorEngine?.stop(200, 500, TimeUnit.MILLISECONDS)
            ktorEngine = null
            
            sseSessions.values.forEach { 
                try { it.session.close() } catch (e: Exception) {}
            }
            sseSessions.clear()
        }
    }

    fun ensureStarted() {
        if (!SkillSettingsState.getInstance(project).state.integrateAiAssistant) return

        synchronized(engineLock) {
            if (ktorEngine != null) return

            try {
                withMcpContext {
                    // Ktor Server for HTTP/SSE
                    startKtorServer()
                }

                log.info("MCP Server started (HTTP:$port)")
            } catch (e: Exception) {
                log.error("Failed to start MCP server", e)
                notify("Agent Skills: MCP server start error", e.message ?: e.toString(), NotificationType.ERROR)
            }
        }
    }

    private fun startKtorServer() {
        val engine = embeddedServer(CIO, host = host, port = port) {
            intercept(ApplicationCallPipeline.Call) {
                if (call.request.uri.contains("/messages") || call.request.uri.contains("/sse")) {
                    this@SkillMcpServerService.log.info("MCP: Incoming ${call.request.httpMethod.value} ${call.request.uri}")
                }
            }
            routing {
                get("/sse") { handleSse(call) }
                post("/messages/{sessionId}") {
                    handlePostMessage(call)
                }
                post("/messages") {
                    handlePostMessage(call)
                }
                get("/health") { call.respondText("OK") }
            }
        }
        engine.start(wait = false)
        this.ktorEngine = engine
    }

    private val sseSessions = ConcurrentHashMap<String, KtorSseSession>()

    private suspend fun handleSse(call: ApplicationCall) {
        val sessionId = UUID.randomUUID().toString()
        val headers = call.request.headers
        val isSseRequested = headers[HttpHeaders.Accept]?.contains("text/event-stream") == true
        
        log.info("MCP: New connection request (SSE=$isSseRequested), assigned sessionId: $sessionId, Headers: ${headers.entries()}")
        
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        
        val (mcpServer, session, provider) = withMcpContext {
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
                            // Try to enable completion capability if supported by SDK
                            this.completions()
                        } catch (_: Throwable) {
                            log.warn("MCP: completions not supported by SDK, disabling")
                            this
                        }
                    }
                    .build())
                .also { registerTools(it, sessionId) }
                .build()
            
            val session = provider.createSession()
            Triple(mcpServer, session, provider)
        }
        
        val ktorSession = KtorSseSession(sessionId, session, mcpServer, sink)
        sseSessions[sessionId] = ktorSession

        try {
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.response.headers.append(HttpHeaders.Connection, "keep-alive")
            
            val currentContext = currentCoroutineContext()
            call.respondTextWriter(ContentType.Text.EventStream) {
                // Initial comment to warm up the stream
                write(": welcome to agent-skills mcp\n\n")
                flush()

                // Используем относительный путь для эндпоинта сообщений.
                // Передача полного URL приводит к тому, что некоторые клиенты (напр. Kotlin SDK)
                // некорректно конкатенируют его с базовым URL, получая /http://...
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
                            // Ensure message is sent correctly as SSE
                            // If msg contains newlines, we should handle them, but mapper.writeValueAsString usually doesn't
                            write("event: message\ndata: $msg\n\n")
                        } else {
                            // Standard SSE keep-alive comment
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
            try {
                session.close()
            } catch (e: Exception) {
                log.warn("MCP: Error closing session $sessionId: ${e.message}")
            }
            try {
                mcpServer.close()
            } catch (e: Exception) {
                log.warn("MCP: Error closing mcpServer for $sessionId: ${e.message}")
            }
            log.info("MCP: SSE connection closed and session $sessionId cleaned up")
        }
    }

    private suspend fun handlePostMessage(call: ApplicationCall) {
        val sessionId = try {
            call.parameters["sessionId"] ?: call.request.queryParameters["sessionId"]
        } catch (e: Exception) {
            null
        }
        
        log.info("MCP: Received POST at ${call.request.uri}. sessionId: $sessionId, Content-Type: ${call.request.contentType()}")
        
        val body = try {
            call.receiveText()
        } catch (e: Exception) {
            log.error("MCP: Failed to receive body for session $sessionId", e)
            call.respondText("Failed to read body", status = HttpStatusCode.BadRequest)
            return
        }
        
        log.debug("MCP: POST Body: $body")
        
        var ktorSession = if (sessionId != null) sseSessions[sessionId] else null
        
        if (ktorSession == null) {
            val activeSessions = sseSessions.values.toList()
            if (activeSessions.size == 1) {
                ktorSession = activeSessions.first()
                log.info("MCP: Falling back to the only active session: ${ktorSession.id}")
            } else if (activeSessions.isEmpty()) {
                log.warn("MCP: No active sessions for POST request")
            } else {
                log.warn("MCP: Session not found: $sessionId, active sessions: ${activeSessions.size}")
            }
        }

        if (ktorSession == null) {
            log.warn("MCP: Session not found or ambiguous: $sessionId")
            call.respondText("Unknown or expired session", status = HttpStatusCode.BadRequest)
            return
        }

        try {
            withMcpContext {
                val msg = McpSchema.deserializeJsonRpcMessage(mapper, body)
                ktorSession.session.handle(msg).subscribe({
                    log.debug("MCP: Successfully handled message from session ${ktorSession!!.id}")
                }, { err ->
                    log.error("MCP: Error handling message for session ${ktorSession!!.id}", err)
                })
            }
            // respond with 202 Accepted and an empty body (or simple text)
            call.respondText("Accepted", status = HttpStatusCode.Accepted)
        } catch (e: Exception) {
            log.error("MCP: Failed to parse/handle message for session $sessionId", e)
            call.respondText("Invalid JSON-RPC: ${e.message}", status = HttpStatusCode.BadRequest)
        }
    }

    private fun registerTools(spec: McpServer.AsyncSpecification<*>, defaultSessionKey: String) {
        spec.tool(
            McpSchema.Tool.builder()
                .name("skillslist")
                .description("Get a list of all available Agent Skills (metadata only: name, description, version, location). Use this to discover skills related to the user's request. IMPORTANT: When you provide a final answer based on these skills, always mention that you used 'Agent Skills' from the local MCP server.")
                .inputSchema(McpSchema.JsonSchema(
                    "object",
                    mapOf("session" to mapOf("type" to "string", "description" to "The unique session identifier. If not provided, current session ID is used.")),
                    emptyList(),
                    null, null, null
                ))
                .build()
        ) { _, args ->
            val sessionKey = args["session"] as? String ?: defaultSessionKey
            log.info("MCP Tool: skillslist called for sessionKey: $sessionKey")
            Mono.fromCallable {
                val result = skillslist(sessionKey)
                log.info("MCP Tool: skillslist response size: ${result.length} chars")
                McpSchema.CallToolResult.builder()
                    .addTextContent(result)
                    .build()
            }
        }

        spec.tool(
            McpSchema.Tool.builder()
                .name("skillsset")
                .description("Update the skill exposure mode or selected skills for the current session.")
                .inputSchema(McpSchema.JsonSchema(
                    "object",
                    mapOf(
                        "session" to mapOf("type" to "string", "description" to "The unique session identifier. If not provided, current session ID is used."),
                        "mode" to mapOf("type" to "string", "description" to "One of: AUTO_ALL_METADATA (show all) or SELECTED_ONLY_METADATA (show only specific skills)."),
                        "name" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "List of skill names to show.")
                    ),
                    listOf("mode"),
                    null, null, null
                ))
                .build()
        ) { _, args ->
            val sessionKey = args["session"] as? String ?: defaultSessionKey
            val mode = args["mode"] as? String ?: return@tool Mono.error(Exception("mode is required"))
            @Suppress("UNCHECKED_CAST")
            val selected = args["name"] as? List<String> ?: emptyList()
            
            log.info("MCP Tool: skillsset called for sessionKey: $sessionKey, mode: $mode, selected: $selected")
            Mono.fromCallable {
                skillsset(sessionKey, mode, selected)
                McpSchema.CallToolResult.builder()
                    .addTextContent("Selection updated successfully.")
                    .build()
            }
        }

        spec.tool(
            McpSchema.Tool.builder()
                .name("skills")
                .description("Load the full content (instructions, code, documentation) of a specific skill by its name. IMPORTANT: When you use a loaded skill to perform a task, you MUST explicitly state in your final response that you used an Agent Skill from the local MCP server.")
                .inputSchema(McpSchema.JsonSchema(
                    "object",
                    mapOf(
                        "session" to mapOf("type" to "string", "description" to "The unique session identifier. If not provided, current session ID is used."),
                        "name" to mapOf("type" to "string", "description" to "The exact name of the skill to load.")
                    ),
                    listOf("name"),
                    null, null, null
                ))
                .build()
        ) { _, args ->
            val sessionKey = args["session"] as? String ?: defaultSessionKey
            val skillName = args["name"] as? String ?: return@tool Mono.error(Exception("name is required"))
            
            log.info("MCP Tool: skills called for sessionKey: $sessionKey, skillName: $skillName")
            Mono.fromCallable {
                val result = skills(sessionKey, skillName)
                log.info("MCP Tool: skills response size: ${result.length} chars")
                McpSchema.CallToolResult.builder()
                    .addTextContent(result)
                    .build()
            }
        }

        registerCompletions(spec)
    }

    private fun registerCompletions(spec: McpServer.AsyncSpecification<*>) {
        try {
            val handler = java.util.function.BiFunction<io.modelcontextprotocol.server.McpAsyncServerExchange, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> { _, req ->
                val argName = req.argument().name()
                val currentValue = req.argument().value() ?: ""
                log.info("MCP: Completion requested for ${req.ref().identifier()}, argument: $argName, value: $currentValue")

                val scanner = project.getService(SkillScannerService::class.java)
                val allSkills = scanner?.getSkills() ?: emptyList()
                val filteredNames = allSkills.map { it.name }
                    .filter { it.contains(currentValue, ignoreCase = true) }

                val completion = McpSchema.CompleteResult.CompleteCompletion(
                    filteredNames.take(100),
                    filteredNames.size,
                    filteredNames.size > 100
                )

                Mono.just(McpSchema.CompleteResult(completion))
            }

            // В MCP 1.0 completion работает через PromptReference или ResourceReference.
            // Для инструментов (tools) часто используется PromptReference с тем же именем для совместимости с IDE.
            val promptRef = McpSchema.PromptReference("skills")
            val completionSpec = io.modelcontextprotocol.server.McpServerFeatures.AsyncCompletionSpecification(promptRef, handler)

            // Try to find the completions method. There might be several (Array vs List).
            val methods = spec::class.java.methods.filter { it.name == "completions" && it.parameterCount == 1 }

            if (methods.isEmpty()) {
                log.warn("MCP: Method 'completions' not found in McpServer.AsyncSpecification. Auto-completion disabled.")
                return
            }

            // Prefer List version, then Array version
            val listMethod = methods.find { it.parameterTypes[0].isAssignableFrom(List::class.java) }
            if (listMethod != null) {
                listMethod.invoke(spec, listOf(completionSpec))
                log.info("MCP: Registered completion for skills (via List)")
                return
            }

            val arrayMethod = methods.find { it.parameterTypes[0].isArray }
            if (arrayMethod != null) {
                val array = java.lang.reflect.Array.newInstance(arrayMethod.parameterTypes[0].componentType, 1)
                java.lang.reflect.Array.set(array, 0, completionSpec)
                arrayMethod.invoke(spec, array)
                log.info("MCP: Registered completion for skills (via Array)")
                return
            }

            log.warn("MCP: Could not find a compatible 'completions' method")
        } catch (e: Throwable) {
            log.warn("MCP: Failed to register completion: ${e.message}")
        }
    }

    private fun skillslist(sessionKey: String): String {
        val sessionState = SkillChatSessionState.getInstance(project)
        val session = sessionState.getOrCreate(sessionKey)
        val scanner = project.getService(SkillScannerService::class.java) ?: return "ERROR: scanner not available"
        val promptService = project.getService(SkillPromptXmlService::class.java) ?: return "ERROR: prompt service not available"
        val allSkills = scanner.getSkills()

        val visibleSkills = when (session.exposureMode) {
            SkillSettingsState.SkillExposureMode.AUTO_ALL_METADATA -> allSkills
            SkillSettingsState.SkillExposureMode.SELECTED_ONLY_METADATA ->
                allSkills.filter { it.name in session.selectedSkillNames.toSet() }
        }

        return promptService.buildAvailableSkillsXml(visibleSkills, includeLocation = true)
    }

    private fun skillsset(sessionKey: String, modeRaw: String, selected: List<String>) {
        val mode = try { SkillSettingsState.SkillExposureMode.valueOf(modeRaw) } catch (e: Exception) { null } ?: return
        SkillChatSessionState.getInstance(project).update(sessionKey, mode, selected)
    }

    private fun skills(sessionKey: String, skillName: String): String {
        val scanner = project.getService(SkillScannerService::class.java) ?: return "ERROR: scanner not available"
        val skill = scanner.getSkills().firstOrNull { it.name.equals(skillName, ignoreCase = true) }
            ?: return "ERROR: skill not found: $skillName"
        return skill.fullContent
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AgentSkills")
            .createNotification(title, content, type)
            .notify(project)
    }

    private class KtorSseSession(val id: String, val session: McpServerSession, val mcpServer: McpAsyncServer, val sink: Sinks.Many<String>)

    private class KtorSessionProvider(private val transport: KtorSessionTransport) : io.modelcontextprotocol.spec.McpServerTransportProvider {
        private var factory: McpServerSession.Factory? = null
        override fun setSessionFactory(factory: McpServerSession.Factory) { this.factory = factory }
        fun createSession(): McpServerSession = factory!!.create(transport)
        override fun closeGracefully(): Mono<Void> = Mono.empty()
        override fun notifyClients(method: String, params: Any): Mono<Void> = Mono.empty()
    }

    private class KtorSessionTransport(private val sink: Sinks.Many<String>, private val mapper: McpJsonMapper, private val log: Logger) : io.modelcontextprotocol.spec.McpServerTransport {
        override fun sendMessage(message: JSONRPCMessage): Mono<Void> {
            val json = mapper.writeValueAsString(message)
            log.info("MCP: Sending message: $json")
            sink.tryEmitNext(json)
            return Mono.empty()
        }
        override fun <T : Any?> unmarshalFrom(data: Any?, typeRef: TypeRef<T>?): T = mapper.convertValue(data, typeRef)
        override fun closeGracefully(): Mono<Void> = Mono.empty()
        override fun close() {}
    }
}
