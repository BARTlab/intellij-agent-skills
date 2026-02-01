package com.bartlab.agentskills.mcp

import com.bartlab.agentskills.AgentSkillsConstants
import com.bartlab.agentskills.settings.SkillSettingsState
import com.bartlab.agentskills.util.AgentSkillsNotifier
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Service for managing MCP (Model Context Protocol) server.
 *
 * Starts HTTP/SSE server for integration with AI assistants.
 * Server automatically starts on IDE startup if enabled in settings.
 *
 * @see SkillSettingsState.State.integrateAiAssistant
 */
@Service(Service.Level.PROJECT)
class SkillMcpServerService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(SkillMcpServerService::class.java)
    
    private val scopeRef = AtomicReference(createScope())
    private val scope: CoroutineScope
        get() = scopeRef.get()
    
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

    private fun createScope(): CoroutineScope = 
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Executes a code block with the correct ClassLoader for MCP libraries.
     */
    private fun <T> withMcpContext(block: () -> T): T {
        val oldLoader = Thread.currentThread().contextClassLoader
        return try {
            Thread.currentThread().contextClassLoader = SkillMcpServerService::class.java.classLoader
            block()
        } finally {
            Thread.currentThread().contextClassLoader = oldLoader
        }
    }

    /**
     * Synchronizes server state with settings.
     *
     * Stops the server if running, and restarts
     * if AI assistant integration is enabled in settings.
     */
    fun syncWithSettings() {
        stop()
        if (SkillSettingsState.getInstance(project).state.integrateAiAssistant) {
            scope.launch {
                // Give the port time to be released
                delay(AgentSkillsConstants.MCP_RESTART_DELAY_MS)
                ensureStarted()
            }
        }
    }

    /**
     * Stops the MCP server and closes all sessions.
     */
    fun stop() {
        val oldScope = scopeRef.getAndSet(createScope())
        oldScope.cancel()
        
        synchronized(engineLock) {
            ktorEngine?.stop(
                AgentSkillsConstants.KTOR_STOP_GRACE_MS,
                AgentSkillsConstants.KTOR_STOP_TIMEOUT_MS
            )
            ktorEngine = null
        }
        sessionHandler.closeAllSessions()
    }

    /**
     * Starts the MCP server if not running and integration is enabled.
     */
    fun ensureStarted() {
        if (!SkillSettingsState.getInstance(project).state.integrateAiAssistant) return

        synchronized(engineLock) {
            if (ktorEngine != null) return

            try {
                withMcpContext { startKtorServer() }
                log.info("MCP Server started (HTTP:$port)")
            } catch (e: Exception) {
                log.error("Failed to start MCP server", e)
                AgentSkillsNotifier.notify(
                    project,
                    "${AgentSkillsConstants.PLUGIN_NAME}: MCP server start error",
                    e.message ?: e.toString(),
                    NotificationType.ERROR
                )
            }
        }
    }

    private fun startKtorServer() {
        val engine = embeddedServer(CIO, host = AgentSkillsConstants.MCP_SERVER_HOST, port = port) {
            configureLogging()
            configureRouting()
        }
        engine.start(wait = false)
        ktorEngine = engine
    }
    
    private fun Application.configureLogging() {
        val serviceLog = this@SkillMcpServerService.log
        intercept(ApplicationCallPipeline.Call) {
            val uri = call.request.uri
            if (uri.contains("/messages") || uri.contains("/sse")) {
                serviceLog.info("MCP: Incoming ${call.request.httpMethod.value} $uri")
            }
        }
    }
    
    private fun Application.configureRouting() {
        routing {
            get("/sse") { sessionHandler.handleSse(call) }
            post("/messages/{sessionId}") { sessionHandler.handlePostMessage(call) }
            post("/messages") { sessionHandler.handlePostMessage(call) }
            get("/health") { call.respondText("OK") }
        }
    }
    
    override fun dispose() {
        stop()
        scopeRef.get().cancel()
    }
}
