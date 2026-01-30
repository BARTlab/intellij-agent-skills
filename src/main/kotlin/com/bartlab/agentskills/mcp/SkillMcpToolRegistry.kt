package com.bartlab.agentskills.mcp

import com.bartlab.agentskills.service.SkillPromptXmlService
import com.bartlab.agentskills.service.SkillScannerService
import com.bartlab.agentskills.settings.SkillSettingsState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import reactor.core.publisher.Mono
import java.lang.reflect.Array as ReflectArray
import java.util.function.BiFunction

class SkillMcpToolRegistry(
    private val project: Project,
    private val log: Logger
) {
    fun registerTools(spec: McpServer.AsyncSpecification<*>, defaultSessionKey: String) {
        spec.tools(
            McpServerFeatures.AsyncToolSpecification.builder()
                .tool(
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
                )
                .callHandler { _, req ->
                    val args = req.arguments()
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
                .build(),
            McpServerFeatures.AsyncToolSpecification.builder()
                .tool(
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
                )
                .callHandler { _, req ->
                    val args = req.arguments()
                    val sessionKey = args["session"] as? String ?: defaultSessionKey
                    val mode = args["mode"] as? String ?: throw Exception("mode is required")
                    @Suppress("UNCHECKED_CAST")
                    val selected = args["name"] as? List<String> ?: emptyList()

                    log.info("MCP Tool: skillsset called for sessionKey: $sessionKey, mode: $mode, selected: $selected")
                    skillsset(sessionKey, mode, selected)
                    Mono.just(McpSchema.CallToolResult.builder().build())
                }
                .build(),
            McpServerFeatures.AsyncToolSpecification.builder()
                .tool(
                    McpSchema.Tool.builder()
                        .name("skills")
                        .description("Load a full agent skill content by name. Use this when you need the full SKILL.md content for a particular skill.")
                        .inputSchema(McpSchema.JsonSchema(
                            "object",
                            mapOf(
                                "session" to mapOf("type" to "string", "description" to "The unique session identifier. If not provided, current session ID is used."),
                                "name" to mapOf("type" to "string", "description" to "Skill name from skillslist.")
                            ),
                            listOf("name"),
                            null, null, null
                        ))
                        .build()
                )
                .callHandler { _, req ->
                    val args = req.arguments()
                    val sessionKey = args["session"] as? String ?: defaultSessionKey
                    val skillName = args["name"] as? String ?: throw Exception("name is required")
                    log.info("MCP Tool: skills called for sessionKey: $sessionKey, name: $skillName")
                    Mono.fromCallable {
                        val result = skills(skillName)
                        McpSchema.CallToolResult.builder().addTextContent(result).build()
                    }
                }
                .build()
        )

        registerCompletions(spec)
    }

    private fun registerCompletions(spec: McpServer.AsyncSpecification<*>) {
        try {
            val handler = BiFunction<io.modelcontextprotocol.server.McpAsyncServerExchange, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> { _, req ->
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

            val promptRef = McpSchema.PromptReference("skills")
            val completionSpec = McpServerFeatures.AsyncCompletionSpecification(promptRef, handler)

            val methods = spec::class.java.methods.filter { it.name == "completions" && it.parameterCount == 1 }

            if (methods.isEmpty()) {
                log.warn("MCP: Method 'completions' not found in McpServer.AsyncSpecification. Auto-completion disabled.")
                return
            }

            val listMethod = methods.find { it.parameterTypes[0].isAssignableFrom(List::class.java) }
            if (listMethod != null) {
                listMethod.invoke(spec, listOf(completionSpec))
                log.info("MCP: Registered completion for skills (via List)")
                return
            }

            val arrayMethod = methods.find { it.parameterTypes[0].isArray }
            if (arrayMethod != null) {
                val array = ReflectArray.newInstance(arrayMethod.parameterTypes[0].componentType, 1)
                ReflectArray.set(array, 0, completionSpec)
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
        val mode = try { SkillSettingsState.SkillExposureMode.valueOf(modeRaw) } catch (_: Exception) { null } ?: return
        SkillChatSessionState.getInstance(project).update(sessionKey, mode, selected)
    }

    private fun skills(skillName: String): String {
        val scanner = project.getService(SkillScannerService::class.java) ?: return "ERROR: scanner not available"
        val skill = scanner.getSkills().firstOrNull { it.name.equals(skillName, ignoreCase = true) }
            ?: return "ERROR: skill not found: $skillName"
        return skill.fullContent
    }
}