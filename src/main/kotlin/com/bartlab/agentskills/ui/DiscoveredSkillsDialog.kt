package com.bartlab.agentskills.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

class DiscoveredSkillsDialog(
    project: Project,
    skills: List<DiscoveredSkillItem>
) : DialogWrapper(project) {

    private val tableModel = DiscoveredSkillsTableModel(skills)

    init {
        title = "Select Skills to Install"
        setOKButtonText("Install")
        setCancelButtonText("Cancel")
        init()
    }

    fun getSelectedIndices(): List<Int> = tableModel.getSelectedIndices()

    override fun createCenterPanel(): JComponent {
        val table = JBTable(tableModel).apply {
            fillsViewportHeight = true
            rowHeight = JBUI.scale(24)
            autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            tableHeader.reorderingAllowed = false

            val checkCol = columnModel.getColumn(0)
            setFixedColumnWidth(checkCol, JBUI.scale(40))
            checkCol.headerRenderer = TableCellRenderer { t, _, _, _, _, _ ->
                val cb = JBCheckBox("", tableModel.isAllSelected())
                cb.isOpaque = false
                val panel = JPanel(BorderLayout())
                panel.add(cb, BorderLayout.CENTER)
                panel.background = t.tableHeader.background
                panel.border = JBUI.Borders.empty(0, 2)
                panel.accessibleContext?.accessibleName = "Select all skills"
                panel.accessibleContext?.accessibleDescription = "Toggle selection for all discovered skills"
                panel
            }
            tableHeader.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val col = columnAtPoint(e.point)
                    if (col == 0) {
                        tableModel.selectAll(!tableModel.isAllSelected())
                        tableHeader.repaint()
                    }
                }
            })
            columnModel.getColumn(1).preferredWidth = JBUI.scale(220)
            columnModel.getColumn(2).preferredWidth = JBUI.scale(360)
        }

        return panel {
            row {
                label("Found ${tableModel.getRowCount()} skills. Select which ones to install.")
            }
            row {
                cell(JBScrollPane(table)).align(Align.FILL)
            }.resizableRow()
        }.apply {
            preferredSize = JBUI.size(640, 380)
            border = JBUI.Borders.empty(10)
        }
    }

    private fun setFixedColumnWidth(column: javax.swing.table.TableColumn, width: Int) {
        column.minWidth = width
        column.maxWidth = width
        column.preferredWidth = width
    }

    data class DiscoveredSkillItem(
        val name: String,
        val path: String,
        val index: Int
    )

    private class DiscoveredSkillsTableModel(items: List<DiscoveredSkillItem>) : AbstractTableModel() {
        private val rows = items.map { Row(it, selected = true) }.toMutableList()

        fun isAllSelected(): Boolean = rows.isNotEmpty() && rows.all { it.selected }

        fun selectAll(select: Boolean) {
            rows.forEach { it.selected = select }
            fireTableDataChanged()
        }

        fun getSelectedIndices(): List<Int> = rows.filter { it.selected }.map { it.item.index }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = when (column) {
            0 -> ""
            1 -> "Skill"
            2 -> "Path"
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.selected
                1 -> row.item.name
                2 -> row.item.path
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 0 && aValue is Boolean) {
                rows[rowIndex].selected = aValue
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }

        private data class Row(
            val item: DiscoveredSkillItem,
            var selected: Boolean
        )
    }
}
