package com.bartlab.agentskills.util

/**
 * Utilities for working with XML.
 */
object XmlUtils {
    
    /**
     * Escapes special XML characters.
     *
     * Replaces:
     * - `&` -> `&amp;`
     * - `<` -> `&lt;`
     * - `>` -> `&gt;`
     * - `"` -> `&quot;`
     * - `'` -> `&apos;`
     *
     * @param s Source string
     * @return Escaped string, safe for insertion into XML
     */
    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
