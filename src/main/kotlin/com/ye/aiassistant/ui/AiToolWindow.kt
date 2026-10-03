package com.ye.aiassistant.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.notification.NotificationType
import com.intellij.ui.content.ContentFactory
import com.ye.aiassistant.errors.ErrorCollector
import com.ye.aiassistant.settings.AiSettings

const val AI_TOOL_WINDOW_ID = "AI Assistant"

/** 持有当前项目的 ChatPanel，供 action / 工具窗口访问。 */
@Service(Service.Level.PROJECT)
class AiPanels {

    @Volatile
    var panel: ChatPanel? = null

    companion object {
        fun get(project: Project): ChatPanel? = project.service<AiPanels>().panel

        /** 打开（或聚焦）AI 工具窗口，并返回面板。 */
        fun open(project: Project): ChatPanel? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(AI_TOOL_WINDOW_ID)
            if (toolWindow != null) {
                if (!toolWindow.isVisible) toolWindow.show()
                toolWindow.activate(null)
            }
            return get(project)
        }
    }
}

class AiToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        project.service<AiPanels>().panel = panel
        val content = ContentFactory.getInstance().createContent(panel, "对话", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** 「修复当前文件报错」的入口：收集报错 -> 选择 -> 交给 ChatPanel 处理。 */
object FixFlow {

    fun fixCurrentFile(project: Project) {
        // 先查配置再弹报错选择框：否则用户选完报错、点确定，才被告知没配 Key，白选一轮
        if (!AiSettings.isConfigured()) {
            notifyBalloon(project, "AI 助手", "还没有配置 API Key：Settings > Tools > AI Assistant", NotificationType.WARNING)
            return
        }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            notifyBalloon(project, "AI 助手", "没有打开的编辑器", NotificationType.WARNING)
            return
        }
        val errors = ErrorCollector.collect(project, editor)
        if (errors.isEmpty()) {
            notifyBalloon(project, "AI 助手", "当前文件没有检测到报错", NotificationType.INFORMATION)
            return
        }
        val caretLine = editor.caretModel.logicalPosition.line + 1
        val dialog = ErrorSelectDialog(project, errors, caretLine)
        if (!dialog.showAndGet()) return
        val selected = dialog.selectedItems
        if (selected.isEmpty()) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val panel = AiPanels.open(project)
        if (panel == null) {
            notifyBalloon(project, "AI 助手", "AI 工具窗口还没准备好，请手动打开右侧的 AI Assistant 面板", NotificationType.WARNING)
            return
        }
        panel.startFix(file, selected)
    }
}
