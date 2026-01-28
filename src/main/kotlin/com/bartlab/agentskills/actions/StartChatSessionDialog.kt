package com.bartlab.agentskills.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.bartlab.agentskills.model.AgentSkill
import com.bartlab.agentskills.service.SkillScannerService
import com.bartlab.agentskills.settings.SkillSettingsState
import com.bartlab.agentskills.ui.AgentSkillsTableComponent
import javax.swing.JComponent
import javax.swing.JPanel

class StartChatSessionDialog(
    project: Project,
    private val skills: List<AgentSkill>,
    initialMode: SkillSettingsState.SkillExposureMode,
    private val initialSelectedNames: Set<String>,
    private val scanner: SkillScannerService? = null
) : DialogWrapper(project) {

    private val modeCombo = ComboBox(
        arrayOf(
            ExposureModeItem(
                SkillSettingsState.SkillExposureMode.AUTO_ALL_METADATA,
                "Auto, metadata for all skills"
            ),
            ExposureModeItem(
                SkillSettingsState.SkillExposureMode.SELECTED_ONLY_METADATA,
                "Metadata for selected skills only"
            ),
        )
    )

    private val skillsTable: AgentSkillsTableComponent = AgentSkillsTableComponent(project) {
        val scanner = project.getService(SkillScannerService::class.java)
        scanner.scan()
        val updatedSkills = scanner.getSkills()
        skillsTable.setData(updatedSkills, skillsTable.getSelectedSkillNames().toSet())
    }

    init {
        title = "Start Agent Skills Chat Session"
        setOKButtonText("Copy Prompt")
        setCancelButtonText("Close")
        
        skillsTable.setData(skills, initialSelectedNames)
        skillsTable.setCheckboxesEnabled(initialMode == SkillSettingsState.SkillExposureMode.SELECTED_ONLY_METADATA)

        modeCombo.selectedItem = modeCombo.model.let { m ->
            (0 until m.size).asSequence()
                .map { m.getElementAt(it) }
                .firstOrNull { it.mode == initialMode }
        }

        modeCombo.addActionListener {
            skillsTable.setCheckboxesEnabled(
                selectedMode() == SkillSettingsState.SkillExposureMode.SELECTED_ONLY_METADATA
            )
        }
        
        init()
    }

    fun selectedMode(): SkillSettingsState.SkillExposureMode =
        (modeCombo.selectedItem as? ExposureModeItem)?.mode
            ?: SkillSettingsState.SkillExposureMode.AUTO_ALL_METADATA

    fun selectedSkillNames(): List<String> = skillsTable.getSelectedSkillNames()

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Mode:") {
                cell(modeCombo).align(Align.FILL)
            }
            row {
                cell(skillsTable.createPanel()).align(Align.FILL)
            }.resizableRow()
        }.apply {
            preferredSize = JBUI.size(900, 500)
            border = JBUI.Borders.empty(10)
        }
    }

    private data class ExposureModeItem(
        val mode: SkillSettingsState.SkillExposureMode,
        val label: String
    ) {
        override fun toString(): String = label
    }
}