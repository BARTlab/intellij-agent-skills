package com.bartlab.agentskills.util

import javax.swing.table.TableColumn

/**
 * Utilities for working with Swing tables.
 */
object TableUtils {
    
    /**
     * Sets fixed width for a table column.
     *
     * @param column Table column
     * @param width Width in pixels
     */
    fun setFixedColumnWidth(column: TableColumn, width: Int) {
        column.minWidth = width
        column.maxWidth = width
        column.preferredWidth = width
    }
    
    /**
     * Formats text for HTML tooltip with line breaks.
     *
     * @param text Source text
     * @param maxCharsPerLine Maximum number of characters per line
     * @return HTML-formatted tooltip
     */
    fun toHtmlTooltip(text: String, maxCharsPerLine: Int = 80): String {
        if (text.isBlank()) return ""
        
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
    
    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun softenUnbreakable(s: String): String = s
        .replace("/", "/<wbr>")
        .replace("\\", "\\<wbr>")
        .replace(".", ".<wbr>")
        .replace("_", "_<wbr>")
        .replace("-", "-<wbr>")
}
