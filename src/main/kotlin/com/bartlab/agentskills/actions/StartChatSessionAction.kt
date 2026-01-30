package com.bartlab.agentskills.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.bartlab.agentskills.mcp.SkillChatSessionState
import com.bartlab.agentskills.service.SkillScannerService
import com.bartlab.agentskills.service.SkillPromptXmlService
import com.bartlab.agentskills.settings.SkillSettingsState
import com.bartlab.agentskills.util.AgentSkillsNotifier
import java.awt.datatransfer.StringSelection
import java.util.UUID

class StartChatSessionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val scanner = project.getService(SkillScannerService::class.java) ?: return
        scanner.scan()
        val skills = scanner.getSkills()

        val settings = SkillSettingsState.getInstance(project).state

        val dialog = StartChatSessionDialog(
            project = project,
            skills = skills,
            initialMode = settings.exposureMode,
            initialSelectedNames = settings.selectedSkillNames.toSet()
        )

        if (!dialog.showAndGet()) return

        val sessionKey = UUID.randomUUID().toString()

        val mode = dialog.selectedMode()
        val selected = dialog.selectedSkillNames()

        SkillChatSessionState.getInstance(project).update(
            sessionKey = sessionKey,
            exposureMode = mode,
            selected = selected
        )

        val visibleSkills = if (mode == SkillSettingsState.SkillExposureMode.AUTO_ALL_METADATA) {
            skills
        } else {
            skills.filter { it.name in selected.toSet() }
        }

        val promptService = project.getService(SkillPromptXmlService::class.java)
        val skillsXml = promptService?.buildAvailableSkillsXml(visibleSkills, includeLocation = false) ?: ""

        val preset = buildPreset(sessionKey, skillsXml)
        CopyPasteManager.getInstance().setContents(StringSelection(preset))

        AgentSkillsNotifier.notify(
            project,
            "Agent Skills",
            "Prompt copied to clipboard (session=$sessionKey)",
            NotificationType.INFORMATION
        )
    }

    @Suppress("SpellCheckingInspection")
    private fun buildPreset(
        sessionKey: String,
        skillsXml: String
    ): String {
        val sb = StringBuilder()
        sb.append("Agent Skills enabled (MCP).\n\n")

        if (skillsXml.isNotEmpty()) {
            sb.append("Below is a list of available skills (Agent Skills).\n")
            sb.append("Each skill has a name and a short description.\n\n")
            sb.append(skillsXml.trim())
            sb.append("\n\n")
            sb.append("IMPORTANT: If the user's request matches the description of any skill, you MUST immediately load its full text using the `skills` tool.\n")
        } else {
            sb.append("The skill list is currently empty or no skills are selected.\n")
        }

        sb.append("\nsession: $sessionKey\n\n")

        sb.append("Your MCP tools for skill management:\n")
        sb.append("1) `skills(session, name)` — LOAD FULL SKILL TEXT. Call this when you find a suitable skill in the list above.\n")
        sb.append("2) `skillslist(session)` — Refresh the metadata list (if needed).\n")
        sb.append("3) `skillsset(session, mode, name)` — Change skill visibility settings.\n\n")

        sb.append("Workflow:\n")
        sb.append("- Look at the user's request.\n")
        sb.append("- Look at the <available_skills> list above.\n")
        sb.append("- If you see a suitable skill, call `skills` with its name.\n")
        sb.append("- Use the instructions from the loaded skill to complete the task.\n")
        sb.append("- IMPORTANT: In your final response, explicitly state that you used an Agent Skill from the local MCP server.")

        return sb.toString()
    }
}