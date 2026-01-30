package com.bartlab.agentskills.service

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.bartlab.agentskills.model.AgentSkill
import com.bartlab.agentskills.model.SkillSource
import com.bartlab.agentskills.model.SkillSourceType
import com.bartlab.agentskills.settings.SkillSettingsState
import com.bartlab.agentskills.util.AgentSkillsNotifier
import com.bartlab.agentskills.util.PathResolver
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import com.google.gson.GsonBuilder

@Service(Service.Level.PROJECT)
class SkillManagerService(private val project: Project) {
    private val log = Logger.getInstance(SkillManagerService::class.java)
    private val fileService = project.getService(SkillFileService::class.java)
    private val yamlParser = SkillYamlParser()

    fun initSkill(name: String, selectedAgents: List<String>, createSymlinks: Boolean, global: Boolean = false): Boolean {
        val basePath = project.basePath ?: return false
        val settings = SkillSettingsState.getInstance(project).state
        val targetBaseDir = if (global) {
            File(System.getProperty("user.home"), ".agent-skills/skills")
        } else if (settings.useCustomPath) {
            File(PathResolver.expandPath(settings.customPath))
        } else {
            File(basePath, ".agents/skills")
        }

        if (!fileService.ensureDirectory(targetBaseDir)) {
            AgentSkillsNotifier.notify(project, "Init Skill", "Failed to create directory: ${targetBaseDir.absolutePath}", NotificationType.ERROR)
            return false
        }

        val skillDir = File(targetBaseDir, name)
        if (skillDir.exists()) {
            AgentSkillsNotifier.notify(project, "Init Skill", "Skill directory already exists: ${skillDir.absolutePath}", NotificationType.WARNING)
            return false
        }

        if (!fileService.ensureDirectory(skillDir)) {
            AgentSkillsNotifier.notify(project, "Init Skill", "Failed to create skill directory: ${skillDir.absolutePath}", NotificationType.ERROR)
            return false
        }

        val skillMd = File(skillDir, "SKILL.md")
        val template = """
            ---
            name: $name
            description: Description for $name
            metadata:
              version: 1.0.0
            ---
            
            # $name
            
            Welcome to your new agent skill!
        """.trimIndent()
        
        if (!fileService.writeText(skillMd, template)) {
            AgentSkillsNotifier.notify(project, "Init Skill", "Failed to create SKILL.md", NotificationType.ERROR)
            return false
        }

        if (createSymlinks) {
            linkSkill(skillDir, selectedAgents, global)
        } else {
            copySkillToAgents(skillDir, selectedAgents, global)
        }
        
        AgentSkillsNotifier.notify(project, "Init Skill", "Successfully initialized $name", NotificationType.INFORMATION)
        return true
    }

    fun addSkill(sourceInput: String, selectedAgents: List<String>, createSymlinks: Boolean, global: Boolean = false): Boolean {
        val source = SkillSourceParser.parse(sourceInput) ?: run {
            AgentSkillsNotifier.notify(project, "Add Skill", "Invalid skill source: $sourceInput", NotificationType.ERROR)
            return false
        }
        
        var tempDir: File? = null
        return try {
            val skillsDir: File = when (source.type) {
                SkillSourceType.LOCAL -> File(source.localPath!!)
                SkillSourceType.DIRECT_URL -> {
                    tempDir = fileService.createTempDir("agent-skill-direct")
                    val skillFile = File(tempDir, "SKILL.md")
                    if (!fileService.downloadToFile(URI.create(source.url), skillFile)) {
                        AgentSkillsNotifier.notify(project, "Add Skill", "Failed to download ${source.url}", NotificationType.ERROR)
                        return false
                    }
                    tempDir
                }
                SkillSourceType.GITHUB, SkillSourceType.GITLAB -> {
                    tempDir = fileService.createTempDir("agent-skill-clone")
                    val gitArgs = mutableListOf("clone", "--depth", "1")
                    if (source.ref != null) {
                        gitArgs.add("-b")
                        gitArgs.add(source.ref)
                    }
                    gitArgs.add(source.url)
                    gitArgs.add(".")
                    
                    val success = runGitCommand(tempDir, *gitArgs.toTypedArray())
                    if (!success) {
                        AgentSkillsNotifier.notify(project, "Add Skill", "Failed to clone ${source.url}", NotificationType.ERROR)
                        return false
                    }
                    if (source.subpath != null) File(tempDir, source.subpath) else tempDir
                }
            }

            val discoveredSkills = discoverSkills(skillsDir)
            if (discoveredSkills.isEmpty()) {
                AgentSkillsNotifier.notify(project, "Add Skill", "No valid skills found in $sourceInput", NotificationType.ERROR)
                return false
            }

            for (skillInfo in discoveredSkills) {
                installDiscoveredSkill(skillInfo, source, selectedAgents, createSymlinks, global, tempDir)
            }
            
            AgentSkillsNotifier.notify(project, "Add Skill", "Successfully added ${discoveredSkills.size} skill(s)", NotificationType.INFORMATION)
            true
        } catch (e: Exception) {
            log.error("Error adding skill", e)
            AgentSkillsNotifier.notify(project, "Add Skill", "Error: ${e.message}", NotificationType.ERROR)
            false
        } finally {
            tempDir?.let { fileService.deleteRecursively(it) }
        }
    }

    private data class DiscoveredSkill(val name: String, val dir: File, val skillMd: File)

    private fun discoverSkills(baseDir: File): List<DiscoveredSkill> {
        val skills = mutableListOf<DiscoveredSkill>()
        
        // Check if the dir itself is a skill
        val directSkillMd = File(baseDir, "SKILL.md")
        if (directSkillMd.exists()) {
            parseSkillName(directSkillMd)?.let { name ->
                skills.add(DiscoveredSkill(name, baseDir, directSkillMd))
                return skills // If root is a skill, we usually don't look deeper in CLI
            }
        }

        // Priority directories
        val priorityDirs = listOf(
            "skills", "skills/.curated", "skills/.experimental", "skills/.system",
            ".agent/skills", ".agents/skills", ".claude/skills", ".cline/skills",
            ".cursor/skills", ".github/skills", ".roo/skills"
        )

        for (relDir in priorityDirs) {
            val dir = File(baseDir, relDir)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { subDir ->
                    if (subDir.isDirectory) {
                        val skillMd = File(subDir, "SKILL.md")
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
        }

        // Deep search if none found
        if (skills.isEmpty()) {
            fileService.findSkillFiles(baseDir, 5)
                .forEach { skillMd ->
                    parseSkillName(skillMd)?.let { name ->
                        if (skills.none { it.name == name }) {
                            skills.add(DiscoveredSkill(name, skillMd.parentFile, skillMd))
                        }
                    }
                }
        }

        return skills
    }

    private fun parseSkillName(skillMd: File): String? {
        val content = fileService.readText(skillMd) ?: return null
        return yamlParser.parseFrontMatter(content)?.name?.trim()
    }

    private fun installDiscoveredSkill(
        skillInfo: DiscoveredSkill,
        source: SkillSource,
        selectedAgents: List<String>,
        createSymlinks: Boolean,
        global: Boolean,
        baseSourceDir: File? = null
    ) {
        val settings = SkillSettingsState.getInstance(project).state
        val basePath = project.basePath ?: return
        val sanitizedName = skillInfo.name.lowercase().replace("[^a-z0-9]".toRegex(), "-").replace("-+".toRegex(), "-").trim('-')
        
        val masterDir = if (global) {
            File(System.getProperty("user.home"), ".agent-skills/skills")
        } else if (settings.useCustomPath) {
            File(PathResolver.expandPath(settings.customPath))
        } else {
            File(basePath, ".agents/skills")
        }
        val targetSkillDir = File(masterDir, sanitizedName)
        
        copyFiltered(skillInfo.dir, targetSkillDir)
        
        // Save source info
        saveSourceInfo(targetSkillDir, source, skillInfo, baseSourceDir)

        if (createSymlinks) {
            linkSkill(targetSkillDir, selectedAgents, global)
        } else {
            copySkillToAgents(targetSkillDir, selectedAgents, global)
        }
    }

    private fun saveSourceInfo(skillDir: File, source: SkillSource, skillInfo: DiscoveredSkill, baseSourceDir: File? = null) {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val actualSubpath = baseSourceDir?.let { 
            try {
                skillInfo.dir.relativeTo(it).path.replace('\\', '/').takeIf { p -> p.isNotEmpty() && p != "." }
            } catch (e: Exception) {
                null
            }
        } ?: source.subpath

        val sourceInfo = mutableMapOf<String, Any?>(
            "type" to source.type.name.lowercase(),
            "url" to source.url,
            "ref" to source.ref,
            "subpath" to actualSubpath,
            "skillName" to skillInfo.name,
            "installedAt" to java.time.Instant.now().toString()
        )
        fileService.writeText(File(skillDir, "skill-source.json"), gson.toJson(sourceInfo))
    }

    private fun copyFiltered(src: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        src.listFiles()?.forEach { file ->
            if (isJunkFile(file)) return@forEach
            val target = File(targetDir, file.name)
            if (file.isDirectory) {
                copyFiltered(file, target)
            } else {
                file.copyTo(target, overwrite = true)
            }
        }
    }

    private fun isJunkFile(file: File): Boolean {
        val name = file.name
        val lowerName = name.lowercase()

        if (name == "README.md" || name == "metadata.json") return true
        if (name.startsWith("_") && name != "__NOT_A_JUNK__") return true
        
        // Additional junk files
        return lowerName.startsWith(".") || 
               lowerName.contains("license") || 
               lowerName == "package.json" || 
               lowerName == "package-lock.json" || 
               lowerName == "yarn.lock" ||
               lowerName == "skill-source.json"
    }

    private fun copySkillToAgents(skillDir: File, agents: List<String>, global: Boolean) {
        val scanner = project.getService(SkillScannerService::class.java)
        val allAgentPaths = scanner.getAgentPaths()
        
        for (agentName in agents) {
            val agentPath = allAgentPaths.find { it.name == agentName } ?: continue
            val targetDir = File(PathResolver.expandPath(if (global) agentPath.globalPath else agentPath.projectPath))
            
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                log.warn("Failed to create agent directory for copy: ${targetDir.absolutePath}")
                continue
            }
            
            val destSkillDir = File(targetDir, skillDir.name)
            skillDir.copyRecursively(destSkillDir, overwrite = true)
            log.info("Copied skill to agent: ${destSkillDir.absolutePath}")
        }
    }

    fun updateSkill(skill: AgentSkill): Boolean {
        val skillMdFile = File(skill.path)
        val skillDir = skillMdFile.parentFile ?: return false
        val sourceFile = File(skillDir, "skill-source.json")
        
        if (!sourceFile.exists()) {
            // Fallback to `git pull` if there is no source info, but it is a Git repo.
            if (File(skillDir, ".git").exists()) {
                val success = runGitCommand(skillDir, "pull")
                if (success) {
                    AgentSkillsNotifier.notify(project, "Update Skill", "Successfully updated ${skill.name}", NotificationType.INFORMATION)
                } else {
                    AgentSkillsNotifier.notify(project, "Update Skill", "Failed to update ${skill.name}", NotificationType.ERROR)
                }
                return success
            }
            AgentSkillsNotifier.notify(project, "Update Skill", "No source information for ${skill.name}", NotificationType.WARNING)
            return false
        }

        return try {
            val gson = Gson()
            val typeToken = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
            val sourceInfo: Map<String, Any?> = gson.fromJson(sourceFile.readText(), typeToken)
            val type = sourceInfo["type"] as String
            val url = sourceInfo["url"] as String
            val ref = sourceInfo["ref"] as? String
            val subpath = sourceInfo["subpath"] as? String

            when (type) {
                "github", "gitlab" -> {
                    if (File(skillDir, ".git").exists()) {
                        runGitCommand(skillDir, "pull")
                    } else {
                        // If it was installed without `.git` (for example, copied from a temp dir), re-clone it.
                        updateFromRemoteRepo(skillDir, url, ref, subpath)
                    }
                }
                "direct_url" -> {
                    URI.create(url).toURL().openStream().use { input ->
                        skillMdFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                "local" -> {
                    val localDir = File(url)
                    if (localDir.exists()) {
                        copyFiltered(localDir, skillDir)
                    }
                }
            }
            
            // Update timestamp.
            val mutableInfo = sourceInfo.toMutableMap()
            mutableInfo["updatedAt"] = java.time.Instant.now().toString()
            fileService.writeText(sourceFile, GsonBuilder().setPrettyPrinting().create().toJson(mutableInfo))

            AgentSkillsNotifier.notify(project, "Update Skill", "Successfully updated ${skill.name}", NotificationType.INFORMATION)
            true
        } catch (e: Exception) {
            log.error("Error updating skill", e)
            AgentSkillsNotifier.notify(project, "Update Skill", "Error updating ${skill.name}: ${e.message}", NotificationType.ERROR)
            false
        }
    }

    private fun updateFromRemoteRepo(skillDir: File, url: String, ref: String?, subpath: String?): Boolean {
        val tempDir = fileService.createTempDir("agent-skill-update")
        return try {
            val gitArgs = mutableListOf("clone", "--depth", "1", "--no-tags")
            if (!ref.isNullOrEmpty()) {
                gitArgs.add("-b")
                gitArgs.add(ref)
            }
            gitArgs.add(url)
            gitArgs.add(".")
            
            if (runGitCommand(tempDir, *gitArgs.toTypedArray())) {
                val sourceDir = if (!subpath.isNullOrEmpty()) {
                    val sDir = File(tempDir, subpath)
                    if (sDir.exists()) sDir else tempDir
                } else {
                    tempDir
                }
                
                // Clear old files but keep `skill-source.json`.
                skillDir.listFiles()?.forEach { 
                    if (it.name != "skill-source.json") {
                        it.deleteRecursively()
                    }
                }
                
                copyFiltered(sourceDir, skillDir)
                true
            } else false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun deleteSkill(skill: AgentSkill): Boolean {
        val skillFile = File(skill.path)
        val skillDir = skillFile.parentFile ?: return false
        
        // Remove symlinks in agent directories.
        removeLinks(skillDir)
        
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            AgentSkillsNotifier.notify(project, "Delete Skill", "Successfully deleted ${skill.name}", NotificationType.INFORMATION)
        } else {
            AgentSkillsNotifier.notify(project, "Delete Skill", "Failed to delete ${skill.name}", NotificationType.ERROR)
        }
        return deleted
    }

    private fun removeLinks(skillDir: File) {
        val scanner = project.getService(SkillScannerService::class.java)
        val allAgentPaths = scanner.getAgentPaths()
        val userHome = System.getProperty("user.home")
        val basePath = project.basePath

        val targetDirs = mutableSetOf<File>()
        
        // Collect all possible agent directories.
        for (ap in allAgentPaths) {
            if (basePath != null) {
                targetDirs.add(File(basePath, ap.projectPath))
            }
            if (ap.globalPath.startsWith("~")) {
                val path = ap.globalPath.replaceFirst("~", userHome)
                targetDirs.add(File(path))
            } else {
                targetDirs.add(File(ap.globalPath))
            }
        }

        // Also, check the custom path if it is set.
        val settings = SkillSettingsState.getInstance(project).state
        if (settings.useCustomPath && settings.customPath.isNotBlank()) {
            targetDirs.add(File(PathResolver.expandPath(settings.customPath)))
        }

        for (dir in targetDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            
            val linkFile = File(dir, skillDir.name)
            if (linkFile.exists()) {
                try {
                    // Only delete it if it is a symlink or exactly the expected directory.
                    // (Usually these are symlinks.)
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
        }
    }

    private fun linkSkill(skillDir: File, agents: List<String>, global: Boolean) {
        val scanner = project.getService(SkillScannerService::class.java)
        val allAgentPaths = scanner.getAgentPaths()
        
        for (agentName in agents) {
            val agentPath = allAgentPaths.find { it.name == agentName } ?: continue
            val targetLinkDir = File(PathResolver.expandPath(if (global) agentPath.globalPath else agentPath.projectPath))
            
            if (!targetLinkDir.exists()) {
                if (!targetLinkDir.mkdirs()) {
                    log.warn("Failed to create agent directory for link: ${targetLinkDir.absolutePath}")
                    continue
                }
            }
            
            val linkFile = File(targetLinkDir, skillDir.name)
            try {
                if (linkFile.exists()) {
                    log.info("Link already exists: ${linkFile.absolutePath}")
                    continue
                }
                fileService.createSymbolicLink(linkFile, skillDir)
                log.info("Created symlink: ${linkFile.absolutePath} -> ${skillDir.absolutePath}")
            } catch (e: Exception) {
                log.error("Failed to create symlink: ${linkFile.absolutePath}", e)
                AgentSkillsNotifier.notify(project, "Symlink Error", "Failed to create symlink for $agentName: ${e.message}", NotificationType.ERROR)
            }
        }
    }

    private fun runGitCommand(workingDir: File, vararg args: String): Boolean {
        return try {
            val process = ProcessBuilder("git", *args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(1, TimeUnit.MINUTES)
            
            if (exited && process.exitValue() == 0) {
                log.info("Git command success: git ${args.joinToString(" ")}\nOutput: $output")
                true
            } else {
                log.error("Git command failed: git ${args.joinToString(" ")}\nExit code: ${if (exited) process.exitValue() else "timeout"}\nOutput: $output")
                false
            }
        } catch (e: Exception) {
            log.error("Error running git command", e)
            false
        }
    }

    
}
