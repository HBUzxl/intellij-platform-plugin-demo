# intellij-platform-plugin-demo

## 功能介绍

这是一个 IntelliJ Platform 插件演示项目，主要功能包括：

**ACP Chat 工具窗口**：提供了一个聊天界面，可以与 AI 助手进行交互

## 如何运行

1. 克隆项目到本地：
   ```bash
   cd intellij-platform-plugin-demo
   ```

2. 安装 codex-acp 工具：
  ```bash
  # 方法1：或者下载二进制文件并添加到 PATH 中 /usr/local/bin, 推荐
  # 确保 codex-acp 在系统 PATH 中，或者修改代码中的绝对路径

  # 方法2：使用 npm 安装
  npm install -g @zed-industries/codex-acp
  ```

3. 设置 OpenAI API 密钥：
   ```bash
   export OPENAI_API_KEY=your_api_key_here
   ```

4. 运行插件：
   - 使用命令行：`./gradlew runIde`

5. 在新打开的 IDE 实例中：
   - 底部会出现 "ACP Chat" 工具窗口
   - 可以通过 `Tools` -> `Start ACP Session` 菜单启动会话
   - 在聊天窗口中输入消息与 AI 交互
