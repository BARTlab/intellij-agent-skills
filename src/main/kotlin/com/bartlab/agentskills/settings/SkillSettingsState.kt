package com.bartlab.agentskills.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "AgentSkillsSettings", storages = [Storage("agent-skills.xml")])
class SkillSettingsState : PersistentStateComponent<SkillSettingsState.State> {

    enum class SkillExposureMode {
        /**
         * Метаданные публикуются для всех найденных скиллов.
         * Полный текст/инструкции — только по tool-запросу (skills).
         */
        AUTO_ALL_METADATA,

        /**
         * Метаданные публикуются только для выбранных пользователем скиллов.
         * Полный текст/инструкции — только по tool-запросу (skills).
         */
        SELECTED_ONLY_METADATA
    }

    class State {
        var useCustomPath: Boolean = false
        var customPath: String = "~/.agents/skills"

        var exposureMode: SkillExposureMode = SkillExposureMode.AUTO_ALL_METADATA
        var selectedSkillNames: MutableList<String> = mutableListOf()

        var integrateAiAssistant: Boolean = true
        var mcpPort: Int = 24680
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): SkillSettingsState =
            project.getService(SkillSettingsState::class.java)
    }
}