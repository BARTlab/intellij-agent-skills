package com.bartlab.agentskills.mcp

import com.bartlab.agentskills.settings.SkillSettingsState
import com.bartlab.agentskills.util.AgentSkillsNotifier
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.response.respondText
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.json.schema.jackson.JacksonJsonSchemaValidatorSupplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class SkillMcpServerService(private val project: Project) {
    private val log = Logger.getInstance(SkillMcpServerService::class.java)
    private var scope = createScope()
    private val host = "0.0.0.0"
    private val port: Int
        get() = SkillSettingsState.getInstance(project).state.mcpPort

    private var ktorEngine: ApplicationEngine? = null
    private val engineLock = Any()
    private val toolRegistry = SkillMcpToolRegistry(project, log)
    private val contextRunner = object : SkillMcpContextRunner {
        override fun <T> run(block: () -> T): T = withMcpContext(block)
    }
    private val sessionHandler by lazy {
        SkillMcpSessionHandler(mapper, validator, toolRegistry, log, contextRunner)
    }

    private val mapper: McpJsonMapper by lazy {
        withMcpContext { JacksonMcpJsonMapper(ObjectMapper().registerKotlinModule()) }
    }
    private val validator by lazy {
        withMcpContext { JacksonJsonSchemaValidatorSupplier().get() }
    }

    private fun createScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            // Give the port a moment to release
            scope.launch {
                delay(300)
                ensureStarted()
            }
        }
    }

    fun stop() {
        scope.cancel()
        synchronized(engineLock) {
            ktorEngine?.stop(200, 500)
            ktorEngine = null
        }
        sessionHandler.closeAllSessions()
        scope = createScope()
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
                AgentSkillsNotifier.notify(project, "Agent Skills: MCP server start error", e.message ?: e.toString(), NotificationType.ERROR)
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
                get("/sse") { sessionHandler.handleSse(call) }
                post("/messages/{sessionId}") { sessionHandler.handlePostMessage(call) }
                post("/messages") { sessionHandler.handlePostMessage(call) }
                get("/health") { call.respondText("OK") }
            }
        }
        engine.start(wait = false)
        this.ktorEngine = engine
    }
}
