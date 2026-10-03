package com.ye.aiassistant.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

enum class ChatMode(val label: String, val hint: String) {
    ASK("问答模式", "只回答问题；发送时自动带上当前文件的内容"),
    AGENT("Agent 模式", "可以主动读文件、搜代码、生成改动");

    companion object {
        fun from(name: String?): ChatMode = entries.firstOrNull { it.name == name } ?: AGENT
    }
}

/**
 * 插件全局设置。用 IDEA 自带的 Settings > Tools > AI Assistant 修改，
 * 持久化到 <config>/options/ai-assistant.xml。
 */
@Service(Service.Level.APP)
@State(name = "AiAssistantSettings", storages = [Storage("ai-assistant.xml")])
class AiSettings : PersistentStateComponent<AiSettings.State> {

    class State {
        /** OpenAI 兼容的 base url，例如 https://api.deepseek.com 或 https://opencode.ai/zen/go/v1 */
        var baseUrl: String = "https://opencode.ai/zen/go/v1"
        var apiKey: String = ""
        var model: String = "deepseek-v4-flash"
        var temperature: Double = 0.2

        /** 0 表示不指定 max_tokens */
        var maxTokens: Int = 0

        /** 额外请求头，每行一条 "Key: Value"（例如自建网关要求的东西） */
        var extraHeaders: String = ""

        /** 形如 http://127.0.0.1:7897，留空表示不走代理 */
        var proxyUrl: String = ""
        var timeoutSeconds: Int = 300

        /** ChatMode.name */
        var mode: String = ChatMode.AGENT.name

        /** 最小修改模式：只改必要的行，禁止重构/格式化/改注释 */
        var minimalFix: Boolean = true
        var maxFixLines: Int = 40
        var contextLines: Int = 3

        /** 生成改动后自动弹出 IDEA 的 Diff 对比窗口 */
        var autoOpenDiff: Boolean = true
        /** 显示模型思考过程（deepseek-reasoner 等） */
        var showThinking: Boolean = false
        /** 回传 reasoning_content（DeepSeek 要求 assistant 消息带上） */
        var sendReasoningContent: Boolean = true
        /** 允许 Agent 直接写文件（默认关闭：一律走 Diff 预览） */
        var autoApplyEdits: Boolean = false
        var maxAgentSteps: Int = 16
        /** 发送消息时自动附带当前文件/光标上下文 */
        var attachContext: Boolean = true

        /**
         * 对话区正文字号。
         * 0 = 跟随 IDE 界面字号（但最多按 13 算）—— IDE 界面字号调大时聊天正文会跟着大得离谱，
         * 所以跟随模式封了顶；想要别的字号直接填数值。
         *
         * 这个值**就是实际渲染出来的字号**（HTML 那边用 pt，和标签/按钮 1:1）。
         */
        var chatFontSize: Int = 0

        /** 消息气泡宽度，占面板宽度的百分比。100 = 铺满（原来的样子）。 */
        var bubbleWidthPercent: Int = 100

        /**
         * 界面尺寸（「块大小」）百分比，100 = 标准。
         *
         * 和「对话字体大小」是两个独立的旋钮：
         * 字号管字（正文/代码/标题/时间戳），它管块（内边距、圆角、按钮高度、
         * 列表行高、弹窗尺寸）。想把字调小但保留宽松的点击区域，或者字不动、只把整体收紧，
         * 都能单独调。字号本身还有一个收得很紧的自适应（±15%），
         * 免得把字调到 9 之后控件还留着一圈给 12 号字的空白。
         */
        var uiScalePercent: Int = 100
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun get(): State = service<AiSettings>().myState

        fun isConfigured(): Boolean = get().apiKey.isNotBlank() && get().model.isNotBlank()

        /**
         * 字号下拉框的选项：显示文案 -> 字号（0 = 跟随 IDE）。
         *
         * 数值就是最终渲染出来的字号，所以可以放心往下给到 9 —— 原来最小只有 11，
         * 而 11 实际渲染成 14 号，用户才会觉得「怎么调都很小不了」。
         */
        val FONT_SIZE_CHOICES: List<Pair<String, Int>> = listOf(
            "跟随 IDE 界面字号" to 0,
            "9（很小）" to 9,
            "10（小）" to 10,
            "11" to 11,
            "12（默认观感）" to 12,
            "13" to 13,
            "14" to 14,
            "16" to 16,
        )

        /** 界面尺寸下拉框的选项：显示文案 -> 百分比。 */
        val UI_SCALE_CHOICES: List<Pair<String, Int>> = listOf(
            "紧凑（85%）" to 85,
            "标准（100%）" to 100,
            "宽松（115%）" to 115,
            "很宽松（130%）" to 130,
        )

        /** 气泡宽度下拉框的选项：显示文案 -> 百分比。 */
        val BUBBLE_WIDTH_CHOICES: List<Pair<String, Int>> = listOf(
            "铺满面板" to 100,
            "宽（88%）" to 88,
            "中（75%）" to 75,
            "窄（60%）" to 60,
        )
    }
}
