package com.bartlab.agentskills.service

import com.bartlab.agentskills.model.SkillSource
import com.bartlab.agentskills.model.SkillSourceType
import java.io.File
import java.nio.file.Paths

object SkillSourceParser {
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
        val githubTreeWithPath = "github\\.com/([^/]+)/([^/]+)/tree/([^/]+)/(.+)".toRegex()
        githubTreeWithPath.find(trimmedInput)?.let { match ->
            val (owner, repo, ref, subpath) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITHUB,
                url = "https://github.com/$owner/${repo.removeSuffix(".git")}.git",
                ref = ref,
                subpath = subpath
            )
        }
        
        val githubTree = "github\\.com/([^/]+)/([^/]+)/tree/([^/]+)$".toRegex()
        githubTree.find(trimmedInput)?.let { match ->
            val (owner, repo, ref) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITHUB,
                url = "https://github.com/$owner/${repo.removeSuffix(".git")}.git",
                ref = ref
            )
        }
        
        val githubRepo = "github\\.com/([^/]+)/([^/]+)".toRegex()
        githubRepo.find(trimmedInput)?.let { match ->
            val (owner, repo) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITHUB,
                url = "https://github.com/$owner/${repo.removeSuffix(".git")}.git"
            )
        }
        
        // GitLab
        val gitlabTreeWithPath = "gitlab\\.com/([^/]+)/([^/]+)/-/tree/([^/]+)/(.+)".toRegex()
        gitlabTreeWithPath.find(trimmedInput)?.let { match ->
            val (owner, repo, ref, subpath) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITLAB,
                url = "https://gitlab.com/$owner/${repo.removeSuffix(".git")}.git",
                ref = ref,
                subpath = subpath
            )
        }
        
        val gitlabTree = "gitlab\\.com/([^/]+)/([^/]+)/-/tree/([^/]+)$".toRegex()
        gitlabTree.find(trimmedInput)?.let { match ->
            val (owner, repo, ref) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITLAB,
                url = "https://gitlab.com/$owner/${repo.removeSuffix(".git")}.git",
                ref = ref
            )
        }
        
        val gitlabRepo = "gitlab\\.com/([^/]+)/([^/]+)".toRegex()
        gitlabRepo.find(trimmedInput)?.let { match ->
            val (owner, repo) = match.destructured
            return SkillSource(
                type = SkillSourceType.GITLAB,
                url = "https://gitlab.com/$owner/${repo.removeSuffix(".git")}.git"
            )
        }
        
        // Shorthand
        val shorthand = "^([^/]+)/([^/]+)(?:/(.+))?$".toRegex()
        if (shorthand.matches(trimmedInput) && !trimmedInput.contains(":") && !trimmedInput.startsWith(".") && !trimmedInput.startsWith("/")) {
            shorthand.find(trimmedInput)?.let { match ->
                val (owner, repo, subpath) = match.destructured
                return SkillSource(
                    type = SkillSourceType.GITHUB,
                    url = "https://github.com/$owner/${repo.removeSuffix(".git")}.git",
                    subpath = if (subpath.isEmpty()) null else subpath
                )
            }
        }
        
        return null
    }
    
    private fun isLocalPath(input: String): Boolean {
        if (input.startsWith("/") || input.startsWith("./") || input.startsWith("../") || input == "." || input == "..") return true
        if ("^[a-zA-Z]:[/\\\\]".toRegex().containsMatchIn(input)) return true
        return File(input).exists() && File(input).isAbsolute
    }
    
    private fun isDirectSkillUrl(input: String): Boolean {
        if (!input.startsWith("http://") && !input.startsWith("https://")) return false
        if (!input.lowercase().endsWith("/skill.md")) return false
        
        if (input.contains("github.com/") && !input.contains("raw.githubusercontent.com")) {
            if (!input.contains("/blob/") && !input.contains("/raw/")) return false
        }
        
        if (input.contains("gitlab.com/") && !input.contains("/-/raw/")) return false
        
        return true
    }
}
