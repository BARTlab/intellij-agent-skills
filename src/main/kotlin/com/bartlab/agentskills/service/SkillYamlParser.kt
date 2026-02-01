package com.bartlab.agentskills.service

import org.yaml.snakeyaml.Yaml

/**
 * Skill metadata extracted from YAML frontmatter.
 *
 * @property name Skill name
 * @property description Skill description
 * @property version Skill version
 * @property license License
 * @property compatibility Compatibility
 * @property allowedTools Allowed tools
 * @property metadata Additional metadata
 */
data class SkillYamlMetadata(
    val name: String?,
    val description: String?,
    val version: String?,
    val license: String?,
    val compatibility: String?,
    val allowedTools: List<String>,
    val metadata: Map<String, String>
)

/**
 * YAML frontmatter parser for SKILL.md files.
 *
 * Extracts metadata from the block between `---` markers at the beginning of the file.
 */
class SkillYamlParser {
    private val yaml = Yaml()

    /**
     * Parses YAML frontmatter from SKILL.md content.
     *
     * @param content Full content of the SKILL.md file
     * @return Skill metadata or null if frontmatter is missing
     */
    fun parseFrontMatter(content: String): SkillYamlMetadata? {
        val block = extractYamlBlock(content) ?: return null
        val data = loadMap(block)

        val allowedTools = parseAllowedTools(data["allowed-tools"])
        val metadata = parseMetadata(data["metadata"])

        return SkillYamlMetadata(
            name = data["name"]?.toString()?.trim(),
            description = data["description"]?.toString()?.trim(),
            version = data["version"]?.toString()?.trim() ?: metadata["version"],
            license = data["license"]?.toString()?.trim(),
            compatibility = data["compatibility"]?.toString()?.trim(),
            allowedTools = allowedTools,
            metadata = metadata
        )
    }
    
    private fun parseAllowedTools(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
        is String -> raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        else -> emptyList()
    }
    
    private fun parseMetadata(raw: Any?): Map<String, String> = when (raw) {
        is Map<*, *> -> raw.mapNotNull { (key, value) ->
            val name = key?.toString()?.trim()
            val rawValue = value?.toString()?.trim()
            if (!name.isNullOrBlank() && rawValue != null) name to rawValue else null
        }.toMap()
        else -> emptyMap()
    }

    /**
     * Removes YAML frontmatter from content, leaving only the document body.
     *
     * @param content Full content of the SKILL.md file
     * @return Content without frontmatter
     */
    fun stripFrontMatter(content: String): String {
        val startIndex = content.indexOf("---")
        if (startIndex == -1) return content.trim()
        val endIndex = content.indexOf("---", startIndex + 3)
        if (endIndex == -1) return content.trim()
        return content.substring(endIndex + 3).trim()
    }

    private fun extractYamlBlock(content: String): String? {
        val startIndex = content.indexOf("---")
        if (startIndex == -1) return null
        val endIndex = content.indexOf("---", startIndex + 3)
        if (endIndex == -1) return null
        return content.substring(startIndex + 3, endIndex).trim()
    }

    private fun loadMap(block: String): Map<String, Any?> {
        val loaded = yaml.load<Any?>(block) ?: return emptyMap()
        val rawMap = loaded as? Map<*, *> ?: return emptyMap()
        return rawMap.entries
            .mapNotNull { (key, value) ->
                val name = key?.toString()?.trim()
                if (name.isNullOrBlank()) null else name to value
            }
            .toMap()
    }
}