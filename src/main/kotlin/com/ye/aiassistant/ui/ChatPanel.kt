package com.ye.aiassistant.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.ye.aiassistant.agent.ContextCollector
import com.ye.aiassistant.agent.Prompts
import com.ye.aiassistant.agent.ToolContext
import com.ye.aiassistant.agent.ToolRegistry
import com.ye.aiassistant.errors.ErrorItem
import com.ye.aiassistant.llm.CancelToken
import com.ye.aiassistant.llm.ChatMsg
import com.ye.aiassistant.llm.LlmCancelledException
import com.ye.aiassistant.llm.LlmClient
import com.ye.aiassistant.llm.LlmListener
import com.ye.aiassistant.llm.StreamResult
import com.ye.aiassistant.patch.FilePatch
import com.ye.aiassistant.patch.FixGuard
import com.ye.aiassistant.patch.PatchPaths
import com.ye.aiassistant.patch.PatchProposal
import com.ye.aiassistant.patch.ProposalService
import com.ye.aiassistant.patch.Proposals
import com.ye.aiassistant.patch.UnifiedDiff
import com.ye.aiassistant.qna.Qna
import com.ye.aiassistant.settings.AiSettings
import com.ye.aiassistant.settings.ChatMode
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/** 没开始跑任务时状态栏显示什么：顺手把两个快捷键告诉用户，省得去翻 README。 */
private const val HINT_IDLE = "Enter 发送 · Shift+Enter 换行 · Esc 停止"

/** 等 EDT 执行的上限：主线程万一被卡住，后台线程不能永久挂着（Tools.kt 里同款）。 */
private const val EDT_WAIT_SECONDS = 60L

/**
 * AI 助手主面板：聊天（Agent / 问答）、报错修复、Q&A 抽取、改动确认卡片。
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val cfg: AiSettings.State get() = AiSettings.get()

    private val messagesBox = verticalBox()
    private val scrollView = WidthTrackingPanel(BorderLayout()).apply {
        add(messagesBox, BorderLayout.NORTH)
        // 留白放在这一层：它也是滚动视口的 view，messagesBox 的宽度会跟着缩，
        // 卡片拿 parent.width 去排版时才对得上（放在 messagesBox 上会宽出去一圈）。
        border = listPadding()
    }
    private val scroll = JBScrollPane(scrollView).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 16
        border = JBUI.Borders.empty()
        verticalScrollBar.addAdjustmentListener {
            val bar = verticalScrollBar
            stickToBottom = bar.value >= bar.maximum - bar.visibleAmount - JBUI.scale(48)
        }
    }
    private var emptyState = buildEmptyState()
    private val input = ChatInputArea()

    /** 工具条上的文字按钮，改字号/块大小时要一起重设。 */
    private val actionButtons = mutableListOf<SecondaryButton>()

    /** 生成过程中要禁掉「新对话」，避免清掉正在写的东西。 */
    private lateinit var newChatButton: ToolIconButton

    /** 工具条右上角的两个图标按钮，改「界面尺寸」时要重新留点击区域。 */
    private lateinit var settingsButton: ToolIconButton

    /** 输入井：改「界面尺寸」时要重设描边圆角。 */
    private lateinit var well: JPanel

    /**
     * 要不要跟着新消息滚到底。
     * 只有「本来就贴在底部」时才自动滚 —— 用户正在往上翻历史时来一条新消息，
     * 把他直接拽到底部是很烦人的。
     */
    @Volatile
    private var stickToBottom = true
    private val sendButton = PrimaryButton("发送")
    private val stopButton = SecondaryButton("停止")

    /** 上一轮没答好 / 出错 / 中途停止时，把那一轮整块撤掉重跑。 */
    private lateinit var retryButton: SecondaryButton
    private val modeCombo = JComboBox(arrayOf(ChatMode.ASK.label, ChatMode.AGENT.label))
    private val minimalCheck = JCheckBox("最小修改", AiSettings.get().minimalFix)
    private val contextCheck = JCheckBox("带上下文", AiSettings.get().attachContext)
    private val statusLabel = JBLabel(HINT_IDLE)

    private val history = ArrayList<ChatMsg>()

    /**
     * 一轮对话（用户一条消息 + 模型的回答 / 工具轮次）。
     * 记下它画出来的每一个组件和 history 的起点，「重新生成」时整块撤掉重跑。
     */
    private class Turn(
        val displayText: String,
        val payload: String,
        val contextSummary: String = "",
        val run: (CancelToken) -> Unit,
    ) {
        val components = mutableListOf<JComponent>()
        var historyMark = 0
    }

    @Volatile
    private var currentTurn: Turn? = null

    @Volatile
    private var lastTurn: Turn? = null

    /** 本轮已经生成过改动提案的文件，避免工具和回复正文重复出卡片 */
    private val proposedPaths: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val proposalService = ProposalService.getInstance(project)
    private val proposalListener: (PatchProposal) -> Unit = { onProposal(it) }

    @Volatile
    private var cancelToken: CancelToken? = null

    @Volatile
    private var busy = false

    @Volatile
    private var currentCard: MessageCard? = null

    /** 工具条和输入区都做成字段：改「界面尺寸」时要重设它们的内边距。 */
    private val toolbar = buildToolbar()
    private val inputPanel = buildInputPanel()

    private fun listPadding(): javax.swing.border.Border =
        JBUI.Borders.empty(Theme.pad(9), Theme.gutter, Theme.pad(10), Theme.gutter)

    private fun toolbarPadding(): javax.swing.border.Border =
        javax.swing.BorderFactory.createCompoundBorder(
            BottomLineBorder(Theme.hairline()),
            JBUI.Borders.empty(Theme.pad(7), Theme.gutter, Theme.pad(8), Theme.gutter),
        )

    private fun inputPadding(): javax.swing.border.Border =
        javax.swing.BorderFactory.createCompoundBorder(
            TopLineBorder(Theme.hairline()),
            JBUI.Borders.empty(Theme.pad(9), Theme.gutter, Theme.pad(8), Theme.gutter),
        )

    init {
        isOpaque = true
        background = Theme.surface()
        border = JBUI.Borders.empty()
        add(toolbar, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)
        messagesBox.add(emptyState)
        proposalService.addListener(proposalListener)
        setBusy(false)
    }

    // ---------- UI ----------

    /**
     * 第一次打开时的空状态。原来这里是一整片空白，用户不知道该干嘛。
     *
     * 每一行都写成短句、左对齐：FlowLayout 遇到比容器还宽的子树会把两侧一起裁掉，
     * 所以这里不用居中、也不让任何一行超过 ~260px。
     */
    private fun buildEmptyState(): JComponent {
        val lines = JPanel()
        lines.layout = BoxLayout(lines, BoxLayout.Y_AXIS)
        lines.isOpaque = false

        // 显式给字号：这里原来用默认字体，改了「对话字体大小」也不会跟着变
        val bodyFont = UIUtil.getLabelFont().deriveFont(Theme.bodySize)
        val metaFont = UIUtil.getLabelFont().deriveFont(Theme.smallSize)

        fun line(html: String, font: java.awt.Font): JBLabel = JBLabel(html).apply {
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            this.font = font
            lines.add(this)
        }

        line("<html><b><font color=\"${Html.hex(Theme.text())}\">开始一段对话</font></b></html>", bodyFont)
        lines.add(Box.createVerticalStrut(Theme.pad(10)))
        val meta = Html.hex(Theme.meta())
        line("<html><font color=\"$meta\">问答模式：只看当前文件（发送时自动带上内容）。</font></html>", metaFont)
        line("<html><font color=\"$meta\">Agent 模式：可主动搜代码、生成改动。</font></html>", metaFont)
        lines.add(Box.createVerticalStrut(Theme.pad(12)))
        val key = Html.hex(Theme.accent())
        line("<html><b><font color=\"$key\">Alt + Shift + E</font></b><font color=\"$meta\">&nbsp;&nbsp;修复当前文件的报错</font></html>", bodyFont)
        line("<html><b><font color=\"$key\">Alt + Shift + A</font></b><font color=\"$meta\">&nbsp;&nbsp;打开并聚焦这个面板</font></html>", bodyFont)

        val wrap = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        wrap.isOpaque = false
        // 左边距对齐消息卡片里的正文（卡片左边是 gutter + 3px 色条 + 11px 内边距）
        wrap.border = JBUI.Borders.empty(Theme.pad(30), Theme.gutter + Theme.pad(12), 0, Theme.gutter)
        wrap.add(lines)
        return wrap
    }

    private fun styledCaption(text: String): JBLabel = JBLabel(text).apply {
        font = UIUtil.getLabelFont().deriveFont(Theme.metaSize)
        foreground = Theme.meta()
    }

    /**
     * 顶部工具条。拆成两行：右侧工具窗口一般只有 400px 左右宽，
     * 七个控件挤一行必然折行，折在哪完全看窗口宽度 —— 看起来像没排过版。
     */
    private fun buildToolbar(): JComponent {
        val root = JPanel(BorderLayout())
        root.isOpaque = true
        root.background = Theme.bar()
        root.border = toolbarPadding()

        val controlFont = UIUtil.getLabelFont().deriveFont(Theme.bodySize)
        modeCombo.font = controlFont
        minimalCheck.font = controlFont
        contextCheck.font = controlFont
        modeCombo.selectedIndex = if (ChatMode.from(cfg.mode) == ChatMode.AGENT) 1 else 0
        modeCombo.toolTipText = "问答模式：只看当前文件（发送时自动附带内容）；Agent 模式：可主动读文件、搜代码、生成改动"
        modeCombo.addActionListener { cfg.mode = selectedMode().name }
        minimalCheck.toolTipText = "只改报错相关的行，禁止重构/格式化/大范围改动"
        minimalCheck.addActionListener { cfg.minimalFix = minimalCheck.isSelected }
        contextCheck.toolTipText = "发送时自动带上当前文件的内容、光标位置、选中片段和报错"
        contextCheck.addActionListener { cfg.attachContext = contextCheck.isSelected }

        val configRow = toolRow(8)
        configRow.add(styledCaption("模式"))
        configRow.add(modeCombo)
        configRow.add(minimalCheck)
        configRow.add(contextCheck)

        // 右侧两个图标按钮：设置 + 新对话。都不常点，用图标比占一整格文字按钮省地方，
        // 也更像工具窗口该有的样子（都带 tooltip）。
        val icons = toolRow(2)
        settingsButton = ToolIconButton(AllIcons.General.Settings, "设置") { openSettings(project) }
        icons.add(settingsButton)
        newChatButton = ToolIconButton(AllIcons.General.Add, "新对话（清空当前对话）") { newSession() }
        icons.add(newChatButton)

        val topRow = JPanel(BorderLayout())
        topRow.isOpaque = false
        topRow.add(configRow, BorderLayout.CENTER)
        topRow.add(icons, BorderLayout.EAST)

        val actionRow = toolRow(6)
        actionRow.border = JBUI.Borders.emptyTop(Theme.pad(6))
        actionButtons += smallButton("修复报错") { FixFlow.fixCurrentFile(project) }.apply {
            toolTipText = "收集当前文件的报错，交给 AI 生成最小补丁"
        }
        actionButtons += smallButton("Q&A 抽取") { startQna() }.apply {
            toolTipText = "从最后一条 AI 回复里抽出待决策问题"
        }
        actionButtons.forEach { actionRow.add(it) }

        val rows = JPanel(BorderLayout())
        rows.isOpaque = false
        rows.add(topRow, BorderLayout.NORTH)
        rows.add(actionRow, BorderLayout.SOUTH)
        root.add(rows, BorderLayout.CENTER)
        return root
    }

    private fun buildInputPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.isOpaque = true
        root.background = Theme.surface()
        root.border = inputPadding()

        // 输入井：一圈描边 + 独立底色，一眼能看出「这里可以打字」
        well = JPanel(BorderLayout())
        well.isOpaque = true
        well.background = Theme.well()
        well.border = wellBorder()
        // 聚焦时描边变主色：不用额外画东西，就有一个明确的"现在在输入"状态
        input.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) = well.repaint()
            override fun focusLost(e: java.awt.event.FocusEvent) = well.repaint()
        })
        well.add(
            JBScrollPane(input).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            },
            BorderLayout.CENTER,
        )

        input.placeholder = "输入问题…"
        // 焦点变化时重画描边（边框是 lambda 取色的，repaint 就够）
        sendButton.addActionListener { send() }
        stopButton.addActionListener { cancelToken?.cancel() }
        retryButton = SecondaryButton("重新生成").apply {
            toolTipText = "把上一轮撤掉、原样重跑（回答得不好 / 出错 / 中途停止之后用）"
            addActionListener { retryLast() }
        }
        val actions = fixedRow(FlowLayout.RIGHT, 6, retryButton, stopButton, sendButton)

        statusLabel.font = UIUtil.getLabelFont().deriveFont(Theme.smallSize)
        statusLabel.foreground = Theme.meta()
        val footer = JPanel(BorderLayout())
        footer.isOpaque = false
        footer.border = JBUI.Borders.emptyTop(Theme.pad(7))
        footer.add(statusLabel, BorderLayout.CENTER)
        footer.add(actions, BorderLayout.EAST)

        root.add(well, BorderLayout.CENTER)
        root.add(footer, BorderLayout.SOUTH)

        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ai-send")
        input.actionMap.put("ai-send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                // 中文输入法组字中的回车是「确认候选词」，不能当成发送 —— 否则每次打中文都会误发
                if (!input.isComposing()) send()
            }
        })
        // Esc：正在生成时停止。绑在 WHEN_IN_FOCUSED_WINDOW 上，焦点不在输入框也能按
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "ai-stop")
        actionMap.put("ai-stop", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (busy) cancelToken?.cancel()
            }
        })
        return root
    }

    /** 输入井的描边：圆角也跟着「界面尺寸」走。 */
    private fun wellBorder(): javax.swing.border.Border =
        javax.swing.BorderFactory.createCompoundBorder(
            OutlineBorder({ if (input.hasFocus()) Theme.accent() else Theme.wellBorder() }, Theme.pad(6)),
            JBUI.Borders.empty(Theme.pad(2)),
        )

    fun focusInput() {
        input.requestFocusInWindow()
    }

    fun selectedMode(): ChatMode = if (modeCombo.selectedIndex == 1) ChatMode.AGENT else ChatMode.ASK

    private fun setBusy(value: Boolean) {
        busy = value
        sendButton.isEnabled = !value
        stopButton.isEnabled = value
        input.isEnabled = !value
        // 生成过程中把「新对话 / 修复报错 / Q&A 抽取」一起禁掉：
        // 比让用户点了之后弹「正在处理上一个请求」的提示要好
        if (::newChatButton.isInitialized) newChatButton.isEnabled = !value
        actionButtons.forEach { it.isEnabled = !value }
        if (::retryButton.isInitialized) retryButton.isEnabled = !value && lastTurn != null
    }

    private fun addCard(card: JComponent) {
        // 有第一条消息了，把空状态撤掉
        if (emptyState.parent === messagesBox) messagesBox.remove(emptyState)
        val hasContent = messagesBox.componentCount > 0
        // 卡片之间插一个独立的 strut，才是真的缝；靠卡片自己的 padding 留白会被卡片底色的填充盖掉
        if (hasContent) {
            val strut = GapStrut()
            messagesBox.add(strut)
            currentTurn?.components?.add(strut)
        }
        messagesBox.add(card)
        currentTurn?.components?.add(card)
        messagesBox.revalidate()
        messagesBox.repaint()
        SwingUtilities.invokeLater {
            if (stickToBottom) scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
        }
    }

    private fun addUserCard(text: String): MessageCard {
        val card = MessageCard(Role.USER)
        card.setText(text)
        addCard(card)
        return card
    }

    private fun <T> onEdtAndWait(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) return block()
        var result: T? = null
        val latch = CountDownLatch(1)
        app.invokeLater {
            try {
                result = block()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(EDT_WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("IDE 主线程繁忙，界面更新超时（${EDT_WAIT_SECONDS}s）")
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // ---------- 对话 ----------

    fun newSession() {
        if (busy) {
            notifyBalloon(project, "AI 助手", "正在生成中，先点「停止」再开新对话", NotificationType.WARNING)
            return
        }
        synchronized(history) { history.clear() }
        currentTurn = null
        lastTurn = null
        messagesBox.removeAll()
        messagesBox.add(emptyState)
        messagesBox.revalidate()
        messagesBox.repaint()
        stickToBottom = true
        setStatus("已开始新对话")
    }

    fun send() {
        if (busy) return
        val text = input.text.trim()
        if (text.isEmpty()) return
        if (!AiSettings.isConfigured()) {
            notifyBalloon(project, "AI 助手", "还没有配置 API Key：Settings > Tools > AI Assistant", NotificationType.WARNING)
            return
        }
        input.text = ""
        val context = if (contextCheck.isSelected) ContextCollector.build(project) else ContextCollector.Result.EMPTY
        val payload = if (context.isEmpty) text else "${context.text}\n\n---\n\n$text"
        beginTurn(
            Turn(text, payload, if (context.isEmpty) "" else context.summary) { token ->
                val result = runAgentLoop(token, emptyList(), null)
                proposeFromText(result.text)
            },
        )
    }

    /** 开一轮对话：记 history 起点、画用户消息、把整轮跑起来。「重新生成」也走这里。 */
    private fun beginTurn(turn: Turn, status: String = "生成中… · ${cfg.model}") {
        currentTurn = turn
        turn.components.clear()
        turn.historyMark = synchronized(history) { history.size }
        val card = addUserCard(turn.displayText)
        // 把「带了什么」显式标在这条消息上：用户才知道模型是看着代码回答的
        if (turn.contextSummary.isNotBlank()) card.setContext(turn.contextSummary)
        synchronized(history) { history += ChatMsg.user(turn.payload) }
        proposedPaths.clear()
        lastTurn = turn
        startBackground(status) { token -> turn.run(token) }
    }

    /**
     * 重新生成上一轮：把上一轮画出来的所有东西（含卡片间的缝隙）整块撤掉、
     * history 回退到那一轮开始之前，再原样重跑。「停止」「出错」「答得不好」之后都用得上。
     */
    fun retryLast() {
        if (busy) return
        val turn = lastTurn ?: return
        if (!AiSettings.isConfigured()) {
            notifyBalloon(project, "AI 助手", "还没有配置 API Key：Settings > Tools > AI Assistant", NotificationType.WARNING)
            return
        }
        turn.components.forEach { messagesBox.remove(it) }
        synchronized(history) {
            while (history.size > turn.historyMark) history.removeAt(history.size - 1)
        }
        if (messagesBox.componentCount == 0) messagesBox.add(emptyState)
        messagesBox.revalidate()
        messagesBox.repaint()
        beginTurn(turn)
    }

    /** 状态栏文字。太长会被按钮挤着截断，全文挂 tooltip。 */
    private fun setStatus(text: String) {
        statusLabel.text = text
        statusLabel.toolTipText = text
    }

    private fun startBackground(status: String, block: (CancelToken) -> Unit) {
        if (busy) {
            notifyBalloon(project, "AI 助手", "正在处理上一个请求，请稍候", NotificationType.WARNING)
            return
        }
        val token = CancelToken()
        cancelToken = token
        setBusy(true)
        setStatus(status)
        val startedAt = System.currentTimeMillis()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                block(token)
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
                edt { setStatus("完成 · %.1fs".format(elapsed)) }
            } catch (e: LlmCancelledException) {
                // 停止也报耗时：用户想知道「跑到一半停掉浪费了多久」
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
                edt { setStatus("已停止 · %.1fs".format(elapsed)) }
            } catch (e: Exception) {
                edt {
                    setStatus("出错")
                    currentCard?.appendNote("出错: ${e.message}")
                    notifyBalloon(project, "AI 助手出错", e.message ?: e.javaClass.simpleName, NotificationType.ERROR)
                }
            } finally {
                edt {
                    // 中途停止 / 出错时流没跑完，卡片的渲染定时器可能还挂着，停掉
                    currentCard?.flush()
                    setBusy(false)
                    cancelToken = null
                    if (::retryButton.isInitialized) retryButton.isEnabled = lastTurn != null
                }
            }
        }
    }

    /** 一轮对话：可能包含多次「模型 -> 工具 -> 模型」。 */
    private fun runAgentLoop(token: CancelToken, extra: List<ChatMsg>, systemOverride: String?): StreamResult {
        val mode = selectedMode()
        val minimal = minimalCheck.isSelected
        val client = LlmClient(cfg)
        val useTools = systemOverride != null || mode == ChatMode.AGENT
        val registry = if (useTools) {
            ToolRegistry(ToolContext(project, minimal) { onProposal(it) }, cfg.autoApplyEdits)
        } else {
            null
        }
        val system = systemOverride
            ?: if (mode == ChatMode.AGENT) Prompts.agentSystem(minimal) else Prompts.askSystem()

        synchronized(history) {
            if (history.isNotEmpty() && history.first().role == "system") {
                history[0] = ChatMsg.system(system)
            } else {
                history.add(0, ChatMsg.system(system))
            }
            history.addAll(extra)
        }

        // 一轮对话一张卡：之前每个模型调用都另起新卡，一次带工具的回答会连出两三张
        // 「AI」卡，中间还夹着只有工具日志的空卡，看起来像 AI 回答了好几次。
        val card = onEdtAndWait {
            MessageCard(Role.ASSISTANT, cfg.showThinking).also { addCard(it) }
        }
        currentCard = card

        var last = StreamResult("", "", emptyList(), "")
        var step = 0
        val maxSteps = cfg.maxAgentSteps.coerceAtLeast(1)
        while (step < maxSteps) {
            token.throwIfCancelled()
            if (step > 0) card.append("\n\n")
            val snapshot = synchronized(history) { history.toList() }
            val result = client.stream(snapshot, registry?.specs().orEmpty(), token, object : LlmListener {
                override fun onText(delta: String) = card.append(delta)
                override fun onReasoning(delta: String) = card.appendReasoning(delta)
            })
            synchronized(history) {
                history += ChatMsg("assistant", result.text, result.reasoning, result.toolCalls)
            }
            card.flush()
            last = result
            if (result.toolCalls.isEmpty() || registry == null) break
            for (call in result.toolCalls) {
                card.appendNote("调用工具 ${call.name} …")
                val output = registry.execute(call)
                card.appendNote("${call.name} 结果: ${output.replace("\n", " ").take(240)}")
                synchronized(history) { history += ChatMsg.tool(call.id, call.name, output) }
            }
            step++
        }
        return last
    }

    // ---------- 报错修复 ----------

    fun startFix(file: VirtualFile, errors: List<ErrorItem>) {
        if (busy) {
            notifyBalloon(project, "AI 助手", "正在处理上一个请求，请稍候", NotificationType.WARNING)
            return
        }
        if (!AiSettings.isConfigured()) {
            notifyBalloon(project, "AI 助手", "还没有配置 API Key：Settings > Tools > AI Assistant", NotificationType.WARNING)
            return
        }
        val path = PatchPaths.relative(project, file)
        val minimal = minimalCheck.isSelected
        val language = file.extension ?: "text"
        val code = Proposals.readText(file)
        val errorLines = errors.map { error ->
            val range = if (error.endLine != error.startLine) "${error.startLine}-${error.endLine}" else "${error.startLine}"
            "行 $range: ${error.description}（当前代码: ${error.text}）"
        }
        val prompt = Prompts.fixUserPrompt(path, language, errorLines, code, minimal)
        beginTurn(
            Turn("修复 ${errors.size} 个报错：$path", prompt) { token ->
                runFixTurn(token, prompt, path, errors, minimal)
            },
            status = "正在分析报错… · ${cfg.model}",
        )
    }

    /** 修复报错的一轮：超出最小修改范围时自动让模型重试一次。 */
    private fun runFixTurn(token: CancelToken, prompt: String, path: String, errors: List<ErrorItem>, minimal: Boolean) {
            var result = runAgentLoop(token, listOf(ChatMsg.user(prompt)), Prompts.fixSystem(minimal))
            var patches = UnifiedDiff.extract(result.text)
            if (patches.isNotEmpty() && minimal) {
                val guard = FixGuard.validate(patches, setOf(path), errors, cfg.contextLines, cfg.maxFixLines)
                if (!guard.ok) {
                    edt { currentCard?.appendNote("改动超出最小修改范围，让模型重试一次…") }
                    val feedback = buildString {
                        appendLine("上一次的补丁不符合最小修改模式，违规项：")
                        appendLine(guard.message())
                        appendLine("请只改动报错所在行附近（上下 ${cfg.contextLines} 行内），总改动不超过 ${cfg.maxFixLines} 行，重新给出补丁。")
                    }
                    result = runAgentLoop(token, listOf(ChatMsg.user(feedback)), Prompts.fixSystem(minimal))
                    patches = UnifiedDiff.extract(result.text)
                    if (patches.isNotEmpty()) {
                        val second = FixGuard.validate(patches, setOf(path), errors, cfg.contextLines, cfg.maxFixLines)
                        if (!second.ok) {
                            edt {
                                notifyBalloon(
                                    project,
                                    "最小修改模式：已放弃",
                                    "模型两次都给出了过大的改动，未生成任何修改。\n${second.message()}",
                                    NotificationType.WARNING,
                                )
                            }
                            return
                        }
                    }
                }
            }
            if (patches.isNotEmpty()) {
                createProposals(patches, "修复报错：$path")
            }
    }

    /**
     * 模型在正文里直接给了 diff（没有走 propose_edit 工具）时，也生成可确认的改动提案，
     * 这样用户能拿到 IDEA 原生的红绿 Diff 窗口，而不是只能看一段纯文本。
     */
    private fun proposeFromText(text: String) {
        val patches = UnifiedDiff.extract(text).filter { it.path.isNotBlank() && it.path !in proposedPaths }
        if (patches.isEmpty()) return
        if (minimalCheck.isSelected) {
            val guard = FixGuard.validateSize(patches, cfg.maxFixLines)
            if (!guard.ok) {
                edt { currentCard?.appendNote("提示：${guard.message()}，仍按原样给出改动，请自行确认") }
            }
        }
        createProposals(patches, "AI 建议的改动")
    }

    private fun createProposals(patches: List<FilePatch>, summary: String) {
        for (patch in patches) {
            try {
                val proposal = onEdtAndWait { Proposals.fromPatch(project, patch, summary) }
                proposedPaths += proposal.pathLabel
                onProposal(proposal)
            } catch (e: Exception) {
                edt {
                    notifyBalloon(project, "无法生成改动", "${patch.path}: ${e.message}", NotificationType.ERROR)
                }
            }
        }
    }

    private fun onProposal(proposal: PatchProposal) {
        edt {
            addCard(ProposalCard(proposal))
            if (cfg.autoOpenDiff) {
                runCatching { Proposals.showDiff(proposal) }
            }
        }
    }

    // ---------- Q&A 抽取 ----------

    fun startQna() {
        if (busy) {
            notifyBalloon(project, "AI 助手", "正在处理上一个请求，请稍候", NotificationType.WARNING)
            return
        }
        val lastAssistant = synchronized(history) {
            history.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
        }
        if (lastAssistant == null) {
            notifyBalloon(project, "AI 助手", "还没有可抽取问题的 AI 回复", NotificationType.INFORMATION)
            return
        }
        startBackground("正在抽取问题…") {
            val client = LlmClient(cfg)
            val result = client.complete(
                listOf(ChatMsg.system(Prompts.qnaSystem()), ChatMsg.user(lastAssistant.content)),
                maxTokens = 2000,
            )
            val questions = Qna.parse(result.text)
            if (questions.isEmpty()) {
                edt { notifyBalloon(project, "AI 助手", "这条回复里没有需要你回答的问题", NotificationType.INFORMATION) }
                return@startBackground
            }
            edt {
                val dialog = QnaDialog(project, questions)
                if (dialog.showAndGet()) {
                    input.text = Qna.summary(dialog.answers)
                    input.requestFocusInWindow()
                }
            }
        }
    }

    /**
     * 设置里改了「对话字体大小 / 界面尺寸 / 气泡宽度」之后，把已经画出来的东西按新尺寸重排一遍。
     * 由 AiConfigurable.apply() 直接调用（只有一个消费者，不必上消息总线）。
     */
    fun refreshAppearance() {
        val label = UIUtil.getLabelFont()
        modeCombo.font = label.deriveFont(Theme.bodySize)
        minimalCheck.font = label.deriveFont(Theme.bodySize)
        contextCheck.font = label.deriveFont(Theme.bodySize)
        statusLabel.font = label.deriveFont(Theme.smallSize)
        actionButtons.forEach { it.applyAppearance() }
        sendButton.applyAppearance()
        stopButton.applyAppearance()
        if (::newChatButton.isInitialized) newChatButton.applyAppearance()
        if (::settingsButton.isInitialized) settingsButton.applyAppearance()

        // 容器的内边距 / 行间距 / 圆角也要跟着「界面尺寸」走
        Theme.refreshSpacing()
        toolbar.border = toolbarPadding()
        inputPanel.border = inputPadding()
        scrollView.border = listPadding()
        if (::well.isInitialized) well.border = wellBorder()

        input.applyAppearance()
        // 空状态是构造时画好的，不是 MessageCard，走不到下面的分支 —— 字号 / 块大小变了要整个重建
        if (emptyState.parent === messagesBox) {
            messagesBox.remove(emptyState)
            emptyState = buildEmptyState()
            messagesBox.add(emptyState, 0)
        }
        messagesBox.components.forEach { child ->
            when (child) {
                is MessageCard -> child.applyAppearance()
                is ProposalCard -> child.applyAppearance()
            }
        }
        revalidate()
        repaint()
    }

    // ---------- 本地视觉预览 ----------

    /**
     * 不联网、没有 API Key 也要能看排版：塞几条有代表性的消息
     * （用户消息 / AI 正文 + 代码块 + diff / 改动提案卡片）。
     * 只在 `-Dai.assistant.demo=true` 时由 AutoTestStartup 调用，正常使用不会走到这里。
     */
    fun seedPreviewForScreenshot() {
        val user = addUserCard("OrderService 里这段报错了，帮我修掉，别动别的逻辑。")
        user.setContext("OrderService.java 第 42 行 · 1 条报错")

        val ai = MessageCard(Role.ASSISTANT, showThinking = true)
        addCard(ai)
        ai.appendReasoning("先确认 findById 的返回类型：是 Optional，这里直接 .get() 了。")
        // 工具轮次和最终回答合并在同一张卡里（真实 Agent 回答就是这个样子）
        ai.appendNote("调用工具 read_file(src/main/java/com/demo/OrderService.java) …")
        ai.appendNote("read_file 结果: 已读取 64 行")
        ai.append("\n\n")
        ai.append(
            """
            ## 诊断

            `OrderService.java:42` 的 `customer` 可能为 null：`findById` 返回 Optional，
            这里直接 `.get()`，缓存未命中时会抛 `NoSuchElementException`。

            **改动点**

            - 换成 `orElseThrow`，异常信息里带上订单号
            - 只动这两行，不碰其它逻辑

            ```java
            Customer customer = customerRepository.findById(order.getCustomerId())
                    .orElseThrow(() -> new IllegalStateException("customer not found"));
            ```

            ```diff
            @@ -39,6 +39,7 @@ public String describe(Order order) {
            -    Customer customer = customerRepository.findById(order.getCustomerId()).get();
            +    Customer customer = customerRepository.findById(order.getCustomerId())
            +            .orElseThrow(() -> new IllegalStateException("customer not found"));
                 return customer.getName();
            ```
            """.trimIndent(),
        )
        ai.flush()

        runCatching {
            Proposals.fromContent(
                project = project,
                file = null,
                pathLabel = "src/main/java/com/demo/OrderService.java",
                original = "class OrderService {}\n",
                updated = "class OrderService { /* patched */ }\n",
                summary = "修复报错：OrderService.java",
                added = 3,
                removed = 1,
            )
        }.onSuccess { addCard(ProposalCard(it)) }

        setStatus("预览：本地视觉验证（没有调用模型）")
    }

    override fun dispose() {
        proposalService.removeListener(proposalListener)
        cancelToken?.cancel()
    }
}
