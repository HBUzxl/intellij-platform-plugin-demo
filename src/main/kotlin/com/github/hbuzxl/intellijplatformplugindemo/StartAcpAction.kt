package com.github.hbuzxl.intellijplatformplugindemo

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSupport
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class StartAcpAction : AnAction("Start ACP Session") {
    private val log = Logger.getInstance(StartAcpAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        notifyInfo("Starting ACP session…")

        // ① 解析 agent 命令（优先绝对路径 → PATH → npx 回退）
        val cmd = agentCommand()
        if (cmd == null) {
            notifyError("codex-acp not found. Set absolute path or install it in PATH.")
            return
        }

        // ② 读取密钥（从父进程环境继承；从命令行 export 后再 runIde 最稳）
        val apiKey = System.getenv("OPENAI_API_KEY") ?: ""
        if (apiKey.isBlank()) {
            notifyWarn("OPENAI_API_KEY is empty; the agent may not respond.")
        }

        // ③ 启动子进程
        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply { environment()["OPENAI_API_KEY"] = apiKey }

        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            log.warn("Failed to start agent: $cmd", t)
            notifyError("Failed to start agent: ${t.message}")
            return
        }

        // ④ 协程作用域（IO 线程池），真正跑协议
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                // 只把 stdio 交给 transport，不要另起线程读取 stdout
                val transport = StdioTransport(
                    parentScope = this,
                    ioDispatcher = Dispatchers.IO,
                    input = proc.inputStream.asSource().buffered(),
                    output = proc.outputStream.asSink().buffered()
                )

                // 使用 Protocol 构造函数
                val protocol = Protocol(
                    parentScope = this,
                    transport = transport
                )

                // 简单的 ClientSupport 实现
                val clientSupport = object : ClientSupport {
                    override suspend fun createClientSession(session: ClientSession, _sessionResponseMeta: JsonElement?): ClientSessionOperations {
                        return object : ClientSessionOperations {
                            override suspend fun requestPermissions(
                                toolCall: com.agentclientprotocol.model.SessionUpdate.ToolCallUpdate,
                                permissions: List<com.agentclientprotocol.model.PermissionOption>,
                                _meta: JsonElement?
                            ): com.agentclientprotocol.model.RequestPermissionResponse {
                                return com.agentclientprotocol.model.RequestPermissionResponse(
                                    outcome = com.agentclientprotocol.model.RequestPermissionOutcome.Selected(
                                        permissions.first().optionId
                                    )
                                )
                            }

                            override suspend fun notify(notification: com.agentclientprotocol.model.SessionUpdate, _meta: JsonElement?) {
                                log.info("Received notification: $notification")
                            }
                        }
                    }
                }

                val client = Client(protocol, clientSupport)

                // 先启动事件循环（异步）
                val loop = launch { protocol.start() }
                delay(100) // 让事件循环先起来

                client.initialize(
                    ClientInfo(
                        capabilities = ClientCapabilities(
                            fs = FileSystemCapability(readTextFile = true, writeTextFile = true)
                        )
                    )
                )

                notifyInfo("ACP protocol started successfully")

                // 演示：给会话一点时间
                withTimeoutOrNull(60_000) { loop.join() }
            } catch (t: Throwable) {
                log.warn("ACP session failed", t)
                notifyError("ACP session failed: ${t.message}")
            } finally {
                // 尝试优雅退出子进程
                proc.waitFor(500, TimeUnit.MILLISECONDS)
                if (proc.isAlive) proc.destroyForcibly()
            }
        }
    }

    /** 返回 "命令 + 参数" 列表；不要传一整串带空格的字符串 */
    private fun agentCommand(): List<String>? {
        // ★ 强烈建议先把 codex-acp 的绝对路径写死测试，最稳：
        val CODEx_ACP_ABS_PATH = "/usr/local/bin/codex-acp" // ← 改成你的真实路径
        if (Files.isExecutable(Paths.get(CODEx_ACP_ABS_PATH))) return listOf(CODEx_ACP_ABS_PATH)

        // 其二：PATH 里有就用
        if (isOnPath("codex-acp")) return listOf("codex-acp")

        // 最后回退：npx（注意要拆成多参数；Windows 用 npx.cmd）
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val npx = if (isWindows) "npx.cmd" else "npx"
        return if (isOnPath(npx)) listOf(npx, "-y", "@zed-industries/codex-acp") else null
    }

    private fun isOnPath(cmd: String): Boolean =
        try { ProcessBuilder(cmd, "--version").start().waitFor(3, TimeUnit.SECONDS) }
        catch (_: Throwable) { false }

    private fun notifyInfo(msg: String) = notify(msg, NotificationType.INFORMATION)
    private fun notifyWarn(msg: String) = notify(msg, NotificationType.WARNING)
    private fun notifyError(msg: String) = notify(msg, NotificationType.ERROR)

    private fun notify(msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Plugin Notifications")
            .createNotification(msg, type)
            .notify(null)
        log.info(msg)
        println(msg)
    }
}
