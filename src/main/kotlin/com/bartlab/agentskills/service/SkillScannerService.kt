package com.bartlab.agentskills.service

import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.bartlab.agentskills.model.AgentSkill
import com.bartlab.agentskills.settings.SkillSettingsState
import com.bartlab.agentskills.util.AgentSkillsNotifier
import com.bartlab.agentskills.util.PathResolver
import java.io.File

@Service(Service.Level.PROJECT)
class SkillScannerService(private val project: Project) {
    private val log = Logger.getInstance(SkillScannerService::class.java)

    private val skillMap = mutableMapOf<String, AgentSkill>()

    data class AgentPath(val name: String, val projectPath: String, val globalPath: String)
    
    private val agentPaths = listOf(
        AgentPath("Amp", ".agents/skills", "~/.config/agents/skills"),
        AgentPath("Antigravity", ".agent/skills", "~/.gemini/antigravity/global_skills"),
        AgentPath("Claude Code", ".claude/skills", "~/.claude/skills"),
        AgentPath("Clawdbot", "skills", "~/.clawdbot/skills"),
        AgentPath("Cline", ".cline/skills", "~/.cline/skills"),
        AgentPath("Codex", ".codex/skills", "~/.codex/skills"),
        AgentPath("Command Code", ".commandcode/skills", "~/.commandcode/skills"),
        AgentPath("Continue", ".continue/skills", "~/.continue/skills"),
        AgentPath("Crush", ".crush/skills", "~/.config/crush/skills"),
        AgentPath("Cursor", ".cursor/skills", "~/.cursor/skills"),
        AgentPath("Droid", ".factory/skills", "~/.factory/skills"),
        AgentPath("Gemini CLI", ".gemini/skills", "~/.gemini/skills"),
        AgentPath("GitHub Copilot", ".github/skills", "~/.copilot/skills"),
        AgentPath("Goose", ".goose/skills", "~/.config/goose/skills"),
        AgentPath("Kilo Code", ".kilocode/skills", "~/.kilocode/skills"),
        AgentPath("Kiro CLI", ".kiro/skills", "~/.kiro/skills"),
        AgentPath("MCPJam", ".mcpjam/skills", "~/.mcpjam/skills"),
        AgentPath("OpenCode", ".opencode/skills", "~/.config/opencode/skills"),
        AgentPath("OpenHands", ".openhands/skills", "~/.openhands/skills"),
        AgentPath("Pi", ".pi/skills", "~/.pi/skills"),
        AgentPath("Qoder", ".qoder/skills", "~/.qoder/skills"),
        AgentPath("Qwen Code", ".qwen/skills", "~/.qwen/skills"),
        AgentPath("Roo Code", ".roo/skills", "~/.roo/skills"),
        AgentPath("Trae", ".trae/skills", "~/.trae/skills"),
        AgentPath("Windsurf", ".windsurf/skills", "~/.codeium/windsurf/skills"),
        AgentPath("Zencoder", ".zencoder/skills", "~/.zencoder/skills"),
        AgentPath("Neovate", ".neovate/skills", "~/.neovate/skills")
    )

    fun scan() {
        log.info("Scanner: starting scan...")
        skillMap.clear()

        val basePath = project.basePath
        if (basePath == null) {
            log.warn("scan(): project.basePath is null (project='${project.name}')")
            AgentSkillsNotifier.notify(project, "Scanning: project.basePath is null", "Open a project and try again.", NotificationType.WARNING)
            return
        }

        val settings = SkillSettingsState.getInstance(project).state

        // 1. Custom Path
        if (settings.useCustomPath) {
            val customRaw = settings.customPath.trim()
            if (customRaw.isNotEmpty()) {
                scanDirectory(File(PathResolver.expandPath(customRaw)))
            }
        } else {
            // 2. Auto Search
            // Project paths from agents
            for (ap in agentPaths) {
                scanDirectory(File(basePath, ap.projectPath))
            }

            // Global paths from agents
            val userHome = System.getProperty("user.home")
            for (ap in agentPaths) {
                if (ap.globalPath.startsWith("~")) {
                    val path = ap.globalPath.replaceFirst("~", userHome)
                    scanDirectory(File(path))
                }
            }

            // Default project scan (backward compatibility)
            scanDirectory(File(basePath))
        }

        log.info("scan(): found total ${skillMap.size} skills")
    }

    private fun scanDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return

        log.info("scanDirectory(): scanning ${dir.absolutePath}")
        dir.walkTopDown()
            .maxDepth(3)
            .filter { it.name == "SKILL.md" }
            .forEach { file ->
                val content = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val skill = parseSkill(file.parentFile.name, content, file.absolutePath)
                if (skill != null) {
                    if (!skillMap.containsKey(skill.name)) {
                        skillMap[skill.name] = skill
                    }
                }
            }
    }

    fun shortenPath(path: String): String {
        return PathResolver.shortenPath(path, project.basePath)
    }

    fun expandPath(path: String): String {
        return PathResolver.expandPath(path)
    }

    private fun parseSkill(dirName: String, content: String, path: String): AgentSkill? {
        val regex = "---([\\s\\S]*?)---".toRegex()
        val match = regex.find(content) ?: return null
        val yamlBlock = match.groupValues[1]
        val body = content.replace(regex, "").trim()

        val lines = yamlBlock.lines().map { it.trimEnd() }
        
        var name = dirName
        var description = ""
        var license: String? = null
        var compatibility: String? = null
        val allowedTools = mutableListOf<String>()
        val metadata = mutableMapOf<String, String>()
        var version = ""

        var currentSection = "" // "metadata" or ""

        for (line in lines) {
            if (line.isBlank()) continue
            
            val trimmedLine = line.trim()
            
            // Обработка вложенных метаданных
            if (line.startsWith("  ") && currentSection == "metadata") {
                val kvMatch = "^\\s*([^:]+):\\s*(.*)$".toRegex().find(line)
                if (kvMatch != null) {
                    val key = kvMatch.groupValues[1].trim()
                    val rawValue = kvMatch.groupValues[2].trim()
                    val value = rawValue.split("#")[0].trim().removeSurrounding("\"").removeSurrounding("'")
                    metadata[key] = value
                    if (key == "version") version = value
                }
                continue
            }

            val kvMatch = "^([^:]+):\\s*(.*)$".toRegex().find(trimmedLine)
            if (kvMatch != null) {
                val key = kvMatch.groupValues[1].trim()
                val rawValue = kvMatch.groupValues[2].trim()
                val value = rawValue.split("#")[0].trim().removeSurrounding("\"").removeSurrounding("'")

                when (key) {
                    "name" -> name = value
                    "description" -> description = value
                    "license" -> license = value
                    "compatibility" -> compatibility = value
                    "allowed-tools" -> allowedTools.addAll(value.split("\\s+".toRegex()).filter { it.isNotBlank() })
                    "metadata" -> {
                        currentSection = "metadata"
                    }
                    "version" -> version = value
                    else -> {
                        currentSection = ""
                    }
                }
            } else {
                currentSection = ""
            }
        }

        // Валидация name согласно спецификации
        val isNameValid = name.length in 1..64 && 
                          name.all { it.isLowerCase() || it.isDigit() || it == '-' } &&
                          !name.startsWith("-") && !name.endsWith("-") &&
                          !name.contains("--")
        
        if (!isNameValid) {
            log.warn("Skill name '$name' in $path is invalid according to specification")
        }
        
        if (name != dirName) {
            log.warn("Skill name '$name' in $path does not match directory name '$dirName'")
        }

        val skill = AgentSkill(
            name = name,
            description = description,
            version = version,
            path = path,
            fullContent = body,
            metadata = metadata,
            license = license,
            compatibility = compatibility,
            allowedTools = allowedTools
        )
        log.info("Scanner: parsed skill '$name' from $path")
        return skill
    }

    fun getSkills(): List<AgentSkill> = skillMap.values.toList()

    fun getAgentPaths(): List<AgentPath> = agentPaths

    fun findSkillForPrompt(prompt: String): AgentSkill? {
        return skillMap.values.find { skill ->
            prompt.contains(skill.name, ignoreCase = true) ||
                skill.description.split(" ").any { it.length > 3 && prompt.contains(it, ignoreCase = true) }
        }
    }
}