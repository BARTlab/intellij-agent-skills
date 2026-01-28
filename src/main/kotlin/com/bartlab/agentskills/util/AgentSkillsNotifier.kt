package com.bartlab.agentskills.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object AgentSkillsNotifier {
    fun notify(project: Project, title: String, content: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AgentSkills")
            .createNotification(title, content, type)
            .notify(project)
    }
}
