package com.bartlab.agentskills.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillMcpJsonMessageParserTest {
    private val mapper = JacksonMcpJsonMapper(ObjectMapper().registerKotlinModule())
    private val parser = SkillMcpJsonMessageParser(mapper)

    @Test
    fun parseRequest() {
        val json = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "tools/list",
              "params": {"limit": 5}
            }
        """.trimIndent()

        val message = parser.parse(json)
        assertTrue(message is McpSchema.JSONRPCRequest)
        assertEquals("tools/list", message.method())
        assertEquals(1, message.id())
    }

    @Test
    fun parseNotification() {
        val json = """
            {
              "jsonrpc": "2.0",
              "method": "notifications/cancel",
              "params": {"requestId": 10}
            }
        """.trimIndent()

        val message = parser.parse(json)
        assertTrue(message is McpSchema.JSONRPCNotification)
        assertEquals("notifications/cancel", message.method())
    }

    @Test
    fun parseResponse() {
        val json = """
            {
              "jsonrpc": "2.0",
              "id": "abc",
              "result": {"ok": true}
            }
        """.trimIndent()

        val message = parser.parse(json)
        assertTrue(message is McpSchema.JSONRPCResponse)
        assertEquals("abc", message.id())
    }
}