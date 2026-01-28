package com.bartlab.agentskills.model

import com.bartlab.agentskills.util.XmlUtils

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
     * Элемент метаданных для system/context (без полного текста).
     * Формат соответствует вашей спецификации:
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