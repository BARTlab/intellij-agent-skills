package com.bartlab.agentskills.mcp

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage

class SkillMcpJsonMessageParser(private val mapper: McpJsonMapper) {
    fun parse(body: String): JSONRPCMessage {
        val raw = mapper.readValue(body, object : TypeRef<Map<String, Any?>>() {})
        val method = raw["method"]
        val idValue = raw["id"]

        return when {
            method != null -> {
                if (idValue != null) {
                    mapper.convertValue(raw, McpSchema.JSONRPCRequest::class.java)
                } else {
                    mapper.convertValue(raw, McpSchema.JSONRPCNotification::class.java)
                }
            }
            raw.containsKey("result") || raw.containsKey("error") || idValue != null -> {
                mapper.convertValue(raw, McpSchema.JSONRPCResponse::class.java)
            }
            else -> throw IllegalArgumentException("Unsupported JSON-RPC message: missing method/result/error/id")
        }
    }
}