package com.ye.aiassistant

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.ye.aiassistant.ui.AiPanels

/**
 * 仅用于本地自动化验证：加 -Dai.assistant.autotest=true 时打开项目后自动展开面板。
 * 再加 -Dai.assistant.demo=true（`./gradlew runIde -Pautotest -Pdemo`）会塞几条示例消息，
 * 方便在无 API Key / 不联网的情况下检查面板排版。
 */
class AutoTestStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (System.getProperty("ai.assistant.autotest") != "true") return
        ApplicationManager.getApplication().invokeLater {
            val panel = AiPanels.open(project) ?: return@invokeLater
            // 只给 -PdemoOut 不配 -Pdemo 时渲染的是空状态本身（未开始对话的样子）
            System.getProperty("ai.assistant.demo.font")?.toIntOrNull()?.let {
                com.ye.aiassistant.settings.AiSettings.get().chatFontSize = it
            }
            System.getProperty("ai.assistant.demo.bubble")?.toIntOrNull()?.let {
                com.ye.aiassistant.settings.AiSettings.get().bubbleWidthPercent = it
            }
            System.getProperty("ai.assistant.demo.density")?.toIntOrNull()?.let {
                com.ye.aiassistant.settings.AiSettings.get().uiScalePercent = it
            }
            // 面板是先建好再改设置的，得让它按新尺寸重排一遍（顺便验证 refreshAppearance 这条路）
            panel.refreshAppearance()
            if (System.getProperty("ai.assistant.demo") == "true") panel.seedPreviewForScreenshot()
            val panelOut = System.getProperty("ai.assistant.demo.out")
            val dialogWhich = System.getProperty("ai.assistant.demo.dialog")
            if (panelOut == null && dialogWhich == null) {
                panel.focusInput()
                return@invokeLater
            }
            // 等面板按工具窗口的真实宽度排完一遍，再离屏出图
            ApplicationManager.getApplication().invokeLater {
                val size = System.getProperty("ai.assistant.demo.size", "430x900")
                val w = size.substringBefore('x').toIntOrNull() ?: 430
                val h = size.substringAfter('x').toIntOrNull() ?: 900

                if (panelOut != null) {
                    PreviewShot.write(panel, panelOut, w, h)

                    // 顺手把设置页也出一张（同一轮启动，不额外花时间）
                    System.getProperty("ai.assistant.demo.settings")?.let { path ->
                        runCatching {
                            val settings = com.ye.aiassistant.settings.AiConfigurable().createComponent()
                            PreviewShot.write(settings, path, w * 2, h)
                        }
                    }
                }

                // 弹窗是模态的，照不到工具窗口那一套 —— 单独给它一条出图通路
                if (dialogWhich != null) {
                    val path = System.getProperty("ai.assistant.demo.dialog.out", "/tmp/idea-ai-dialog.png")
                    runCatching {
                        val content = com.ye.aiassistant.ui.DialogPreview.panel(project, dialogWhich)
                        if (content != null) {
                            // 用弹窗自己的首选尺寸出图：这样量出来的宽高就是它真实要占的地方
                            val pref = content.preferredSize
                            PreviewShot.write(content, path, pref.width.coerceAtLeast(320), pref.height.coerceAtLeast(120))
                        }
                    }
                }
                panel.focusInput()
            }
        }
    }
}
