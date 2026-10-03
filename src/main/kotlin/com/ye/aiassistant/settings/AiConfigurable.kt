package com.ye.aiassistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.ye.aiassistant.llm.ChatMsg
import com.ye.aiassistant.llm.LlmClient
import com.ye.aiassistant.ui.AiPanels
import com.ye.aiassistant.ui.Theme
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

class AiConfigurable : Configurable {

    private val baseUrl = JBTextField()
    private val apiKey = JPasswordField()
    private val model = JBTextField()
    private val temperature = JBTextField()
    private val maxTokens = JBTextField()
    private val proxyUrl = JBTextField()
    private val timeoutSeconds = JBTextField()
    private val extraHeaders = JBTextField()
    private val minimalFix = JCheckBox("默认开启「最小修改模式」")
    private val maxFixLines = JBTextField()
    private val contextLines = JBTextField()
    private val autoOpenDiff = JCheckBox("生成改动后自动打开 Diff 窗口")
    private val showThinking = JCheckBox("显示模型思考过程")
    private val sendReasoningContent = JCheckBox("回传 reasoning_content（DeepSeek 需要）")
    private val autoApplyEdits = JCheckBox("允许 Agent 直接改文件（不推荐）")
    private val attachContext = JCheckBox("发送消息时附带当前文件内容（上下文）")
    private val maxAgentSteps = JBTextField()
    private val chatFontSizeCombo = JComboBox(AiSettings.FONT_SIZE_CHOICES.map { it.first }.toTypedArray())
    private val bubbleWidthCombo = JComboBox(AiSettings.BUBBLE_WIDTH_CHOICES.map { it.first }.toTypedArray())
    private val uiScaleCombo = JComboBox(AiSettings.UI_SCALE_CHOICES.map { it.first }.toTypedArray())

    private val testResult = JBLabel(" ")
    private var root: JComponent? = null
    private var snapshot: List<String> = emptyList()

    override fun getDisplayName(): String = "AI Assistant"

    /** 灰色小字说明：让每个开关自己解释自己，不用去翻 README。 */
    private fun hint(text: String): JBLabel = JBLabel(
        "<html><font color=\"${hex(Theme.meta())}\" size=\"-1\">$text</font></html>",
    ).apply { border = JBUI.Borders.emptyTop(2) }

    /**
     * 分组标题：标题 + 一条伸到右边的细线。
     * 平台自带的 TitledSeparator 在新 UI（Islands）下那条线几乎看不见，分组等于没有，
     * 所以自己画一条。
     */
    private fun sectionHeader(title: String): JComponent {
        val label = JBLabel(title).apply {
            font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD, Theme.bodySize.toFloat())
            foreground = Theme.text()
        }
        val rule = object : JComponent() {
            init {
                preferredSize = Dimension(0, JBUI.scale(1))
                minimumSize = Dimension(0, JBUI.scale(1))
            }

            override fun paintComponent(g: java.awt.Graphics) {
                g.color = Theme.hairline()
                g.fillRect(0, height / 2, width, JBUI.scale(1))
            }
        }
        val row = JPanel(BorderLayout(JBUI.scale(12), 0))
        row.isOpaque = false
        row.border = JBUI.Borders.empty(10, 0, 4, 0)
        row.add(label, BorderLayout.WEST)
        row.add(rule, BorderLayout.CENTER)
        return row
    }

    private fun hex(color: java.awt.Color): String =
        "#%02x%02x%02x".format(color.red, color.green, color.blue)

    override fun createComponent(): JComponent {
        val testButton = JButton("测试连接").apply {
            toolTipText = "用上面这套配置发一个最小请求，验证 URL / Key / 模型是否可用"
            addActionListener { testConnection() }
        }
        val defaultsButton = JButton("恢复默认").apply {
            toolTipText = "把表单恢复成默认值（要点 Apply 才会真正生效）"
            addActionListener { loadDefaults() }
        }

        val panel = FormBuilder.createFormBuilder()
            // ── 连接 ─────────────────────────────────────
            .addComponent(sectionHeader("连接"))
            .addLabeledComponent("接口地址 Base URL:", baseUrl, 1, false)
            .addComponentToRightColumn(hint("默认对齐 opencode zen 网关；也可填 https://api.deepseek.com 或本地 Ollama"))
            .addLabeledComponent("API Key:", apiKey, 1, false)
            .addLabeledComponent("模型:", model, 1, false)
            .addLabeledComponent("temperature:", temperature, 1, false)
            .addComponentToRightColumn(hint("0～2，越低越稳定；改代码建议 0.1～0.3"))
            .addLabeledComponent("max_tokens（0 = 不限制）:", maxTokens, 1, false)
            .addLabeledComponent("代理（留空直连）:", proxyUrl, 1, false)
            .addLabeledComponent("超时（秒）:", timeoutSeconds, 1, false)
            .addLabeledComponent("额外请求头（每行 Key: Value）:", extraHeaders, 1, false)
            // ── 修改行为 ─────────────────────────────────
            .addComponent(sectionHeader("修改行为"))
            .addComponent(minimalFix)
            .addComponentToRightColumn(hint("只改报错相关的行，禁止重构 / 格式化 / 大范围改动"))
            .addLabeledComponent("最小修改 · 最大改动行数:", maxFixLines, 1, false)
            .addLabeledComponent("最小修改 · 报错行上下范围:", contextLines, 1, false)
            .addComponent(autoOpenDiff)
            .addComponentToRightColumn(hint("补丁生成后直接弹出 IDEA 原生 Diff，左边当前内容、右边建议"))
            .addComponent(autoApplyEdits)
            .addComponentToRightColumn(hint("不建议打开：开了之后 Agent 会跳过 Diff 确认，直接写文件"))
            // ── 对话 ─────────────────────────────────────
            .addComponent(sectionHeader("对话"))
            .addComponent(attachContext)
            .addComponentToRightColumn(
                hint(
                    "自动附上当前文件的内容、光标位置、选中片段和报错。<br>" +
                        "小文件给全文，大文件给光标附近的片段。关掉之后模型就看不到你的代码，只能让你手动粘贴",
                ),
            )
            .addComponent(showThinking)
            .addComponentToRightColumn(hint("把模型的 reasoning 内容折叠显示在回复上方"))
            .addComponent(sendReasoningContent)
            .addComponentToRightColumn(hint("DeepSeek 的推理模型需要回传 reasoning_content，其它服务一般不用"))
            .addLabeledComponent("Agent 最大轮次:", maxAgentSteps, 1, false)
            .addComponentToRightColumn(hint("一轮对话里「模型 → 工具 → 模型」最多循环几次"))
            // ── 对话外观 ─────────────────────────────────
            .addComponent(sectionHeader("对话外观"))
            .addLabeledComponent("对话字体大小:", chatFontSizeCombo, 1, false)
            .addComponentToRightColumn(
                hint(
                    "数字就是实际显示的字号（最小 9、最大 16）。代码、时间戳、标题都相对它推算。<br>" +
                        "「跟随 IDE」= 界面字号 − 1，最多按 13 算",
                ),
            )
            .addLabeledComponent("界面尺寸（块大小）:", uiScaleCombo, 1, false)
            .addComponentToRightColumn(
                hint(
                    "管「块」：内边距、圆角、按钮高度、列表行高、弹窗尺寸。<br>" +
                        "和上面那个（管「字」）互相独立 —— 可以字调小而点击区域保持宽松，反之也行",
                ),
            )
            .addLabeledComponent("消息气泡宽度:", bubbleWidthCombo, 1, false)
            .addComponentToRightColumn(hint("「铺满面板」= 横贯整行；选窄一点更像聊天气泡，右边会露出面板底色"))
            // ── 连接检查 ─────────────────────────────────
            .addComponent(sectionHeader("连接检查"))
            .addComponent(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(testButton)
                    add(defaultsButton)
                },
            )
            .addComponent(testResult)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val wrapper = JPanel(BorderLayout())
        wrapper.add(panel, BorderLayout.NORTH)
        wrapper.border = JBUI.Borders.empty(10, 12, 12, 12)
        root = wrapper
        reset()
        return wrapper
    }

    override fun reset() {
        val s = AiSettings.get()
        baseUrl.text = s.baseUrl
        apiKey.text = s.apiKey
        model.text = s.model
        temperature.text = s.temperature.toString()
        maxTokens.text = s.maxTokens.toString()
        proxyUrl.text = s.proxyUrl
        timeoutSeconds.text = s.timeoutSeconds.toString()
        extraHeaders.text = s.extraHeaders
        minimalFix.isSelected = s.minimalFix
        maxFixLines.text = s.maxFixLines.toString()
        contextLines.text = s.contextLines.toString()
        autoOpenDiff.isSelected = s.autoOpenDiff
        showThinking.isSelected = s.showThinking
        sendReasoningContent.isSelected = s.sendReasoningContent
        autoApplyEdits.isSelected = s.autoApplyEdits
        attachContext.isSelected = s.attachContext
        maxAgentSteps.text = s.maxAgentSteps.toString()
        chatFontSizeCombo.selectedIndex =
            AiSettings.FONT_SIZE_CHOICES.indexOfFirst { it.second == s.chatFontSize }.coerceAtLeast(0)
        bubbleWidthCombo.selectedIndex =
            AiSettings.BUBBLE_WIDTH_CHOICES.indexOfFirst { it.second == s.bubbleWidthPercent }.coerceAtLeast(0)
        uiScaleCombo.selectedIndex =
            AiSettings.UI_SCALE_CHOICES.indexOfFirst { it.second == s.uiScalePercent }.coerceAtLeast(0)
        snapshot = currentValues()
    }

    override fun isModified(): Boolean = currentValues() != snapshot

    override fun apply() {
        writeInto(AiSettings.get())
        snapshot = currentValues()
        // 已经打开的面板立刻按新字号 / 新气泡宽度重排。
        // 只有一个消费者，直接找到面板调用就行，不必为此上消息总线。
        ProjectManager.getInstance().openProjects.forEach { project ->
            AiPanels.get(project)?.refreshAppearance()
        }
    }

    override fun disposeUIResources() {
        root = null
    }

    private fun currentValues(): List<String> = listOf(
        baseUrl.text, String(apiKey.password), model.text, temperature.text, maxTokens.text,
        proxyUrl.text, timeoutSeconds.text, extraHeaders.text, minimalFix.isSelected.toString(), maxFixLines.text,
        contextLines.text, autoOpenDiff.isSelected.toString(), showThinking.isSelected.toString(),
        sendReasoningContent.isSelected.toString(), autoApplyEdits.isSelected.toString(),
        attachContext.isSelected.toString(), maxAgentSteps.text,
        chatFontSizeCombo.selectedIndex.toString(), bubbleWidthCombo.selectedIndex.toString(),
        uiScaleCombo.selectedIndex.toString(),
    )

    private fun writeInto(s: AiSettings.State) {
        s.baseUrl = baseUrl.text.trim()
        s.apiKey = String(apiKey.password).trim()
        s.model = model.text.trim()
        s.temperature = temperature.text.trim().toDoubleOrNull() ?: 0.2
        s.maxTokens = maxTokens.text.trim().toIntOrNull() ?: 0
        s.proxyUrl = proxyUrl.text.trim()
        s.timeoutSeconds = timeoutSeconds.text.trim().toIntOrNull() ?: 300
        s.extraHeaders = extraHeaders.text.trim()
        s.minimalFix = minimalFix.isSelected
        s.maxFixLines = maxFixLines.text.trim().toIntOrNull() ?: 40
        s.contextLines = contextLines.text.trim().toIntOrNull() ?: 3
        s.autoOpenDiff = autoOpenDiff.isSelected
        s.showThinking = showThinking.isSelected
        s.sendReasoningContent = sendReasoningContent.isSelected
        s.autoApplyEdits = autoApplyEdits.isSelected
        s.attachContext = attachContext.isSelected
        s.maxAgentSteps = maxAgentSteps.text.trim().toIntOrNull() ?: 16
        s.chatFontSize = AiSettings.FONT_SIZE_CHOICES.getOrNull(chatFontSizeCombo.selectedIndex)?.second ?: 0
        s.uiScalePercent = AiSettings.UI_SCALE_CHOICES.getOrNull(uiScaleCombo.selectedIndex)?.second ?: 100
        s.bubbleWidthPercent = AiSettings.BUBBLE_WIDTH_CHOICES.getOrNull(bubbleWidthCombo.selectedIndex)?.second
            ?: 100
    }

    /** 把表单恢复成默认值。不落盘 —— 用户点 Apply 才生效，和 IDEA 设置页的语义一致。 */
    private fun loadDefaults() {
        val d = AiSettings.State()
        baseUrl.text = d.baseUrl
        apiKey.text = ""
        model.text = d.model
        temperature.text = d.temperature.toString()
        maxTokens.text = d.maxTokens.toString()
        proxyUrl.text = d.proxyUrl
        timeoutSeconds.text = d.timeoutSeconds.toString()
        extraHeaders.text = d.extraHeaders
        minimalFix.isSelected = d.minimalFix
        maxFixLines.text = d.maxFixLines.toString()
        contextLines.text = d.contextLines.toString()
        autoOpenDiff.isSelected = d.autoOpenDiff
        showThinking.isSelected = d.showThinking
        sendReasoningContent.isSelected = d.sendReasoningContent
        autoApplyEdits.isSelected = d.autoApplyEdits
        attachContext.isSelected = d.attachContext
        maxAgentSteps.text = d.maxAgentSteps.toString()
        chatFontSizeCombo.selectedIndex =
            AiSettings.FONT_SIZE_CHOICES.indexOfFirst { it.second == d.chatFontSize }.coerceAtLeast(0)
        uiScaleCombo.selectedIndex =
            AiSettings.UI_SCALE_CHOICES.indexOfFirst { it.second == d.uiScalePercent }.coerceAtLeast(0)
        bubbleWidthCombo.selectedIndex =
            AiSettings.BUBBLE_WIDTH_CHOICES.indexOfFirst { it.second == d.bubbleWidthPercent }.coerceAtLeast(0)
        testResult.text = " "
    }

    private fun testConnection() {
        val temp = AiSettings.State()
        writeInto(temp)
        testResult.text = "请求中…"
        testResult.foreground = Theme.meta()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                LlmClient(temp).complete(listOf(ChatMsg.user("ping")), maxTokens = 8)
            }
            ApplicationManager.getApplication().invokeLater {
                if (result.isSuccess) {
                    testResult.foreground = Theme.ok()
                    testResult.text = "连接正常，模型回复: ${result.getOrThrow().text.take(60).ifBlank { "(空)" }}"
                } else {
                    testResult.foreground = Theme.danger()
                    testResult.text = "失败: ${result.exceptionOrNull()?.message?.take(300)}"
                }
            }
        }
    }
}
