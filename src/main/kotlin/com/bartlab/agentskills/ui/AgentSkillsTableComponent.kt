package com.bartlab.agentskills.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.ui.LayeredIcon
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.bartlab.agentskills.model.AgentSkill
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.bartlab.agentskills.service.SkillManagerService
import com.bartlab.agentskills.service.SkillScannerService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import java.awt.Point
import javax.swing.JLabel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Icon
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

class AgentSkillsTableComponent(
    private val project: Project,
    private val onRefresh: () -> Unit
) {
    private val scanner = project.getService(SkillScannerService::class.java)
    private val manager = project.getService(SkillManagerService::class.java)
    private val model = SkillsTableModel(scanner)
    private val loadingPanel = JBLoadingPanel(BorderLayout(), project)
    private val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            val col = columnAtPoint(event.point)
            if (row < 0 || col < 0) return null

            val r = (model as SkillsTableModel).getRow(row) ?: return null
            val raw = when (col) {
                0 -> "Selection affects visibility in 'Selected Only' mode"
                1 -> r.name
                2 -> r.version
                3 -> r.description
                4 -> r.path
                else -> return null
            }
            if (raw.isBlank()) return null
            return toHtmlTooltip(raw, maxCharsPerLine = 80)
        }
    }.apply {
        fillsViewportHeight = true
        preferredScrollableViewportSize = JBUI.size(-1, 200)
        rowHeight = JBUI.scale(24)
        autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        setShowGrid(false)
        tableHeader.reorderingAllowed = false

        // "overflow:hide" for cells on hover (prevent cell expansion)
        setExpandableItemsEnabled(false)
        
        // Настройка ширины колонки чекбоксов
        val checkCol = columnModel.getColumn(0)
        checkCol.preferredWidth = JBUI.scale(40)
        checkCol.maxWidth = JBUI.scale(40)
        checkCol.minWidth = JBUI.scale(40)
        checkCol.headerRenderer = TableCellRenderer { t, _, _, _, _, _ ->
            val cb = JBCheckBox("", this@AgentSkillsTableComponent.model.isAllSelected())
            cb.isOpaque = false
            cb.isEnabled = this@AgentSkillsTableComponent.model.isCheckboxesEnabled()
            val panel = JPanel(BorderLayout())
            panel.add(cb, BorderLayout.CENTER)
            panel.background = t.tableHeader.background
            panel.border = JBUI.Borders.empty(0, 2)
            panel
        }
        tableHeader.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (!this@AgentSkillsTableComponent.model.isCheckboxesEnabled()) return
                val col = columnAtPoint(e.point)
                if (col == 0) {
                    this@AgentSkillsTableComponent.model.selectAll(!this@AgentSkillsTableComponent.model.isAllSelected())
                    tableHeader.repaint()
                }
            }
        })

        // Version column - make it narrower and add loader
        val versionCol = columnModel.getColumn(2)
        versionCol.preferredWidth = JBUI.scale(80)
        versionCol.maxWidth = JBUI.scale(120)
        versionCol.cellRenderer = TableCellRenderer { t, value, isSelected, hasFocus, row, column ->
            val panel = JPanel(BorderLayout(JBUI.scale(4), 0))
            panel.background = if (isSelected) t.selectionBackground else t.background
            val label = JLabel(value?.toString() ?: "")
            label.foreground = if (isSelected) t.selectionForeground else t.foreground
            panel.add(label, BorderLayout.CENTER)
            
            val modelIndex = t.convertRowIndexToModel(row)
            val skillName = this@AgentSkillsTableComponent.model.getRow(modelIndex)?.name ?: ""
            if (this@AgentSkillsTableComponent.model.isUpdating(skillName)) {
                panel.add(JLabel(AnimatedIcon.Default.INSTANCE), BorderLayout.EAST)
            }
            panel.border = JBUI.Borders.empty(0, 4)
            panel
        }

        // Рендерер для чекбоксов, чтобы они были disabled, а не просто readonly
        val booleanRenderer = getDefaultRenderer(java.lang.Boolean::class.java)
        checkCol.cellRenderer = TableCellRenderer { t, value, isSelected, hasFocus, row, column ->
            val c = booleanRenderer.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column)
            if (c is JComponent) {
                c.isEnabled = this@AgentSkillsTableComponent.model.isCheckboxesEnabled()
            }
            c
        }
        
        // Ограничение ширины остальных колонок и использование рендерера для overflow
        val defaultRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: javax.swing.JTable?, value: Any?,
                isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (c is javax.swing.JLabel) {
                    c.toolTipText = value?.toString()
                }
                return c
            }
        }
        
        for (i in 1 until columnCount) {
            columnModel.getColumn(i).cellRenderer = defaultRenderer
        }
    }

    private val decorator = ToolbarDecorator.createDecorator(table)
        .setToolbarPosition(ActionToolbarPosition.TOP)
        .setAddAction {
            val dialog = AddSkillDialog(project)
            if (dialog.showAndGet()) {
                val mode = dialog.getMode()
                if (mode == AddSkillDialog.Mode.INSTALL) {
                    loadingPanel.setLoadingText("Installing skill from ${dialog.getRepoUrl()}...")
                    loadingPanel.startLoading()
                    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Adding skill from ${dialog.getRepoUrl()}") {
                        override fun run(indicator: ProgressIndicator) {
                            manager.addSkill(dialog.getRepoUrl(), dialog.getSelectedAgents(), dialog.shouldCreateSymlinks(), dialog.isGlobalInstall())
                        }
                        override fun onFinished() {
                            loadingPanel.stopLoading()
                            onRefresh()
                        }
                    })
                } else {
                    loadingPanel.setLoadingText("Initializing skill ${dialog.getSkillName()}...")
                    loadingPanel.startLoading()
                    try {
                        manager.initSkill(dialog.getSkillName(), dialog.getSelectedAgents(), dialog.shouldCreateSymlinks(), dialog.isGlobalInstall())
                    } finally {
                        loadingPanel.stopLoading()
                        onRefresh()
                    }
                }
            }
        }
        .setRemoveAction { _ ->
            val selectedRows = table.selectedRows
            if (selectedRows.isNotEmpty()) {
                val skillRows = selectedRows.map { table.convertRowIndexToModel(it) }
                    .mapNotNull { model.getRow(it) }
                val allSkills = scanner.getSkills()
                val skillsToDelete = skillRows.mapNotNull { skillRow -> allSkills.find { it.name == skillRow.name } }
                
                if (skillsToDelete.isNotEmpty()) {
                    val namesString = if (skillsToDelete.size == 1) "'${skillsToDelete[0].name}'" else "${skillsToDelete.size} skills"
                    val title = if (skillsToDelete.size == 1) "Delete Skill" else "Delete Skills"
                    
                    if (Messages.showYesNoDialog(project, "Delete $namesString?", title, Messages.getQuestionIcon()) == Messages.YES) {
                        loadingPanel.setLoadingText("Deleting $namesString...")
                        loadingPanel.startLoading()
                        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Deleting $namesString") {
                            override fun run(indicator: ProgressIndicator) {
                                for (skill in skillsToDelete) {
                                    indicator.text = "Deleting ${skill.name}..."
                                    manager.deleteSkill(skill)
                                }
                            }
                            override fun onFinished() {
                                loadingPanel.stopLoading()
                                onRefresh()
                            }
                        })
                    }
                }
            }
        }
        .setMoveUpAction(null)
        .setMoveDownAction(null)
        .addExtraAction(object : DefaultActionGroup("Update", true), CustomComponentAction {
            init {
                templatePresentation.icon = AllIcons.Actions.Refresh
                templatePresentation.description = "Update skills"
                add(object : AnAction("Selected") {
                    override fun actionPerformed(e: AnActionEvent) = updateSelected()
                    override fun update(ev: AnActionEvent) {
                        ev.presentation.isEnabled = table.selectedRow >= 0
                    }
                })
                add(object : AnAction("All") {
                    override fun actionPerformed(e: AnActionEvent) = updateAll()
                })
            }

            override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
                return ActionButton(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
            }

            override fun actionPerformed(e: AnActionEvent) {
                val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                    null, this, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true
                )
                val component = e.inputEvent?.component ?: return
                popup.show(RelativePoint(component, Point(0, component.height)))
            }
        })
        .addExtraAction(object : AnAction("Refresh Skills", "Scan for skills again", AllIcons.Javaee.UpdateRunningApplication) {
            override fun actionPerformed(e: AnActionEvent) {
                onRefresh()
            }
        })
        .addExtraAction(object : AnAction("Edit Skill", "Open SKILL.md for editing", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                val row = table.selectedRow
                if (row >= 0) {
                    val skillRow = model.getRow(table.convertRowIndexToModel(row)) ?: return
                    val expandedPath = scanner?.expandPath(skillRow.path) ?: skillRow.path
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(expandedPath)
                    if (file != null) {
                        FileEditorManager.getInstance(project).openFile(file, true)
                    } else {
                        Messages.showErrorDialog(project, "Could not find file: $expandedPath", "Error Opening Skill")
                    }
                }
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = table.selectedRow >= 0
            }
        })

    private fun updateSelected() {
        val row = table.selectedRow
        if (row >= 0) {
            val skillRow = model.getRow(table.convertRowIndexToModel(row)) ?: return
            val skill = scanner.getSkills().find { it.name == skillRow.name } ?: return

            model.setUpdating(skill.name, true)
            loadingPanel.setLoadingText("Updating skill ${skill.name}...")
            loadingPanel.startLoading()
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Updating skill ${skill.name}") {
                override fun run(indicator: ProgressIndicator) {
                    manager.updateSkill(skill)
                }
                override fun onFinished() {
                    model.setUpdating(skill.name, false)
                    loadingPanel.stopLoading()
                    onRefresh()
                }
            })
        }
    }

    private fun updateAll() {
        val skills = scanner.getSkills()
        if (skills.isEmpty()) return

        skills.forEach { model.setUpdating(it.name, true) }
        loadingPanel.setLoadingText("Updating all skills...")
        loadingPanel.startLoading()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Updating all skills") {
            override fun run(indicator: ProgressIndicator) {
                for (skill in skills) {
                    indicator.text = "Updating ${skill.name}..."
                    manager.updateSkill(skill)
                }
            }
            override fun onFinished() {
                skills.forEach { model.setUpdating(it.name, false) }
                loadingPanel.stopLoading()
                onRefresh()
            }
        })
    }

    fun createPanel(): JComponent {
        loadingPanel.add(decorator.createPanel(), BorderLayout.CENTER)
        return loadingPanel
    }

    fun setData(skills: List<AgentSkill>, selectedNames: Set<String>) {
        model.setData(skills, selectedNames)
    }

    fun setCheckboxesEnabled(enabled: Boolean) {
        model.setCheckboxesEnabled(enabled)
        val column = table.columnModel.getColumn(0)
        if (enabled) {
            column.minWidth = JBUI.scale(30)
            column.maxWidth = JBUI.scale(30)
            column.preferredWidth = JBUI.scale(30)
        } else {
            column.minWidth = 0
            column.maxWidth = 0
            column.preferredWidth = 0
        }
    }

    fun getSelectedSkillNames(): List<String> = model.getSelectedSkillNames()

    private fun toHtmlTooltip(text: String, maxCharsPerLine: Int): String {
        fun escapeHtml(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        fun softenUnbreakable(s: String): String = s
            .replace("/", "/<wbr>")
            .replace("\\", "\\<wbr>")
            .replace(".", ".<wbr>")
            .replace("_", "_<wbr>")
            .replace("-", "-<wbr>")

        val safe = softenUnbreakable(escapeHtml(text))
        val lines = buildList {
            var i = 0
            while (i < safe.length) {
                val end = (i + maxCharsPerLine).coerceAtMost(safe.length)
                add(safe.substring(i, end))
                i = end
            }
        }
        return "<html>${lines.joinToString("<br/>")}</html>"
    }

    private class SkillRow(
        val name: String,
        val version: String,
        val description: String,
        val path: String,
        var selected: Boolean
    )

    private class SkillsTableModel(private val scanner: SkillScannerService?) : AbstractTableModel() {
        private val rows = mutableListOf<SkillRow>()
        private val updatingSkillNames = mutableSetOf<String>()
        private var checkboxesEnabled: Boolean = false

        fun isCheckboxesEnabled(): Boolean = checkboxesEnabled

        fun setUpdating(name: String, updating: Boolean) {
            if (updating) updatingSkillNames.add(name) else updatingSkillNames.remove(name)
            val index = rows.indexOfFirst { it.name == name }
            if (index >= 0) {
                fireTableCellUpdated(index, 2)
            }
        }

        fun isUpdating(name: String): Boolean = updatingSkillNames.contains(name)

        fun isAllSelected(): Boolean = rows.isNotEmpty() && rows.all { it.selected }

        fun selectAll(select: Boolean) {
            rows.forEach { it.selected = select }
            fireTableDataChanged()
        }

        fun setCheckboxesEnabled(enabled: Boolean) {
            checkboxesEnabled = enabled
            fireTableDataChanged()
        }

        fun setData(skills: List<AgentSkill>, selectedNames: Set<String>) {
            rows.clear()
            skills.sortedBy { it.name.lowercase() }.forEach { s ->
                rows.add(
                    SkillRow(
                        name = s.name,
                        version = s.version,
                        description = s.description,
                        path = scanner?.shortenPath(s.path) ?: s.path,
                        selected = s.name in selectedNames
                    )
                )
            }
            fireTableDataChanged()
        }

        fun getSelectedSkillNames(): List<String> = rows.filter { it.selected }.map { it.name }
        fun getRow(i: Int): SkillRow? = rows.getOrNull(i)

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 5
        override fun getColumnName(column: Int): String = when (column) {
            0 -> ""
            1 -> "Name"
            2 -> "Version"
            3 -> "Description"
            4 -> "Location"
            else -> ""
        }
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            columnIndex == 0 && checkboxesEnabled

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val r = rows[rowIndex]
            return when (columnIndex) {
                0 -> r.selected
                1 -> r.name
                2 -> r.version
                3 -> r.description
                4 -> r.path
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0 || !checkboxesEnabled) return
            val v = (aValue as? Boolean) ?: return
            rows[rowIndex].selected = v
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }
}
