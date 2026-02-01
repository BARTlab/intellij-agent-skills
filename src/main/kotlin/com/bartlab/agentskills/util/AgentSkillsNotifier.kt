package com.bartlab.agentskills.util

import com.bartlab.agentskills.AgentSkillsConstants
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Utility for sending notifications to the user.
 *
 * Uses IntelliJ Notification API to show popup notifications.
 */
object AgentSkillsNotifier {
    
    /**
     * Sends a notification to the user.
     *
     * @param project Project context for showing the notification
     * @param title Notification title
     * @param content Notification text
     * @param type Notification type (INFORMATION, WARNING, ERROR)
     */
    fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(AgentSkillsConstants.NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
