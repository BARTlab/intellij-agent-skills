package com.bartlab.agentskills.model

import com.bartlab.agentskills.util.XmlUtils

/**
 * Agent Skill model.
 *
 * Represents a skill loaded from a SKILL.md file.
 * Used to store metadata and full content of a skill.
 *
 * @property name Unique skill name (per specification: lowercase, hyphens)
 * @property description Brief description of the skill
 * @property version Skill version
 * @property path Absolute path to the SKILL.md file
 * @property fullContent Full content of the skill (without YAML frontmatter)
 * @property metadata Additional metadata from frontmatter
 * @property license Skill license (optional)
 * @property compatibility Compatibility information (optional)
 * @property allowedTools List of allowed tools (optional)
 */
data class AgentSkill(
    val name: String,
    val description: String,
    val version: String,
    val path: String,
    val fullContent: String,
    val metadata: Map<String, String> = emptyMap(),
    val license: String? = null,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList()
) {
    /**
     * Metadata element for system/context (without full text).
     * The format matches the specification:
     *
     * <skill>
     *   <name>...</name>
     *   <description>...</description>
     *   <location>...</location>
     * </skill>
     */
    fun toAvailableSkillXml(includeLocation: Boolean): String {
        val safeName = XmlUtils.escape(name)
        val safeDesc = XmlUtils.escape(description)
        val safeVer = XmlUtils.escape(version)
        val safeLoc = XmlUtils.escape(path)

        return buildString {
            appendLine("<skill>")
            appendLine("  <name>$safeName</name>")
            appendLine("  <description>$safeDesc</description>")
            appendLine("  <version>$safeVer</version>")
            license?.let { appendLine("  <license>${XmlUtils.escape(it)}</license>") }
            compatibility?.let { appendLine("  <compatibility>${XmlUtils.escape(it)}</compatibility>") }
            if (allowedTools.isNotEmpty()) {
                appendLine("  <allowed_tools>${XmlUtils.escape(allowedTools.joinToString(" "))}</allowed_tools>")
            }
            if (includeLocation) {
                appendLine("  <location>$safeLoc</location>")
            }
            append("</skill>")
        }
    }
}