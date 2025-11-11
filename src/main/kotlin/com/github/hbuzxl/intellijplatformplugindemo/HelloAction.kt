package com.github.hbuzxl.intellijplatformplugindemo


import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger

class HelloAction : AnAction("Hello From Plugin") {
    private val log = Logger.getInstance(HelloAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val msg = "Hello action clicked!"
        log.info(msg)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Plugin Notifications")
            .createNotification(msg, NotificationType.INFORMATION)
            .notify(e.project)
        println(msg)
    }
}
