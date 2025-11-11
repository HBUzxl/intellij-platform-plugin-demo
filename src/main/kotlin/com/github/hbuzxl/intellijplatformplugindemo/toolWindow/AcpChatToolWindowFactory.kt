package com.github.hbuzxl.intellijplatformplugindemo.toolWindow

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.ClientSupport
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.swing.*
import java.awt.BorderLayout
import java.awt.FlowLayout

class AcpChatToolWindowFactory : ToolWindowFactory {
    private val log = Logger.getInstance(AcpChatToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val acpChatToolWindow = AcpChatToolWindow(toolWindow)
        val content = ContentFactory.getInstance().createContent(acpChatToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class AcpChatToolWindow(toolWindow: ToolWindow) {
        private val log = Logger.getInstance(AcpChatToolWindow::class.java)
        private var client: Client? = null
        private var protocol: Protocol? = null
        private var proc: Process? = null
        private var scope: CoroutineScope? = null
        private var clientSession: ClientSession? = null
        private var sessionId: SessionId? = null
        private var sessionOperations: ClientSessionOperations? = null
        
        // UI组件
        private val messageArea = JTextArea()
        private val inputField = JTextField()
        private val sendButton = JButton("Send")
        
        init {
            messageArea.isEditable = false
            messageArea.wrapStyleWord = true
            messageArea.lineWrap = true
            
            // 初始化ACP客户端
            initializeAcpClient()
        }

        fun getContent(): JComponent {
            val panel = JPanel(BorderLayout())
            
            // 消息区域（上方）
            val messageScrollPane = JScrollPane(messageArea)
            messageScrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
            panel.add(messageScrollPane, BorderLayout.CENTER)
            
            // 输入区域（下方）
            val inputPanel = JPanel(BorderLayout())
            inputPanel.add(inputField, BorderLayout.CENTER)
            
            // 发送按钮
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
            buttonPanel.add(sendButton)
            inputPanel.add(buttonPanel, BorderLayout.EAST)
            
            panel.add(inputPanel, BorderLayout.SOUTH)
            
            // 添加事件监听器
            setupEventListeners()
            
            return panel
        }
        
        private fun setupEventListeners() {
            // 发送按钮点击事件
            sendButton.addActionListener {
                sendMessage()
            }
            
            // 回车键发送消息
            inputField.addActionListener {
                sendMessage()
            }
        }
        
        private fun sendMessage() {
            val message = inputField.text.trim()
            if (message.isEmpty()) return
            val session = clientSession
            if (session == null) {
                appendMessage("Error: Session not initialized\n")
                return
            }

            // 清空输入框
            inputField.text = ""

            // 显示用户消息
            appendMessage("You: $message\n")

            // 发送消息到ACP客户端
            scope?.launch {
                try {
                    session.prompt(
                        listOf(ContentBlock.Text(message))
                    ).collect { event ->
                        when (event) {
                            is Event.SessionUpdateEvent -> handleNotification(event.update)
                            is Event.PromptResponseEvent -> appendMessage("Message sent successfully\n")
                        }
                    }
                } catch (e: Exception) {
                    this@AcpChatToolWindow.log.error("Failed to send message", e)
                    appendMessage("Error: Failed to send message: ${e.message}\n")
                }
            }
        }
        
        private fun appendMessage(message: String) {
            SwingUtilities.invokeLater {
                messageArea.append(message)
                // 自动滚动到底部
                messageArea.caretPosition = messageArea.document.length
            }
        }
        
        private fun initializeAcpClient() {
            // 在协程中初始化ACP客户端
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            
            scope?.launch {
                try {
                    // 解析agent命令
                    val cmd = agentCommand()
                    if (cmd == null) {
                        SwingUtilities.invokeLater {
                            appendMessage("Error: codex-acp not found. Set absolute path or install it in PATH.\n")
                        }
                        return@launch
                    }
                    
                    // 读取API密钥
                    val apiKey = System.getenv("OPENAI_API_KEY") ?: ""
                    
                    // 启动子进程
                    val pb = ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .apply { environment()["OPENAI_API_KEY"] = apiKey }
                    
                    proc = pb.start()
                    
                    // 设置transport和protocol
                    val transport = StdioTransport(
                        parentScope = this,
                        ioDispatcher = Dispatchers.IO,
                        input = proc!!.inputStream.asSource().buffered(),
                        output = proc!!.outputStream.asSink().buffered()
                    )
                    
                    protocol = Protocol(
                        parentScope = this,
                        transport = transport
                    )
                    
                    // 创建ClientSupport实现
                    val clientSupport = object : ClientSupport {
                        override suspend fun createClientSession(session: ClientSession, _sessionResponseMeta: JsonElement?): ClientSessionOperations {
                            // 保存sessionId
                            sessionId = session.sessionId
                            
                            // 创建并保存ClientSessionOperations对象
                            val operations = object : ClientSessionOperations {
                                override suspend fun requestPermissions(
                                    toolCall: SessionUpdate.ToolCallUpdate,
                                    permissions: List<PermissionOption>,
                                    _meta: JsonElement?
                                ): RequestPermissionResponse {
                                    return RequestPermissionResponse(
                                        outcome = RequestPermissionOutcome.Selected(
                                            permissions.first().optionId
                                        )
                                    )
                                }
                                
                                override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
                                    // 处理来自ACP的通知，显示在消息区域
                                    handleNotification(notification)
                                }
                            }
                            
                            // 保存ClientSessionOperations对象
                            sessionOperations = operations
                            
                            return operations
                        }
                    }
                    
                    client = Client(protocol!!, clientSupport)
                    
                    // 启动事件循环
                    val loop = launch { protocol!!.start() }
                    delay(100) // 让事件循环先起来
                    
                    // 初始化客户端
                    client!!.initialize(
                        ClientInfo(
                            capabilities = ClientCapabilities(
                                fs = FileSystemCapability(readTextFile = true, writeTextFile = true)
                            )
                        )
                    )
                    
                    try {
                        val sessionParameters = SessionParameters(
                            cwd = System.getProperty("user.dir"),
                            mcpServers = emptyList()
                        )
                        clientSession = client!!.newSession(sessionParameters)
                        sessionId = clientSession?.sessionId
                    } catch (e: Exception) {
                        this@AcpChatToolWindow.log.warn("Failed to create session", e)
                    }
                    
                    SwingUtilities.invokeLater {
                        appendMessage("ACP client initialized successfully.\n")
                        appendMessage("Session created with ID: ${sessionId?.value}\n")
                        appendMessage("You can now send messages.\n")
                    }
                } catch (t: Throwable) {
                    this@AcpChatToolWindow.log.warn("ACP initialization failed", t)
                    SwingUtilities.invokeLater {
                        appendMessage("Error: ACP initialization failed: ${t.message}\n")
                    }
                }
            }
        }
        
        private fun handleNotification(notification: SessionUpdate) {
            when (notification) {
                is SessionUpdate.AgentMessageChunk -> {
                    val content = notification.content
                    when (content) {
                        is ContentBlock.Text -> {
                            appendMessage(content.text)
                        }
                        else -> {
                            // 忽略其他类型的内容块
                        }
                    }
                }
                is SessionUpdate.AgentThoughtChunk -> {
                    // 可以选择显示agent的思考过程
                    // 这里我们忽略它，只显示最终回复
                }
                else -> {
                    // 忽略其他类型的通知
                }
            }
        }
        
        /** 返回 "命令 + 参数" 列表；不要传一整串带空格的字符串 */
        private fun agentCommand(): List<String>? {
            // 强烈建议先把 codex-acp 的绝对路径写死测试，最稳：
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
            try { 
                ProcessBuilder(cmd, "--version").start().waitFor(3, TimeUnit.SECONDS) 
            } catch (_: Throwable) { 
                false 
            }
        
        // 清理资源
        fun dispose() {
            scope?.cancel()
            protocol?.close()
            proc?.apply {
                waitFor(500, TimeUnit.MILLISECONDS)
                if (isAlive) destroyForcibly()
            }
        }
    }
}
