package com.ye.aiassistant.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.notification.NotificationType
import com.ye.aiassistant.llm.ChatMsg
import com.ye.aiassistant.llm.LlmClient
import com.ye.aiassistant.settings.AiSettings
import com.ye.aiassistant.ui.AiPanels
import com.ye.aiassistant.ui.FixFlow
import com.ye.aiassistant.ui.notifyBalloon

/** Alt+Shift+E：修复当前文件的报错。 */
class FixErrorsAction : AnAction(
    "AI 修复报错…",
    "用 AI 修复当前文件的编译/检查报错（Diff 预览后应用）",
    AllIcons.Actions.IntentionBulb,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FixFlow.fixCurrentFile(project)
    }
}

/** Alt+Shift+Q：从最后一条 AI 回复里抽取问题，箭头选择答案。 */
class QnaAction : AnAction(
    "AI 问答抽取（Q&A）",
    "从最后一条 AI 回复中抽取问题，用方向键选择答案",
    AllIcons.General.QuestionDialog,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = AiPanels.open(project)
        if (panel == null) {
            notifyBalloon(project, "AI 助手", "AI 面板未就绪", NotificationType.WARNING)
            return
        }
        panel.startQna()
    }
}

/** Alt+Shift+A：打开 AI 面板并聚焦输入框。 */
class OpenChatAction : AnAction(
    "打开 AI 助手",
    "打开 AI 助手面板",
    AllIcons.Toolwindows.ToolWindowMessages,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AiPanels.open(project)?.focusInput()
    }
}

/** 测试连通性（Tools 菜单里也能点）。 */
class TestConnectionAction : AnAction("测试 AI 连接", "用当前配置发一个最小请求", AllIcons.Actions.Execute) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!AiSettings.isConfigured()) {
            notifyBalloon(project, "AI 助手", "请先在 Settings > Tools > AI Assistant 配置 API Key", NotificationType.WARNING)
            return
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                LlmClient(AiSettings.get()).complete(listOf(ChatMsg.user("ping")), maxTokens = 8)
            }
            val message = result.map { it.text.ifBlank { "（模型返回空文本，但连接正常）" } }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            notifyBalloon(
                project,
                if (result.isSuccess) "AI 连接正常" else "AI 连接失败",
                message,
                if (result.isSuccess) NotificationType.INFORMATION else NotificationType.ERROR,
            )
        }
    }
}
