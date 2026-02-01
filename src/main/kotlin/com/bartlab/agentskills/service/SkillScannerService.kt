package com.bartlab.agentskills.service

import com.bartlab.agentskills.AgentSkillsConstants
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.bartlab.agentskills.model.AgentSkill
import com.bartlab.agentskills.model.AgentPath
import com.bartlab.agentskills.model.AgentPathRegistry
import com.bartlab.agentskills.settings.SkillSettingsState
import com.bartlab.agentskills.util.AgentSkillsNotifier
import com.bartlab.agentskills.util.PathResolver
import java.io.File

/**
 * Service for scanning and finding skills (SKILL.md files) in the project.
 *
 * Scans standard AI agent directories and custom paths.
 * Results are cached and updated when [scan] is called.
 */
@Service(Service.Level.PROJECT)
class SkillScannerService(private val project: Project) {
    private val log = Logger.getInstance(SkillScannerService::class.java)

    private val skillMap = mutableMapOf<String, AgentSkill>()
    private val fileService = project.getService(SkillFileService::class.java)
    private val yamlParser = SkillYamlParser()

    /**
     * Scans all directories for SKILL.md files.
     *
     * If custom path is enabled in settings, scans only that path.
     * Otherwise scans standard directories of all supported agents
     * both in project and globally.
     */
    fun scan() {
        log.info("Scanner: starting scan...")
        skillMap.clear()

        val basePath = project.basePath
        if (basePath == null) {
            log.warn("scan(): project.basePath is null (project='${project.name}')")
            AgentSkillsNotifier.notify(
                project,
                "Scanning: project.basePath is null",
                "Open a project and try again.",
                NotificationType.WARNING
            )
            return
        }

        val settings = SkillSettingsState.getInstance(project).state

        if (settings.useCustomPath) {
            scanCustomPath(settings.customPath)
        } else {
            scanAgentPaths(basePath)
        }

        log.info("scan(): found total ${skillMap.size} skills")
    }
    
    private fun scanCustomPath(customPath: String) {
        val customRaw = customPath.trim()
        if (customRaw.isNotEmpty()) {
            scanDirectory(File(PathResolver.expandPath(customRaw)))
        }
    }
    
    private fun scanAgentPaths(basePath: String) {
        // Project paths from agents
        for (ap in AgentPathRegistry.agents) {
            scanDirectory(File(basePath, ap.projectPath))
        }

        // Global paths from agents
        for (ap in AgentPathRegistry.agents) {
            scanDirectory(File(PathResolver.expandPath(ap.globalPath)))
        }

        // Default project scan (backward compatibility)
        scanDirectory(File(basePath))
    }

    private fun scanDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return

        log.info("scanDirectory(): scanning ${dir.absolutePath}")
        fileService.findSkillFiles(dir, AgentSkillsConstants.SKILL_SCAN_MAX_DEPTH)
            .forEach { file ->
                val content = fileService.readText(file) ?: return@forEach
                val skill = parseSkill(file.parentFile.name, content, file.absolutePath)
                if (skill != null) {
                    skillMap.putIfAbsent(skill.name, skill)
                }
            }
    }

    /**
     * Shortens path for UI display.
     *
     * @param path Full path
     * @return Shortened path relative to project or with home replaced by ~
     */
    fun shortenPath(path: String): String =
        PathResolver.shortenPath(path, project.basePath)

    /**
     * Expands path, replacing ~ with home directory.
     *
     * @param path Path to expand
     * @return Absolute path
     */
    fun expandPath(path: String): String =
        PathResolver.expandPath(path)

    private fun parseSkill(dirName: String, content: String, path: String): AgentSkill? {
        val metadata = yamlParser.parseFrontMatter(content) ?: return null
        val body = yamlParser.stripFrontMatter(content)

        val name = metadata.name?.ifBlank { dirName } ?: dirName
        
        validateSkillName(name, dirName, path)

        return AgentSkill(
            name = name,
            description = metadata.description.orEmpty(),
            version = metadata.version.orEmpty(),
            path = path,
            fullContent = body,
            metadata = metadata.metadata,
            license = metadata.license,
            compatibility = metadata.compatibility,
            allowedTools = metadata.allowedTools
        ).also {
            log.info("Scanner: parsed skill '$name' from $path")
        }
    }
    
    private fun validateSkillName(name: String, dirName: String, path: String) {
        val isNameValid = name.length in AgentSkillsConstants.SKILL_NAME_MIN_LENGTH..AgentSkillsConstants.SKILL_NAME_MAX_LENGTH 
            && AgentSkillsConstants.SKILL_NAME_REGEX.matches(name)
        
        if (!isNameValid) {
            log.warn("Skill name '$name' in $path is invalid according to specification")
        }
        
        if (name != dirName) {
            log.warn("Skill name '$name' in $path does not match directory name '$dirName'")
        }
    }

    /**
     * Returns list of all discovered skills.
     */
    fun getSkills(): List<AgentSkill> = skillMap.values.toList()

    /**
     * Returns list of all supported agents with their paths.
     */
    fun getAgentPaths(): List<AgentPath> = AgentPathRegistry.agents
}