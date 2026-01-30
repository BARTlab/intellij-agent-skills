package com.bartlab.agentskills.service

import org.yaml.snakeyaml.Yaml

data class SkillYamlMetadata(
    val name: String?,
    val description: String?,
    val version: String?,
    val license: String?,
    val compatibility: String?,
    val allowedTools: List<String>,
    val metadata: Map<String, String>
)

class SkillYamlParser {
    private val yaml = Yaml()

    fun parseFrontMatter(content: String): SkillYamlMetadata? {
        val block = extractYamlBlock(content) ?: return null
        val data = loadMap(block)

        val allowedTools = when (val rawTools = data["allowed-tools"]) {
            is List<*> -> rawTools.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
            is String -> rawTools.split(Regex("\\s+")).filter { it.isNotBlank() }
            else -> emptyList()
        }

        val metadata = when (val rawMetadata = data["metadata"]) {
            is Map<*, *> -> rawMetadata.mapNotNull { (key, value) ->
                val name = key?.toString()?.trim()
                val rawValue = value?.toString()?.trim()
                if (!name.isNullOrBlank() && rawValue != null) name to rawValue else null
            }.toMap()
            else -> emptyMap()
        }

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