package com.bartlab.agentskills.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.bartlab.agentskills.mcp.SkillMcpServerService
import com.bartlab.agentskills.settings.SkillSettingsState
import com.intellij.openapi.application.ApplicationManager

class SkillScannerStartupActivity : ProjectActivity {
    private val log = Logger.getInstance(SkillScannerStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        log.info("StartupActivity: Starting for project '${project.name}'")
        val scanner = project.getService(SkillScannerService::class.java) ?: return
        val mcpServer = project.getService(SkillMcpServerService::class.java) ?: return

        val enabled = SkillSettingsState.getInstance(project).state.integrateAiAssistant
        log.info("StartupActivity: MCP server integration enabled: $enabled")
        if (enabled) {
            mcpServer.ensureStarted()
        } else {
            mcpServer.stop()
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            scanner.scan()
        }
    }
}