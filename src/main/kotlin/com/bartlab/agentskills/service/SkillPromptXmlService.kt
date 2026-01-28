package com.bartlab.agentskills.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.bartlab.agentskills.model.AgentSkill

@Service(Service.Level.PROJECT)
class SkillPromptXmlService(
    private val project: Project,
) {
    private val log = Logger.getInstance(SkillPromptXmlService::class.java)

    fun buildAvailableSkillsXml(
        visibleSkills: List<AgentSkill>,
        includeLocation: Boolean = true
    ): String {
        log.info("PromptXml: building XML for ${visibleSkills.size} skills")

        return buildString {
            appendLine("<available_skills>")
            for (skill in visibleSkills.sortedBy { it.name.lowercase() }) {
                val xml = skill.toAvailableSkillXml(includeLocation = includeLocation)
                appendLine("  ${xml.replace("\n", "\n  ")}")
            }
            append("</available_skills>")
        }
    }
}