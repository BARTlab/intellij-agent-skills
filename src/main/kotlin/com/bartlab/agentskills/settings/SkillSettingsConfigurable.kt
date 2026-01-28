package com.bartlab.agentskills.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.PortField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.bartlab.agentskills.service.SkillScannerService
import com.bartlab.agentskills.mcp.SkillMcpServerService
import com.bartlab.agentskills.ui.AgentSkillsTableComponent
import com.bartlab.agentskills.util.AgentSkillsNotifier
import com.intellij.notification.NotificationType
import java.awt.datatransfer.StringSelection
import javax.swing.*

class SkillSettingsConfigurable(private val project: Project) : Configurable {
    private val log = Logger.getInstance(SkillSettingsConfigurable::class.java)
    private val settings = SkillSettingsState.getInstance(project)

    private var useCustomPathCheckBox: JBCheckBox? = null
    private var customPathField: TextFieldWithBrowseButton? = null

    private var exposureModeCombo: ComboBox<ExposureModeItem>? = null

    private var integrateAiAssistantCheckBox: JBCheckBox? = null
    private var mcpPortField: PortField? = null

    private var skillsTable: AgentSkillsTableComponent? = null

    private var rootComponent: JComponent? = null

    override fun getDisplayName(): String = "Agent Skills"

    override fun createComponent(): JComponent {
        val scanner = project.getService(SkillScannerService::class.java)

        val ai = JBCheckBox("Enable MCP server on port:")
        val port = PortField(settings.state.mcpPort)
        integrateAiAssistantCheckBox = ai
        mcpPortField = port

        val useCustom = JBCheckBox("Skill search directory:")
        val scannerPathsHint = scanner?.getAgentPaths()?.let { paths ->
            "<html>When disabled, the following directories are searched:<br/>" +
                    "<b>Project:</b> " + paths.joinToString(", ") { it.projectPath } + "<br/>" +
                    "<b>Global:</b> " + paths.map { it.globalPath }.distinct().joinToString(", ") + "</html>"
        } ?: "When disabled, default project and global directories are searched."

        val customPath = TextFieldWithBrowseButton()
        useCustomPathCheckBox = useCustom
        customPathField = customPath

        customPath.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Skills Folder")
                .withDescription("Select the directory containing folders with SKILL.md files."),
            object : TextComponentAccessor<JTextField> {
                override fun getText(component: JTextField): String = component.text
                override fun setText(component: JTextField, text: String) {
                    component.text = scanner?.shortenPath(text) ?: text
                }
            }
        )

        val exposure = ComboBox(
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
        exposureModeCombo = exposure

        val skillsTableComponent = AgentSkillsTableComponent(project) {
            val scanner = project.getService(SkillScannerService::class.java)
            scanner?.scan()
            skillsTable?.setData(scanner?.getSkills().orEmpty(), settings.state.selectedSkillNames.toSet())
        }
        this.skillsTable = skillsTableComponent

        ai.addActionListener { applyModeToUi() }
        useCustom.addActionListener { applyModeToUi() }
        exposure.addActionListener { applyModeToUi() }

        val root = panel {
            group("Integration") {
                row {
                    cell(ai)
                    cell(port)
                    button("Copy SSE Config") {
                        val portValue = port.number
                        val config = """
                        {
                          "mcpServers": {
                            "skills": {
                              "url": "http://127.0.0.1:$portValue/sse"
                            }
                          }
                        }
                        """.trimIndent()
                        CopyPasteManager.getInstance().setContents(StringSelection(config))
                        AgentSkillsNotifier.notify(
                            project,
                            "MCP Config",
                            "Configuration copied to clipboard",
                            NotificationType.INFORMATION
                        )
                    }
                }
            }

            group("Search Settings") {
                row {
                    cell(useCustom)
                    cell(customPath).resizableColumn().align(Align.FILL)
                    icon(AllIcons.General.ContextHelp)
                        .applyToComponent {
                            toolTipText = scannerPathsHint
                        }
                }
                row("Exposure Mode:") {
                    cell(exposure).align(Align.FILL)
                }
            }

            row("Discovered Skills (SKILL.md):") {}.topGap(TopGap.MEDIUM)
            row {
                cell(skillsTableComponent.createPanel()).align(Align.FILL)
            }.resizableRow()
        }.apply {
            border = JBUI.Borders.empty(10)
        }

        scanner?.scan()
        skillsTableComponent.setData(scanner?.getSkills().orEmpty(), settings.state.selectedSkillNames.toSet())
        applyModeToUi()

        rootComponent = root
        return root
    }

    override fun reset() {
        val s = settings.state
        val scanner = project.getService(SkillScannerService::class.java)
        useCustomPathCheckBox?.isSelected = s.useCustomPath
        customPathField?.text = scanner?.shortenPath(s.customPath) ?: s.customPath

        exposureModeCombo?.selectedItem = exposureModeCombo
            ?.model
            ?.let { m ->
                (0 until m.size).asSequence()
                    .map { m.getElementAt(it) }
                    .firstOrNull { it.mode == s.exposureMode }
            }

        integrateAiAssistantCheckBox?.isSelected = s.integrateAiAssistant
        mcpPortField?.number = s.mcpPort

        applyModeToUi()
    }

    override fun isModified(): Boolean {
        val s = settings.state
        val scanner = project.getService(SkillScannerService::class.java)

        val useDir = useCustomPathCheckBox?.isSelected ?: s.useCustomPath
        val currentRaw = customPathField?.text ?: s.customPath
        val expandedCustom = scanner?.expandPath(currentRaw) ?: currentRaw
        val sCustomExpanded = scanner?.expandPath(s.customPath) ?: s.customPath

        val mode = (exposureModeCombo?.selectedItem as? ExposureModeItem)?.mode ?: s.exposureMode
        val selectedNow = skillsTable?.getSelectedSkillNames().orEmpty()

        val ai = integrateAiAssistantCheckBox?.isSelected ?: s.integrateAiAssistant
        val port = mcpPortField?.number ?: s.mcpPort

        return useDir != s.useCustomPath ||
            expandedCustom != sCustomExpanded ||
            mode != s.exposureMode ||
            selectedNow != s.selectedSkillNames ||
            ai != s.integrateAiAssistant ||
            port != s.mcpPort
    }

    override fun apply() {
        log.info("Settings: applying changes...")
        val s = settings.state
        val scanner = project.getService(SkillScannerService::class.java)

        s.useCustomPath = useCustomPathCheckBox?.isSelected ?: s.useCustomPath
        val currentRaw = customPathField?.text ?: s.customPath
        s.customPath = scanner?.expandPath(currentRaw) ?: currentRaw

        s.exposureMode = (exposureModeCombo?.selectedItem as? ExposureModeItem)?.mode ?: s.exposureMode
        s.selectedSkillNames = skillsTable?.getSelectedSkillNames()?.toMutableList() ?: s.selectedSkillNames

        s.integrateAiAssistant = integrateAiAssistantCheckBox?.isSelected ?: s.integrateAiAssistant
        s.mcpPort = mcpPortField?.number ?: s.mcpPort

        // Немедленно включаем/выключаем MCP сервер по галочке.
        project.getService(SkillMcpServerService::class.java)?.syncWithSettings()

        log.info("Settings: changes applied. mcpPort=${s.mcpPort}, integrateAiAssistant=${s.integrateAiAssistant}")
    }

    override fun disposeUIResources() {
        rootComponent = null
        useCustomPathCheckBox = null
        customPathField = null
        exposureModeCombo = null
        integrateAiAssistantCheckBox = null
        skillsTable = null
    }

    private fun applyModeToUi() {
        customPathField?.isEnabled = useCustomPathCheckBox?.isSelected == true
        mcpPortField?.isEnabled = integrateAiAssistantCheckBox?.isSelected == true

        val mode = (exposureModeCombo?.selectedItem as? ExposureModeItem)?.mode
            ?: SkillSettingsState.SkillExposureMode.AUTO_ALL_METADATA

        skillsTable?.setCheckboxesEnabled(mode == SkillSettingsState.SkillExposureMode.SELECTED_ONLY_METADATA)
    }

    private data class ExposureModeItem(
        val mode: SkillSettingsState.SkillExposureMode,
        val label: String
    ) {
        override fun toString(): String = label
    }
}