package com.bartlab.agentskills.model

enum class SkillSourceType {
    LOCAL, GITHUB, GITLAB, DIRECT_URL
}

data class SkillSource(
    val type: SkillSourceType,
    val url: String,
    val ref: String? = null,
    val subpath: String? = null,
    val localPath: String? = null
)
