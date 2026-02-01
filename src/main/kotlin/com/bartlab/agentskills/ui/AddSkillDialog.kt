package com.bartlab.agentskills.ui

import com.bartlab.agentskills.AgentSkillsConstants
import com.bartlab.agentskills.model.AgentPath
import com.bartlab.agentskills.util.TableUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.icons.AllIcons
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import javax.swing.table.AbstractTableModel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.util.ui.JBUI
import com.bartlab.agentskills.service.SkillScannerService
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.TableCellRenderer

class AddSkillDialog(project: Project) : DialogWrapper(project) {
    enum class Mode { INSTALL, INIT }

    private val modeComboBox = ComboBox(Mode.entries.toTypedArray())
    private val sourceField = ExtendableTextField().apply {
        emptyText.text = "vercel-labs/agent-skills or https://github.com/owner/repo"
        addExtension(ExtendableTextComponent.Extension.create(
            AllIcons.General.ContextHelp,
            "Use GitHub URL, owner/repo shorthand, local path, or direct skill.md URL.",
            null
        ))
    }
    private val skillNameField = JBTextField().apply {
        emptyText.text = "my-new-skill"
    }
    private val createSymlinksCheckbox = JBCheckBox("Create symbolic links for agents", true)
    private val globalInstallCheckbox = JBCheckBox("Global installation (to user home directory)", false)
    private val scanner = project.getService(SkillScannerService::class.java)
    private val agentsTableModel = AgentsTableModel(scanner.getAgentPaths())

    init {
        title = "Add/Initialize Agent Skill"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val isInstall = object : ComponentPredicate() {
            override fun invoke(): Boolean = modeComboBox.selectedItem == Mode.INSTALL
            override fun addListener(listener: (Boolean) -> Unit) {
                modeComboBox.addActionListener { listener(invoke()) }
            }
        }
        val isInit = object : ComponentPredicate() {
            override fun invoke(): Boolean = modeComboBox.selectedItem == Mode.INIT
            override fun addListener(listener: (Boolean) -> Unit) {
                modeComboBox.addActionListener { listener(invoke()) }
            }
        }

        return panel {
            row("Action:") {
                cell(modeComboBox).align(Align.FILL)
            }
            row("Skill Source:") {
                cell(sourceField).align(Align.FILL)
            }.visibleIf(isInstall)
            row {
                comment("You can find skills at <a href=\"https://skills.sh/\">https://skills.sh/</a>") {
                    com.intellij.ide.BrowserUtil.browse(it.url)
                }
            }.visibleIf(isInstall)
            
            row("Skill Name:") {
                cell(skillNameField).align(Align.FILL)
            }.visibleIf(isInit)

            row {
                cell(createSymlinksCheckbox)
            }
            row {
                cell(globalInstallCheckbox)
            }.visibleIf(isInstall)
            
            row("Agents:") {}.topGap(com.intellij.ui.dsl.builder.TopGap.MEDIUM)
            row {
                val table = JBTable(agentsTableModel).apply {
                    fillsViewportHeight = true
                    val checkCol = columnModel.getColumn(0)
                    TableUtils.setFixedColumnWidth(checkCol, JBUI.scale(AgentSkillsConstants.CHECKBOX_COLUMN_WIDTH))
                    checkCol.headerRenderer = TableCellRenderer { t, _, _, _, _, _ ->
                        val cb = JBCheckBox("", agentsTableModel.isAllSelected())
                        cb.isOpaque = false
                        val panel = JPanel(BorderLayout())
                        panel.add(cb, BorderLayout.CENTER)
                        panel.background = t.tableHeader.background
                        panel.border = JBUI.Borders.empty(0, 2)
                        panel.accessibleContext?.accessibleName = "Select all agents"
                        panel.accessibleContext?.accessibleDescription = "Toggle selection for all agents"
                        panel
                    }
                    tableHeader.addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            val col = columnAtPoint(e.point)
                            if (col == 0) {
                                agentsTableModel.selectAll(!agentsTableModel.isAllSelected())
                                tableHeader.repaint()
                            }
                        }
                    })
                    columnModel.getColumn(1).preferredWidth = 150
                    columnModel.getColumn(2).preferredWidth = 300
                }
                cell(JBScrollPane(table)).align(Align.FILL)
            }.resizableRow()
        }.apply {
            preferredSize = JBUI.size(500, 600)
        }
    }

    fun getMode(): Mode = modeComboBox.selectedItem as Mode
    fun getRepoUrl(): String = sourceField.text.trim()
    fun getSkillName(): String = skillNameField.text.trim()
    fun getSelectedAgents(): List<String> = agentsTableModel.selectedAgents.toList()
    fun shouldCreateSymlinks(): Boolean = createSymlinksCheckbox.isSelected
    fun isGlobalInstall(): Boolean = globalInstallCheckbox.isSelected

    private class AgentsTableModel(val agents: List<AgentPath>) : AbstractTableModel() {
        val selectedAgents = mutableSetOf<String>()

        fun isAllSelected(): Boolean = agents.isNotEmpty() && selectedAgents.size == agents.size

        fun selectAll(select: Boolean) {
            if (select) {
                selectedAgents.addAll(agents.map { it.name })
            } else {
                selectedAgents.clear()
            }
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = agents.size
        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            0 -> ""
            1 -> "Agent"
            2 -> "Path"
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val agent = agents[rowIndex]
            return when (columnIndex) {
                0 -> selectedAgents.contains(agent.name)
                1 -> agent.name
                2 -> agent.projectPath
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 0 && aValue is Boolean) {
                val agent = agents[rowIndex]
                if (aValue) {
                    selectedAgents.add(agent.name)
                } else {
                    selectedAgents.remove(agent.name)
                }
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }
    }
}
