package com.bartlab.agentskills.service

import com.bartlab.agentskills.AgentSkillsConstants
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.bartlab.agentskills.model.AgentSkill
import com.bartlab.agentskills.model.AgentPath
import com.bartlab.agentskills.model.AgentPathRegistry
import com.bartlab.agentskills.model.DiscoveredSkill
import com.bartlab.agentskills.model.SkillSource
import com.bartlab.agentskills.model.SkillSourceType
import com.bartlab.agentskills.settings.SkillSettingsState
import com.bartlab.agentskills.ui.DiscoveredSkillsDialog
import com.bartlab.agentskills.util.AgentSkillsNotifier
import com.bartlab.agentskills.util.PathResolver
import com.bartlab.agentskills.util.SkillFileFilter
import java.io.File
import java.net.URI
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Service for managing skills: creation, addition, update and deletion.
 *
 * Supports installing skills from various sources:
 * - Local directories
 * - GitHub repositories
 * - GitLab repositories
 * - Direct URLs to SKILL.md files
 */
@Service(Service.Level.PROJECT)
class SkillManagerService(private val project: Project) {
    private val log = Logger.getInstance(SkillManagerService::class.java)
    
    private val fileService: SkillFileService
        get() = project.getService(SkillFileService::class.java)
    
    private val gitService: GitOperationsService
        get() = project.getService(GitOperationsService::class.java)
    
    private val scanner: SkillScannerService
        get() = project.getService(SkillScannerService::class.java)
    
    private val yamlParser = SkillYamlParser()
    
    private val gson: Gson by lazy {
        GsonBuilder().setPrettyPrinting().create()
    }

    /**
     * Initializes a new skill with SKILL.md template.
     *
     * @param name Skill name
     * @param selectedAgents List of agents for which to create links/copies
     * @param createSymlinks Create symbolic links instead of copies
     * @param global Install globally (in home directory)
     * @return true if initialization was successful
     */
    fun initSkill(
        name: String,
        selectedAgents: List<String>,
        createSymlinks: Boolean,
        global: Boolean = false
    ): Boolean {
        val targetBaseDir = resolveSkillsDirectory(global) ?: return false

        if (!fileService.ensureDirectory(targetBaseDir)) {
            notifyError("Init Skill", "Failed to create directory: ${targetBaseDir.absolutePath}")
            return false
        }

        val skillDir = File(targetBaseDir, name)
        if (skillDir.exists()) {
            notifyWarning("Init Skill", "Skill directory already exists: ${skillDir.absolutePath}")
            return false
        }

        if (!fileService.ensureDirectory(skillDir)) {
            notifyError("Init Skill", "Failed to create skill directory: ${skillDir.absolutePath}")
            return false
        }

        val skillMd = File(skillDir, AgentSkillsConstants.SKILL_FILE_NAME)
        val template = buildSkillTemplate(name)
        
        if (!fileService.writeText(skillMd, template)) {
            notifyError("Init Skill", "Failed to create ${AgentSkillsConstants.SKILL_FILE_NAME}")
            return false
        }

        distributeSkillToAgents(skillDir, selectedAgents, createSymlinks, global)
        
        notifyInfo("Init Skill", "Successfully initialized $name")
        return true
    }
    
    private fun buildSkillTemplate(name: String): String = """
        ---
        name: $name
        description: Description for $name
        metadata:
          version: 1.0.0
        ---
        
        # $name
        
        Welcome to your new agent skill!
    """.trimIndent()

    /**
     * Adds a skill from an external source.
     *
     * @param sourceInput Skill source (URL, path, shorthand)
     * @param selectedAgents List of agents for installation
     * @param createSymlinks Create symbolic links
     * @param global Global installation
     * @return true if addition was successful
     */
    fun addSkill(
        sourceInput: String,
        selectedAgents: List<String>,
        createSymlinks: Boolean,
        global: Boolean = false
    ): Boolean {
        val source = SkillSourceParser.parse(sourceInput) ?: run {
            notifyError("Add Skill", "Invalid skill source: $sourceInput")
            return false
        }
        
        var tempDir: File? = null
        return try {
            val skillsDir = resolveSourceDirectory(source, tempDir = { tempDir = it }) ?: return false

            val discoveredSkills = discoverSkills(skillsDir)
            if (discoveredSkills.isEmpty()) {
                notifyError("Add Skill", "No valid skills found in $sourceInput")
                return false
            }

            val selectedSkills = selectSkillsForInstallation(discoveredSkills, skillsDir) ?: return false

            for (skillInfo in selectedSkills) {
                installDiscoveredSkill(skillInfo, source, selectedAgents, createSymlinks, global, tempDir)
            }
            
            notifyInfo("Add Skill", "Successfully added ${selectedSkills.size} skill(s)")
            true
        } catch (e: Exception) {
            log.error("Error adding skill", e)
            notifyError("Add Skill", "Error: ${e.message}")
            false
        } finally {
            tempDir?.let { fileService.deleteRecursively(it) }
        }
    }
    
    private fun resolveSourceDirectory(
        source: SkillSource,
        tempDir: (File) -> Unit
    ): File? {
        return when (source.type) {
            SkillSourceType.LOCAL -> File(source.localPath!!)
            
            SkillSourceType.DIRECT_URL -> {
                val temp = fileService.createTempDir(AgentSkillsConstants.TEMP_DIR_DIRECT_PREFIX)
                tempDir(temp)
                val skillFile = File(temp, AgentSkillsConstants.SKILL_FILE_NAME)
                if (!fileService.downloadToFile(URI.create(source.url), skillFile)) {
                    notifyError("Add Skill", "Failed to download ${source.url}")
                    return null
                }
                temp
            }
            
            SkillSourceType.GITHUB, SkillSourceType.GITLAB -> {
                val temp = fileService.createTempDir(AgentSkillsConstants.TEMP_DIR_CLONE_PREFIX)
                tempDir(temp)
                
                if (!gitService.clone(temp, source.url, source.ref)) {
                    notifyError("Add Skill", "Failed to clone ${source.url}")
                    return null
                }
                
                if (source.subpath != null) File(temp, source.subpath) else temp
            }
        }
    }
    
    private fun selectSkillsForInstallation(
        discoveredSkills: List<DiscoveredSkill>,
        skillsDir: File
    ): List<DiscoveredSkill>? {
        return if (discoveredSkills.size > 1) {
            val picked = selectSkillsToInstall(discoveredSkills, skillsDir)
            if (picked == null) {
                notifyInfo("Add Skill", "Skill installation canceled")
                return null
            }
            if (picked.isEmpty()) {
                notifyWarning("Add Skill", "No skills selected for installation")
                return null
            }
            picked
        } else {
            discoveredSkills
        }
    }

    private fun discoverSkills(baseDir: File): List<DiscoveredSkill> {
        val skills = mutableListOf<DiscoveredSkill>()
        
        // Check if the dir itself is a skill
        val directSkillMd = File(baseDir, AgentSkillsConstants.SKILL_FILE_NAME)
        if (directSkillMd.exists()) {
            parseSkillName(directSkillMd)?.let { name ->
                skills.add(DiscoveredSkill(name, baseDir, directSkillMd))
                return skills // If root is a skill, we usually don't look deeper
            }
        }

        // Search in priority directories first
        searchPriorityDirectories(baseDir, skills)

        // Deep search if none found
        if (skills.isEmpty()) {
            searchDeep(baseDir, skills)
        }

        return skills
    }
    
    private fun searchPriorityDirectories(baseDir: File, skills: MutableList<DiscoveredSkill>) {
        val priorityDirs = listOf(
            "skills", "skills/.curated", "skills/.experimental", "skills/.system",
            ".agent/skills", ".agents/skills", ".claude/skills", ".cline/skills",
            ".cursor/skills", ".github/skills", ".roo/skills"
        )

        for (relDir in priorityDirs) {
            val dir = File(baseDir, relDir)
            if (dir.exists() && dir.isDirectory) {
                scanSubdirectories(dir, skills)
            }
        }
    }
    
    private fun scanSubdirectories(dir: File, skills: MutableList<DiscoveredSkill>) {
        dir.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory) {
                val skillMd = File(subDir, AgentSkillsConstants.SKILL_FILE_NAME)
                if (skillMd.exists()) {
                    parseSkillName(skillMd)?.let { name ->
                        if (skills.none { it.name == name }) {
                            skills.add(DiscoveredSkill(name, subDir, skillMd))
                        }
                    }
                }
            }
        }
    }
    
    private fun searchDeep(baseDir: File, skills: MutableList<DiscoveredSkill>) {
        fileService.findSkillFiles(baseDir, AgentSkillsConstants.SKILL_DISCOVER_MAX_DEPTH)
            .forEach { skillMd ->
                parseSkillName(skillMd)?.let { name ->
                    if (skills.none { it.name == name }) {
                        skills.add(DiscoveredSkill(name, skillMd.parentFile, skillMd))
                    }
                }
            }
    }

    private fun parseSkillName(skillMd: File): String? {
        val content = fileService.readText(skillMd) ?: return null
        return yamlParser.parseFrontMatter(content)?.name?.trim()
    }

    private fun selectSkillsToInstall(
        discoveredSkills: List<DiscoveredSkill>,
        baseDir: File
    ): List<DiscoveredSkill>? {
        val app = ApplicationManager.getApplication()
        var result: List<DiscoveredSkill>? = null
        val showDialog = {
            val items = discoveredSkills.mapIndexed { index, skill ->
                val displayPath = try {
                    skill.dir.relativeTo(baseDir).path.ifBlank { "." }
                } catch (e: Exception) {
                    skill.dir.path
                }
                DiscoveredSkillsDialog.DiscoveredSkillItem(skill.name, displayPath, index)
            }
            val dialog = DiscoveredSkillsDialog(project, items)
            result = if (dialog.showAndGet()) {
                val selected = dialog.getSelectedIndices().toSet()
                discoveredSkills.filterIndexed { index, _ -> index in selected }
            } else {
                null
            }
        }

        if (app.isDispatchThread) {
            showDialog()
        } else {
            app.invokeAndWait(showDialog)
        }
        return result
    }

    private fun installDiscoveredSkill(
        skillInfo: DiscoveredSkill,
        source: SkillSource,
        selectedAgents: List<String>,
        createSymlinks: Boolean,
        global: Boolean,
        baseSourceDir: File? = null
    ) {
        val masterDir = resolveSkillsDirectory(global) ?: return
        val sanitizedName = sanitizeSkillName(skillInfo.name)
        val targetSkillDir = File(masterDir, sanitizedName)
        
        SkillFileFilter.copyFiltered(skillInfo.dir, targetSkillDir)
        saveSourceInfo(targetSkillDir, source, skillInfo, baseSourceDir)
        distributeSkillToAgents(targetSkillDir, selectedAgents, createSymlinks, global)
    }
    
    private fun sanitizeSkillName(name: String): String =
        name.lowercase()
            .replace("[^a-z0-9]".toRegex(), "-")
            .replace("-+".toRegex(), "-")
            .trim('-')

    private fun saveSourceInfo(
        skillDir: File,
        source: SkillSource,
        skillInfo: DiscoveredSkill,
        baseSourceDir: File? = null
    ) {
        val actualSubpath = baseSourceDir?.let { base ->
            runCatching {
                skillInfo.dir.relativeTo(base).path
                    .replace('\\', '/')
                    .takeIf { it.isNotEmpty() && it != "." }
            }.getOrNull()
        } ?: source.subpath

        val sourceInfo = mapOf(
            "type" to source.type.name.lowercase(),
            "url" to source.url,
            "ref" to source.ref,
            "subpath" to actualSubpath,
            "skillName" to skillInfo.name,
            "installedAt" to java.time.Instant.now().toString()
        )
        
        val sourceFile = File(skillDir, AgentSkillsConstants.SKILL_SOURCE_FILE_NAME)
        fileService.writeText(sourceFile, gson.toJson(sourceInfo))
    }

    private fun copySkillToAgents(skillDir: File, agents: List<String>, global: Boolean) {
        val allAgentPaths = scanner.getAgentPaths()
        
        for (agentName in agents) {
            val agentPath = allAgentPaths.find { it.name == agentName } ?: continue
            val targetDir = resolveAgentPath(agentPath, global)
            
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                log.warn("Failed to create agent directory for copy: ${targetDir.absolutePath}")
                continue
            }
            
            val destSkillDir = File(targetDir, skillDir.name)
            skillDir.copyRecursively(destSkillDir, overwrite = true)
            log.info("Copied skill to agent: ${destSkillDir.absolutePath}")
        }
    }
    
    private fun distributeSkillToAgents(
        skillDir: File,
        agents: List<String>,
        createSymlinks: Boolean,
        global: Boolean
    ) {
        if (createSymlinks) {
            linkSkill(skillDir, agents, global)
        } else {
            copySkillToAgents(skillDir, agents, global)
        }
    }
    
    private fun resolveAgentPath(agentPath: AgentPath, global: Boolean): File {
        val path = if (global) agentPath.globalPath else agentPath.projectPath
        return if (global || path.startsWith("~") || path.startsWith("/")) {
            File(PathResolver.expandPath(path))
        } else {
            val basePath = project.basePath
            if (basePath != null) File(basePath, path) else File(PathResolver.expandPath(path))
        }
    }
    
    private fun resolveSkillsDirectory(global: Boolean): File? {
        val basePath = project.basePath ?: return null
        val settings = SkillSettingsState.getInstance(project).state
        
        return when {
            global -> File(PathResolver.expandPath(AgentSkillsConstants.DEFAULT_GLOBAL_SKILLS_PATH))
            settings.useCustomPath -> File(PathResolver.expandPath(settings.customPath))
            else -> File(basePath, AgentSkillsConstants.DEFAULT_PROJECT_SKILLS_PATH)
        }
    }

    /**
     * Updates a skill from its original source.
     *
     * @param skill Skill to update
     * @return true if update was successful
     */
    fun updateSkill(skill: AgentSkill): Boolean {
        val skillMdFile = File(skill.path)
        val skillDir = skillMdFile.parentFile ?: return false
        val sourceFile = File(skillDir, AgentSkillsConstants.SKILL_SOURCE_FILE_NAME)
        
        if (!sourceFile.exists()) {
            return updateFromGitOrFail(skill, skillDir)
        }

        return try {
            val sourceInfo = parseSourceInfo(sourceFile)
            val updateResult = performUpdate(sourceInfo, skillDir, skillMdFile)
            
            if (updateResult) {
                updateSourceTimestamp(sourceFile, sourceInfo)
                notifyInfo("Update Skill", "Successfully updated ${skill.name}")
            }
            updateResult
        } catch (e: Exception) {
            log.error("Error updating skill", e)
            notifyError("Update Skill", "Error updating ${skill.name}: ${e.message}")
            false
        }
    }
    
    private fun updateFromGitOrFail(skill: AgentSkill, skillDir: File): Boolean {
        if (gitService.isGitRepository(skillDir)) {
            val success = gitService.pull(skillDir)
            if (success) {
                notifyInfo("Update Skill", "Successfully updated ${skill.name}")
            } else {
                notifyError("Update Skill", "Failed to update ${skill.name}")
            }
            return success
        }
        notifyWarning("Update Skill", "No source information for ${skill.name}")
        return false
    }
    
    private fun parseSourceInfo(sourceFile: File): Map<String, Any?> {
        val typeToken = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
        return Gson().fromJson(sourceFile.readText(), typeToken)
    }
    
    private fun performUpdate(
        sourceInfo: Map<String, Any?>,
        skillDir: File,
        skillMdFile: File
    ): Boolean {
        val type = sourceInfo["type"] as String
        val url = sourceInfo["url"] as String
        val ref = sourceInfo["ref"] as? String
        val subpath = sourceInfo["subpath"] as? String

        return when (type) {
            "github", "gitlab" -> updateFromRemote(skillDir, url, ref, subpath)
            "direct_url" -> updateFromDirectUrl(url, skillMdFile)
            "local" -> updateFromLocal(url, skillDir)
            else -> false
        }
    }
    
    private fun updateFromRemote(skillDir: File, url: String, ref: String?, subpath: String?): Boolean {
        return if (gitService.isGitRepository(skillDir)) {
            gitService.pull(skillDir)
        } else {
            updateFromRemoteRepo(skillDir, url, ref, subpath)
        }
    }
    
    private fun updateFromDirectUrl(url: String, skillMdFile: File): Boolean {
        return runCatching {
            URI.create(url).toURL().openStream().use { input ->
                skillMdFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrElse {
            log.error("Failed to download from $url", it)
            false
        }
    }
    
    private fun updateFromLocal(url: String, skillDir: File): Boolean {
        val localDir = File(url)
        return if (localDir.exists()) {
            SkillFileFilter.copyFiltered(localDir, skillDir)
            true
        } else {
            false
        }
    }
    
    private fun updateSourceTimestamp(sourceFile: File, sourceInfo: Map<String, Any?>) {
        val mutableInfo = sourceInfo.toMutableMap()
        mutableInfo["updatedAt"] = java.time.Instant.now().toString()
        fileService.writeText(sourceFile, gson.toJson(mutableInfo))
    }

    private fun updateFromRemoteRepo(
        skillDir: File,
        url: String,
        ref: String?,
        subpath: String?
    ): Boolean {
        val tempDir = fileService.createTempDir(AgentSkillsConstants.TEMP_DIR_UPDATE_PREFIX)
        return try {
            if (!gitService.cloneForUpdate(tempDir, url, ref)) {
                return false
            }
            
            val sourceDir = resolveSubpath(tempDir, subpath)
            clearSkillDirPreservingSource(skillDir)
            SkillFileFilter.copyFiltered(sourceDir, skillDir)
            true
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    private fun resolveSubpath(baseDir: File, subpath: String?): File {
        if (subpath.isNullOrEmpty()) return baseDir
        val subDir = File(baseDir, subpath)
        return if (subDir.exists()) subDir else baseDir
    }
    
    private fun clearSkillDirPreservingSource(skillDir: File) {
        skillDir.listFiles()?.forEach {
            if (it.name != AgentSkillsConstants.SKILL_SOURCE_FILE_NAME) {
                it.deleteRecursively()
            }
        }
    }

    /**
     * Deletes a skill and all associated symbolic links.
     *
     * @param skill Skill to delete
     * @return true if deletion was successful
     */
    fun deleteSkill(skill: AgentSkill): Boolean {
        val skillFile = File(skill.path)
        val skillDir = skillFile.parentFile ?: return false
        
        removeLinks(skillDir)
        
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            notifyInfo("Delete Skill", "Successfully deleted ${skill.name}")
        } else {
            notifyError("Delete Skill", "Failed to delete ${skill.name}")
        }
        return deleted
    }

    private fun removeLinks(skillDir: File) {
        val targetDirs = collectAgentDirectories()

        for (dir in targetDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            removeSymlinkIfMatches(dir, skillDir)
        }
    }
    
    private fun collectAgentDirectories(): Set<File> {
        val targetDirs = mutableSetOf<File>()
        val basePath = project.basePath
        
        for (ap in scanner.getAgentPaths()) {
            if (basePath != null) {
                targetDirs.add(File(basePath, ap.projectPath))
            }
            targetDirs.add(File(PathResolver.expandPath(ap.globalPath)))
        }

        val settings = SkillSettingsState.getInstance(project).state
        if (settings.useCustomPath && settings.customPath.isNotBlank()) {
            targetDirs.add(File(PathResolver.expandPath(settings.customPath)))
        }
        
        return targetDirs
    }
    
    private fun removeSymlinkIfMatches(dir: File, skillDir: File) {
        val linkFile = File(dir, skillDir.name)
        if (!linkFile.exists()) return
        
        try {
            if (fileService.isSymbolicLink(linkFile)) {
                val target = fileService.readSymbolicLink(linkFile)
                if (target != null && target.toAbsolutePath() == skillDir.toPath().toAbsolutePath()) {
                    fileService.deleteIfExists(linkFile)
                    log.info("Removed symlink: ${linkFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            log.error("Failed to remove symlink: ${linkFile.absolutePath}", e)
        }
    }

    private fun linkSkill(skillDir: File, agents: List<String>, global: Boolean) {
        val allAgentPaths = scanner.getAgentPaths()
        
        for (agentName in agents) {
            val agentPath = allAgentPaths.find { it.name == agentName } ?: continue
            val targetLinkDir = resolveAgentPath(agentPath, global)
            
            if (!targetLinkDir.exists() && !targetLinkDir.mkdirs()) {
                log.warn("Failed to create agent directory for link: ${targetLinkDir.absolutePath}")
                continue
            }
            
            createSymlinkForAgent(skillDir, targetLinkDir, agentName)
        }
    }
    
    private fun createSymlinkForAgent(skillDir: File, targetLinkDir: File, agentName: String) {
        val linkFile = File(targetLinkDir, skillDir.name)
        try {
            if (linkFile.exists()) {
                log.info("Link already exists: ${linkFile.absolutePath}")
                return
            }
            fileService.createSymbolicLink(linkFile, skillDir)
            log.info("Created symlink: ${linkFile.absolutePath} -> ${skillDir.absolutePath}")
        } catch (e: Exception) {
            log.error("Failed to create symlink: ${linkFile.absolutePath}", e)
            notifyError("Symlink Error", "Failed to create symlink for $agentName: ${e.message}")
        }
    }
    
    // ============================================
    // Notification helpers
    // ============================================
    
    private fun notifyInfo(title: String, message: String) {
        AgentSkillsNotifier.notify(project, title, message, NotificationType.INFORMATION)
    }
    
    private fun notifyWarning(title: String, message: String) {
        AgentSkillsNotifier.notify(project, title, message, NotificationType.WARNING)
    }
    
    private fun notifyError(title: String, message: String) {
        AgentSkillsNotifier.notify(project, title, message, NotificationType.ERROR)
    }
}
