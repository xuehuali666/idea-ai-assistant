package com.ye.aiassistant.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.ye.aiassistant.patch.PatchProposal
import com.ye.aiassistant.patch.Proposals
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Scrollable
import javax.swing.Timer

/** 在 EDT 上执行；已在 EDT 则直接执行。 */
fun edt(block: () -> Unit) {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) block() else app.invokeLater(block)
}

fun notifyBalloon(project: com.intellij.openapi.project.Project?, title: String, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("AI Assistant")
        .createNotification(title, content, type)
        .notify(project)
}

/** 让内容在 JScrollPane 里跟随视口宽度，高度自适应。 */
class WidthTrackingPanel(layout: LayoutManager) : JPanel(layout), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 100
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/** 只到分钟：聊天记录里秒没有意义，还占宽度。 */
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** 说话的人。角色决定配色与卡片底色，调用方不用再传颜色。 */
enum class Role(val label: String) {
    USER("你"),
    ASSISTANT("AI"),
    ;

    fun color(): java.awt.Color = when (this) {
        USER -> Theme.user()
        ASSISTANT -> Theme.accent()
    }

    fun surface(): java.awt.Color = when (this) {
        USER -> Theme.cardUser()
        ASSISTANT -> Theme.card()
    }
}

/** 一条聊天消息。流式追加时用定时器合并重绘，避免每个 token 都重排 HTML。 */
class MessageCard(
    private val role: Role = Role.ASSISTANT,
    private val showThinking: Boolean = false,
) : JPanel(BorderLayout()) {

    private val chip = RoleChip(role.label, role.color())

    /** 胶囊外面包一层 FlowLayout：直接塞进 BorderLayout.WEST 会被拉成整行高度，变成一条长条。 */
    private val chipWrap = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
    private val timeLabel = JBLabel(LocalTime.now().format(TIME_FORMAT))
    private val copyButton = ToolIconButton(AllIcons.Actions.Copy, "复制这条消息") { copyAll() }

    /** 「已带上下文」小标：让用户看得见「这条消息确实把代码一起发出去了」。 */
    private val contextBadge = JBLabel().apply { isVisible = false }
    private var contextSummary: String = ""
    private val metaRow: JPanel = Theme.row(FlowLayout.RIGHT, 6, contextBadge, copyButton, timeLabel)
    private val headerRow = JPanel(BorderLayout())
    private val body = JEditorPane("text/html", "")
    private val raw = StringBuilder()
    private val reasoning = StringBuilder()
    private val timer = Timer(90) { renderNow() }

    init {
        isOpaque = true
        // BoxLayout 按 alignmentX 摆横向位置：气泡比面板窄时靠左，不设的话会飘到中间
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        background = role.surface()
        // 左侧角色色条 + 底部发丝线，卡片之间才有分界（原来整片都是同一种灰）
        border = Theme.cardFrame(role.color())

        timeLabel.font = UIUtil.getLabelFont().deriveFont(Theme.metaSize)
        timeLabel.foreground = Theme.meta()

        chipWrap.isOpaque = false
        chipWrap.add(chip)

        headerRow.isOpaque = false
        headerRow.add(chipWrap, BorderLayout.WEST)
        headerRow.add(metaRow, BorderLayout.EAST)
        headerRow.border = JBUI.Borders.emptyBottom(Theme.pad(5))

        body.isEditable = false
        body.isOpaque = false
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        body.border = JBUI.Borders.empty()

        add(headerRow, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        renderNow()
    }

    /** 气泡宽度：设置里的百分比 × 面板可用宽度。100% 就是原来的铺满。 */
    private fun bubbleWidth(): Int {
        val avail = parent?.width?.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: 480
        return (avail * Theme.bubbleRatio).toInt().coerceAtLeast(160)
    }

    override fun getMaximumSize(): Dimension = Dimension(bubbleWidth(), super.getMaximumSize().height)

    /**
     * JEditorPane 的 preferredSize 依赖「当前宽度」，宽度还没确定时会算出一个过高的高度（卡片出现大片空白）。
     * 这里先把正文按实际可用宽度排一次，再返回高度。
     * 注意要扣掉自身 border 的内边距，否则会按偏宽的宽度去排版，正文高度算少、被裁掉。
     */
    override fun getPreferredSize(): Dimension {
        val outer = bubbleWidth()
        val inner = (outer - insets.left - insets.right).coerceAtLeast(140)
        body.setSize(inner, Short.MAX_VALUE.toInt())
        val head = headerRow.preferredSize.height + headerRow.insets.top + headerRow.insets.bottom
        val h = head + body.preferredSize.height + insets.top + insets.bottom
        return Dimension(outer, h)
    }

    /**
     * 空间不够时按「已带上下文小标 → 时间戳」的顺序依次收起次要元素。
     *
     * headerRow 是 BorderLayout（WEST 胶囊 / EAST 元信息），两边加起来超过卡片宽度时
     * BorderLayout **既不折行也不裁剪**，会直接让两层文字叠在一起 —— 弹窗里踩过同一个坑。
     * 胶囊（说话人）和复制按钮最有用，所以先牺牲小标、再牺牲时间戳。
     */
    override fun doLayout() {
        val avail = (width - insets.left - insets.right).coerceAtLeast(0)
        val gap = Theme.pad(8)
        val chipW = chipWrap.preferredSize.width
        val badgeW = if (contextSummary.isBlank()) 0 else contextBadge.preferredSize.width + Theme.pad(6)
        val copyW = copyButton.preferredSize.width + Theme.pad(6)
        val timeW = timeLabel.preferredSize.width
        contextBadge.isVisible = contextSummary.isNotBlank() && chipW + badgeW + copyW + timeW + gap <= avail
        timeLabel.isVisible = chipW + copyW + timeW + gap <= avail
        super.doLayout()
    }

    /** 设置里改了字号 / 气泡宽度 / 界面尺寸之后重新排一遍（正文内容不动）。 */
    fun applyAppearance() {
        border = Theme.cardFrame(role.color())
        headerRow.border = JBUI.Borders.emptyBottom(Theme.pad(5))
        timeLabel.font = UIUtil.getLabelFont().deriveFont(Theme.metaSize)
        contextBadge.font = UIUtil.getLabelFont().deriveFont(Theme.smallSize)
        chip.applyAppearance()
        copyButton.applyAppearance()
        renderNow()
    }

    fun setHeader(text: String) {
        edt { chip.text = text }
    }

    /**
     * 标记这条消息附带了哪些上下文（悬停看详情）。
     *
     * 显式标出来，用户才敢相信「模型是看了我的代码才回答的」——
     * 之前的上下文只带了一半（有文件名和行号、没有正文），模型只能回
     * 「请把代码粘贴出来」，用户看到的是一个自相矛盾的回复。
     */
    fun setContext(summary: String) {
        edt {
            contextSummary = summary
            contextBadge.text = "<html><font color=\"${Html.hex(Theme.accent())}\">已带上下文</font></html>"
            contextBadge.toolTipText = summary
            contextBadge.font = UIUtil.getLabelFont().deriveFont(Theme.smallSize)
            contextBadge.isVisible = summary.isNotBlank()
            revalidate()
            repaint()
        }
    }

    /** 复制这条消息的正文（不含「思考过程」）。 */
    private fun copyAll() {
        val body = synchronized(raw) { raw.toString() }
        if (body.isBlank()) return
        CopyPasteManager.getInstance().setContents(StringSelection(body))
        copyButton.toolTipText = "已复制"
        Timer(1400) { copyButton.toolTipText = "复制这条消息" }.apply { isRepeats = false; start() }
    }

    fun append(text: String) {
        synchronized(raw) { raw.append(text) }
        scheduleRender()
    }

    fun appendReasoning(text: String) {
        if (!showThinking) return
        synchronized(reasoning) { reasoning.append(text) }
        scheduleRender()
    }

    /** 工具调用 / 报错之类的日志行：用代码块渲染，保留换行。太长的截断（卡片不是日志查看器）。 */
    fun appendNote(note: String) {
        val text = note.trimEnd()
        val shown = if (text.length <= 400) text else text.take(400) + "\n…（已截断）"
        synchronized(raw) {
            raw.append("\n\n```\n").append(shown).append("\n```\n\n")
        }
        scheduleRender()
    }

    fun setText(text: String) {
        synchronized(raw) {
            raw.setLength(0)
            raw.append(text)
        }
        renderNow()
    }

    fun text(): String = synchronized(raw) { raw.toString() }

    fun flush() {
        timer.stop()
        renderNow()
    }

    private fun scheduleRender() {
        edt {
            if (!timer.isRunning) timer.start()
        }
    }

    private fun renderNow() {
        val think = synchronized(reasoning) { reasoning.toString() }
        val bodyText = synchronized(raw) { raw.toString() }
        val empty = bodyText.isBlank() && !(showThinking && think.isNotBlank())
        val html = buildString {
            if (showThinking && think.isNotBlank()) append(Html.thinking(think))
            append(Html.markdown(bodyText))
            if (empty) {
                append("<i><font color=\"${Html.hex(Theme.subtle())}\">正在生成…</font></i>")
            }
        }
        body.text = Html.page(html)
        revalidate()
        repaint()
    }
}

/** 一条待确认的 AI 改动卡片。 */
class ProposalCard(private val proposal: PatchProposal) : JPanel(BorderLayout()) {

    private val status = JBLabel("待确认")
    private val applyButton = PrimaryButton("应用")
    private val revertButton = SecondaryButton("撤销")

    /** 构造里才建好的那几个按钮，改字号时要一起重设。 */
    private lateinit var buttonFonts: List<JButton>

    /** 路径单独一行。太长要按当前宽度截断，所以留个字段好重算。 */
    private val pathLabel = JBLabel()

    /** 下面这几块的内边距/间距要跟着「界面尺寸」刷新，所以留成字段。 */
    private val pathWrap = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
    private val titleRow = toolRow(8)
    private val subRow = toolRow(8)
    private val buttonRow = JPanel(WrapLayout(Theme.pad(6), Theme.pad(5)))

    init {
        isOpaque = true
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        background = Theme.cardAccent()
        border = Theme.cardFrame(Theme.accent())

        val chipWrap = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        chipWrap.isOpaque = false
        chipWrap.add(RoleChip("改动建议", Theme.accent()))

        status.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, Theme.metaSize)
        status.foreground = Theme.warn()

        // 标题行用会折行的行：状态文本（「应用失败: …」可能很长）和胶囊挤一行时，
        // BorderLayout 的 WEST/EAST 会直接叠在一起 —— 弹窗里踩过同一个坑。
        titleRow.add(chipWrap)
        titleRow.add(status)

        // 路径太长要掐掉前段、保留文件名：Swing 的 JLabel 不会自动省略号，
        // 让它溢出会被卡片边缘硬切。完整路径放在 tooltip 里。
        pathLabel.text = pathHtml(proposal.pathLabel)
        pathLabel.toolTipText = proposal.pathLabel
        // 用一层 FlowLayout 包住，而不是靠 alignmentX：
        // BoxLayout 里 `alignmentX` 不一定说了算（这里实测被摆到了 x=102，也就是居中），
        // 而外层是个 JPanel 时它能拿到整行宽度，FlowLayout(LEFT) 就一定贴左。
        pathWrap.isOpaque = false
        pathWrap.border = JBUI.Borders.emptyTop(Theme.pad(3))
        pathWrap.add(pathLabel)

        val summary = JBLabel(
            "<html><font color=\"${Html.hex(Theme.meta())}\">${Html.escape(proposal.summary)}</font></html>",
        ).apply { font = UIUtil.getLabelFont().deriveFont(Theme.smallSize) }
        val stats = JBLabel(
            "<html><font color=\"${Html.hex(Theme.meta())}\">${proposal.changedStats()}</font></html>",
        ).apply { font = UIUtil.getLabelFont().deriveFont(Theme.smallSize) }

        subRow.border = JBUI.Borders.emptyTop(Theme.pad(3))
        subRow.add(summary)
        subRow.add(stats)

        val north = JPanel()
        north.layout = BoxLayout(north, BoxLayout.Y_AXIS)
        north.isOpaque = false
        north.add(titleRow)
        north.add(pathWrap)
        north.add(subRow)

        // 按钮**直接**放进这个 FlowLayout，不要再套一层容器：
        // 套一层的话内层会拿自己的首选宽度，外层永远不会折行，气泡一窄最后一个按钮就被裁掉。
        buttonRow.isOpaque = false
        buttonRow.border = JBUI.Borders.emptyTop(Theme.pad(5))
        val diffButton = SecondaryButton("查看 Diff")
        val rejectButton = SecondaryButton("放弃")
        revertButton.isEnabled = false
        buttonRow.add(diffButton)
        buttonRow.add(applyButton)
        buttonRow.add(revertButton)
        buttonRow.add(rejectButton)
        buttonFonts = listOf(diffButton, applyButton, revertButton, rejectButton)

        add(north, BorderLayout.NORTH)
        add(Box.createVerticalStrut(Theme.pad(8)), BorderLayout.CENTER)
        add(buttonRow, BorderLayout.SOUTH)

        diffButton.addActionListener { Proposals.showDiff(proposal) }
        applyButton.addActionListener {
            runCatching { Proposals.apply(proposal) }
                .onSuccess {
                    setStatus("已应用 ✓", Theme.ok())
                    applyButton.isEnabled = false
                    revertButton.isEnabled = true
                    notifyBalloon(
                        proposal.project,
                        "AI 已修改 ${proposal.pathLabel}",
                        "如果结果不对，可以在编辑器里 Ctrl+Z，或点卡片上的「撤销」。",
                        NotificationType.INFORMATION,
                    )
                }
                .onFailure { setStatus("应用失败: ${it.message}", Theme.danger()) }
        }
        revertButton.addActionListener {
            runCatching { Proposals.revert(proposal) }
                .onSuccess {
                    setStatus("已撤销", Theme.meta())
                    applyButton.isEnabled = true
                    revertButton.isEnabled = false
                }
                .onFailure { setStatus("撤销失败: ${it.message}", Theme.danger()) }
        }
        rejectButton.addActionListener {
            proposal.rejected = true
            setStatus("已放弃", Theme.meta())
            applyButton.isEnabled = false
            revertButton.isEnabled = false
        }
    }

    private fun setStatus(text: String, color: java.awt.Color) {
        // 状态可能是很长的错误信息；JLabel 不折行，太长会被卡片边缘硬切 —— 截断 + tooltip
        status.text = if (text.length <= 46) text else text.take(45) + "…"
        status.toolTipText = text.take(300)
        status.foreground = color
    }

    /** 气泡宽度跟着设置走，和消息卡片保持一致。 */
    private fun bubbleWidth(): Int {
        val avail = parent?.width?.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: 480
        return (avail * Theme.bubbleRatio).toInt().coerceAtLeast(160)
    }

    override fun getMaximumSize(): Dimension = Dimension(bubbleWidth(), super.getMaximumSize().height)

    override fun getPreferredSize(): Dimension =
        Dimension(bubbleWidth(), super.getPreferredSize().height)

    /** 设置里改了字号 / 气泡宽度 / 界面尺寸之后重新排一遍。 */
    fun applyAppearance() {
        border = Theme.cardFrame(Theme.accent())
        status.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, Theme.metaSize)
        buttonFonts.forEach { button ->
            when (button) {
                is SecondaryButton -> button.applyAppearance()
                is PrimaryButton -> button.applyAppearance()
                else -> button.font = UIUtil.getLabelFont().deriveFont(Theme.bodySize)
            }
        }
        // 布局的内边距、按钮行的行列间距也一起跟上
        pathWrap.border = JBUI.Borders.emptyTop(Theme.pad(3))
        subRow.border = JBUI.Borders.emptyTop(Theme.pad(3))
        buttonRow.border = JBUI.Borders.emptyTop(Theme.pad(5))
        (buttonRow.layout as? FlowLayout)?.apply { hgap = Theme.pad(6); vgap = Theme.pad(5) }
        pathLabel.text = pathHtml(proposal.pathLabel)
        revalidate()
        repaint()
    }

    /**
     * 路径只用「最后两段」：`src/main/java/com/demo/OrderService.java` → `…/demo/OrderService.java`。
     * 不按像素宽度猜能放几个字符 —— 面板真实宽度要等布局才知道，猜小了白白截掉，
     * 猜大了会被卡片边缘硬切半个字。留最后两段既够定位文件，又任何宽度都放得下。
     * 完整路径在 tooltip 里。
     */
    private fun pathHtml(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val shown = if (parts.size <= 2) path else "…/" + parts.takeLast(2).joinToString("/")
        return "<html><font color=\"${Html.hex(Theme.text())}\">${Html.escape(shown)}</font></html>"
    }
}

/** 输入框：Enter 发送，Shift+Enter 换行。 */
class ChatInputArea : JTextArea() {

    /** 空的时候显示的灰字提示。JTextArea 没有原生 placeholder，自己画。 */
    var placeholder: String = ""

    init {
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        foreground = Theme.text()
        applyAppearance()
    }

    /** 字号 / 块大小变了要重设字体、内边距和默认高度，顺手重画占位提示。 */
    fun applyAppearance() {
        font = UIUtil.getLabelFont().deriveFont(Theme.bodySize)
        border = JBUI.Borders.empty(Theme.pad(6), Theme.pad(8))
        // 输入区高度也跟着块大小走：紧凑时 2 行就够，宽松时给 4 行
        rows = (3 * Theme.density).toInt().coerceIn(2, 5)
        repaint()
    }

    /**
     * 输入法是否正在组字。
     *
     * 组字中的回车是「确认候选词」，不能当成发送 —— 中文用户几乎每次打字都会踩到。
     * 判据：文档里有没有带 [StyleConstants.ComposedTextAttribute] 的文本段
     * （Swing 用它标记组字区，是公开 API）。
     */
    fun isComposing(): Boolean = composingAt(document.defaultRootElement)

    private fun composingAt(el: javax.swing.text.Element): Boolean {
        if (el.isLeaf) {
            return el.attributes.isDefined(javax.swing.text.StyleConstants.ComposedTextAttribute)
        }
        for (i in 0 until el.elementCount) {
            if (composingAt(el.getElement(i))) return true
        }
        return false
    }

    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        if (text.isNotEmpty() || placeholder.isEmpty()) return
        val g2 = g.create() as java.awt.Graphics2D
        g2.color = Theme.subtle()
        g2.font = font
        val fm = g2.fontMetrics
        g2.drawString(placeholder, insets.left, insets.top + fm.ascent)
        g2.dispose()
    }
}

fun verticalBox(): JPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

/**
 * 顶部工具条里的一行。
 *
 * 用 [WrapLayout] 而不是平台的 FlowLayout：后者折行时不会把多出来的行算进首选高度，
 * 折下去的那一行会被父容器裁掉 —— 「带上下文」那个勾选框就是这么消失的
 * （面板 334px、字号 11 时它刚好放不下，于是折到第二行、然后被切掉，谁也看不见）。
 */
fun toolRow(gap: Int): JPanel = JPanel(WrapLayout(Theme.pad(gap), 0)).apply {
    isOpaque = false
    Theme.registerSpacing(this, gap)
}

/** 和 [toolRow] 同理，但用平台 FlowLayout（不折行）—— 用在只有两三个固定控件的行上。 */
fun fixedRow(align: Int, gap: Int, vararg comps: JComponent): JPanel =
    Theme.row(align, gap, *comps)

/** 工具条上的次要按钮：统一走 SecondaryButton，字号跟着正文走。 */
fun smallButton(text: String, action: () -> Unit): SecondaryButton = SecondaryButton(text).apply {
    addActionListener { action() }
}
