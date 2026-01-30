package com.bartlab.agentskills.service

import com.bartlab.agentskills.model.SkillSource
import com.bartlab.agentskills.model.SkillSourceType
import java.io.File
import java.nio.file.Paths

object SkillSourceParser {
    private val githubTreeWithPathRegex = Regex("github\\.com/([^/]+)/([^/]+)/tree/([^/]+)/(.+)")
    private val githubTreeRegex = Regex("github\\.com/([^/]+)/([^/]+)/tree/([^/]+)$")
    private val githubRepoRegex = Regex("github\\.com/([^/]+)/([^/]+)")
    private val gitlabTreeWithPathRegex = Regex("gitlab\\.com/([^/]+)/([^/]+)/-/tree/([^/]+)/(.+)")
    private val gitlabTreeRegex = Regex("gitlab\\.com/([^/]+)/([^/]+)/-/tree/([^/]+)$")
    private val gitlabRepoRegex = Regex("gitlab\\.com/([^/]+)/([^/]+)")
    private val shorthandRegex = Regex("^([^/]+)/([^/]+)(?:/(.+))?$")
    private val windowsAbsoluteRegex = Regex("^[a-zA-Z]:[/\\\\]")
    private val httpSchemeRegex = Regex("^https?://", RegexOption.IGNORE_CASE)

    fun parse(input: String): SkillSource? {
        val trimmedInput = input.trim()
        
        if (isLocalPath(trimmedInput)) {
            val absolutePath = Paths.get(trimmedInput).toAbsolutePath().toString()
            return SkillSource(
                type = SkillSourceType.LOCAL,
                url = absolutePath,
                localPath = absolutePath
            )
        }
        
        if (isDirectSkillUrl(trimmedInput)) {
            return SkillSource(
                type = SkillSourceType.DIRECT_URL,
                url = trimmedInput
            )
        }
        
        // GitHub
        githubTreeWithPathRegex.find(trimmedInput)?.let { match ->
            val (owner, repo, ref, subpath) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITHUB,
                url = "https://github.com/$owner/${repo.removeSuffix(".git")}.git",
                ref = ref,
                subpath = subpath
            )
        }

        githubTreeRegex.find(trimmedInput)?.let { match ->
            val (owner, repo, ref) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITHUB,
                url = "https://github.com/$owner/${repo.removeSuffix(".git")}.git",
                ref = ref
            )
        }

        githubRepoRegex.find(trimmedInput)?.let { match ->
            val (owner, repo) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITHUB,
                url = "https://github.com/$owner/${repo.removeSuffix(".git")}.git"
            )
        }

        // GitLab
        gitlabTreeWithPathRegex.find(trimmedInput)?.let { match ->
            val (owner, repo, ref, subpath) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITLAB,
                url = "https://gitlab.com/$owner/${repo.removeSuffix(".git")}.git",
                ref = ref,
                subpath = subpath
            )
        }

        gitlabTreeRegex.find(trimmedInput)?.let { match ->
            val (owner, repo, ref) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITLAB,
                url = "https://gitlab.com/$owner/${repo.removeSuffix(".git")}.git",
                ref = ref
            )
        }

        gitlabRepoRegex.find(trimmedInput)?.let { match ->
            val (owner, repo) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITLAB,
                url = "https://gitlab.com/$owner/${repo.removeSuffix(".git")}.git"
            )
        }

        // Shorthand
        if (shorthandRegex.matches(trimmedInput) && !trimmedInput.contains(":") && !trimmedInput.startsWith(".") && !trimmedInput.startsWith("/")) {
            shorthandRegex.find(trimmedInput)?.let { match ->
                val (owner, repo, subpath) = match.destructured
                return SkillSource(
                    type = SkillSourceType.GITHUB,
                    url = "https://github.com/$owner/${repo.removeSuffix(".git")}.git",
                    subpath = subpath.takeIf { it.isNotEmpty() }
                )
            }
        }
        
        return null
    }
    
    private fun isLocalPath(input: String): Boolean {
        if (input.startsWith("/") || input.startsWith("./") || input.startsWith("../") || input == "." || input == "..") return true
        if (windowsAbsoluteRegex.containsMatchIn(input)) return true
        return File(input).isAbsolute
    }
    
    private fun isDirectSkillUrl(input: String): Boolean {
        if (!httpSchemeRegex.containsMatchIn(input)) return false
        if (!input.lowercase().endsWith("/skill.md")) return false
        
        if (input.contains("github.com/") && !input.contains("raw.githubusercontent.com")) {
            if (!input.contains("/blob/") && !input.contains("/raw/")) return false
        }
        
        if (input.contains("gitlab.com/") && !input.contains("/-/raw/")) return false
        
        return true
    }
}
