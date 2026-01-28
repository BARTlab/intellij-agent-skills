package com.bartlab.agentskills.mcp

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.bartlab.agentskills.settings.SkillSettingsState

@Service(Service.Level.PROJECT)
@State(name = "AgentSkillsChatSessions", storages = [Storage("agent-skills-chat-sessions.xml")])
class SkillChatSessionState : PersistentStateComponent<SkillChatSessionState.State> {

    class Session {
        var exposureMode: SkillSettingsState.SkillExposureMode = SkillSettingsState.SkillExposureMode.AUTO_ALL_METADATA
        var selectedSkillNames: MutableList<String> = mutableListOf()
        var updatedAtEpochMs: Long = 0L
    }

    class State {
        var sessions: MutableMap<String, Session> = mutableMapOf()
        var maxSessionsToKeep: Int = 50
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        cleanup()
    }

    fun getOrCreate(sessionKey: String): Session {
        val now = System.currentTimeMillis()
        val s = myState.sessions.getOrPut(sessionKey) { Session() }
        s.updatedAtEpochMs = now
        cleanup()
        return s
    }

    fun update(sessionKey: String, exposureMode: SkillSettingsState.SkillExposureMode, selected: List<String>) {
        val s = getOrCreate(sessionKey)
        s.exposureMode = exposureMode
        s.selectedSkillNames = selected.toMutableList()
        s.updatedAtEpochMs = System.currentTimeMillis()
        cleanup()
    }

    private fun cleanup() {
        val limit = myState.maxSessionsToKeep.coerceAtLeast(1)
        if (myState.sessions.size <= limit) return

        val sorted = myState.sessions.entries.sortedBy { it.value.updatedAtEpochMs }
        val toRemove = sorted.take(myState.sessions.size - limit).map { it.key }
        toRemove.forEach { myState.sessions.remove(it) }
    }

    companion object {
        fun getInstance(project: Project): SkillChatSessionState =
            project.getService(SkillChatSessionState::class.java)
    }
}