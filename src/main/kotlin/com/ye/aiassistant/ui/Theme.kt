package com.ye.aiassistant.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.ye.aiassistant.settings.AiSettings
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.AbstractBorder
import kotlin.math.roundToInt
import javax.swing.border.Border

/**
 * 面板的视觉令牌 —— 颜色 / 间距 / 字号都从这里取。
 *
 * 两条原则：
 * 1. **表面分三层**：输入区比面板暗、卡片比面板亮，每层差一点点亮度，卡片才浮得起来。
 *    原来卡片和面板取的是同一个色（`UIUtil.getPanelBackground()`），整片糊成一块灰。
 * 2. **正文比 IDE 界面字号小一号**：工具窗口普遍只有 400px 宽，正文用界面字号（一般 13）
 *    在窄面板里显得又大又挤；12 号才有聊天记录该有的密度。
 */
object Theme {

    // ── 表面（深色以 Islands Dark 的基底 #2B2D30 为起点）─────

    /** 工具窗口主体底色。 */
    fun surface(): Color = JBColor(Color(0xF7, 0xF8, 0xFA), Color(0x2B, 0x2D, 0x30))

    /** 顶部工具条：比面板**暗**一档，读起来像一条 header 带，不会和正文连成一片。 */
    fun bar(): Color = JBColor(Color(0xEC, 0xEE, 0xF2), Color(0x25, 0x27, 0x2A))

    /** AI 消息卡片：比面板亮一档（中性）。 */
    fun card(): Color = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x31, 0x34, 0x38))

    /** 「你」的消息：往角色色（绿）偏一点点 —— 三张卡的色相各自对应自己的角色。 */
    fun cardUser(): Color = JBColor(Color(0xEF, 0xF6, 0xF1), Color(0x2B, 0x33, 0x30))

    /** 改动提案卡片：往主色（蓝）偏，它是等你动手的东西，存在感最强。 */
    fun cardAccent(): Color = JBColor(Color(0xEE, 0xF4, 0xFD), Color(0x2A, 0x32, 0x3F))

    /** 输入区：比面板暗一档，做成一个「凹槽」。 */
    fun well(): Color = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x22, 0x24, 0x27))

    /** 思考过程的底：比卡片再暗/深一档，读起来像"引用的内部推理"。 */
    fun thinkBlock(): Color = JBColor(Color(0xF2, 0xF4, 0xF7), Color(0x28, 0x2B, 0x2E))

    /** 代码块 / diff 上下文行的底。从卡片往下压一档，代码块才像"嵌进去的一段"。 */
    fun codeBlock(): Color = JBColor(Color(0xF3, 0xF5, 0xF8), Color(0x25, 0x28, 0x2B))

    // ── 线 ─────────────────────────────────────────────────

    fun hairline(): Color = JBColor(Color(0xE1, 0xE5, 0xEB), Color(0x3B, 0x3E, 0x42))
    fun wellBorder(): Color = JBColor(Color(0xCB, 0xD3, 0xDD), Color(0x3E, 0x42, 0x46))

    // ── 前景 ───────────────────────────────────────────────

    /** 正文。 */
    fun text(): Color = JBColor(Color(0x1E, 0x22, 0x27), Color(0xDE, 0xE1, 0xE6))

    /** 次要信息：注释、路径、时间戳、说明。 */
    fun meta(): Color = JBColor(Color(0x6A, 0x72, 0x80), Color(0x98, 0x9F, 0xA7))

    /** 更淡一层：占位符、被压下去的东西。 */
    fun subtle(): Color = JBColor(Color(0x8B, 0x93, 0x9E), Color(0x74, 0x7B, 0x83))

    // ── 语义色 ─────────────────────────────────────────────

    fun accent(): Color = JBColor(Color(0x1F, 0x63, 0xC0), Color(0x66, 0xAA, 0xF0))
    fun user(): Color = JBColor(Color(0x18, 0x77, 0x44), Color(0x6E, 0xC9, 0x8E))
    fun warn(): Color = JBColor(Color(0x99, 0x5A, 0x00), Color(0xE0, 0xAA, 0x55))
    fun ok(): Color = JBColor(Color(0x18, 0x73, 0x3A), Color(0x64, 0xC0, 0x7C))
    fun danger(): Color = JBColor(Color(0xAE, 0x2A, 0x2A), Color(0xDF, 0x71, 0x71))

    /** 主色按钮上的文字。 */
    fun onAccent(): Color = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x0D, 0x13, 0x1A))

    /** 当前是不是深色主题 —— 用来决定半透明填充的强度。 */
    fun isDark(): Boolean = surface().let { (it.red + it.green + it.blue) / 3 < 128 }

    /** 取一个颜色的半透明版本。 */
    fun alpha(c: Color, a: Double): Color = Color(c.red, c.green, c.blue, (a * 255).toInt().coerceIn(0, 255))

    // ── 间距 / 尺寸（「块大小」） ─────────────────────────
    //
    // 两个旋钮各管一段：
    //   · 「对话字体大小」管**字**（正文 / 代码 / 标题 / 时间戳）
    //   · 「界面尺寸」管**块**（内边距、圆角、按钮高度、列表行高、弹窗尺寸）
    // 再叠一个字号带来的自适应（±15%，收得很紧），免得把字调到 9 之后
    // 控件还留着一圈本来给 12 号字的空白。

    /** 用户在设置里选的块大小倍率。 */
    private val blockScale: Float get() = AiSettings.get().uiScalePercent.coerceIn(70, 150) / 100f

    /** 字号带来的小幅自适应。 */
    private val fontFactor: Float get() = (bodySize / 12f).coerceIn(0.85f, 1.15f)

    /** 内边距 / 间隙 / 圆角的总倍率。 */
    val density: Float get() = (blockScale * fontFactor).coerceIn(0.7f, 1.7f)

    /**
     * 把一个「设计值」换算成实际像素。
     * 设计基准 = 12 号字 + 标准块大小；所以全项目的内边距都写成 `Theme.pad(9)` 这种整数，
     * 换算是集中的一处理，不要在各处散着乘系数。
     */
    fun pad(v: Int): Int = JBUI.scale((v * density).roundToInt())

    fun pad(v: Float): Int = JBUI.scale((v * density).roundToInt())

    /** 分块左右留白。 */
    val gutter: Int get() = pad(10)

    /** 卡片之间的缝隙。 */
    val cardGap: Int get() = pad(6)

    /** 左侧角色色条宽度。至少 2px —— 紧凑模式下不能缩成看不见。 */
    val accentBar: Int get() = pad(3).coerceAtLeast(2)

    fun cardPadding(): Border = JBUI.Borders.empty(pad(9), pad(11), pad(10), pad(11))

    /**
     * 需要跟着「界面尺寸」刷新间距的横排：面板 -> 设计间距。
     *
     * 用弱引用，面板被回收后自动消失，不会把工具窗口 leaking 住。
     */
    // WeakHashMap 不是线程安全的：row() 在 EDT 上调、refreshSpacing 由设置页 apply 触发。
    // 虽然几乎总在 EDT，包一层同步的成本是零，不值得赌。
    private val spacedRows: MutableMap<JPanel, Int> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    /**
     * 建一个横排，并登记它的间距 —— 改「界面尺寸」之后 [refreshSpacing] 会把它们一起重设。
     *
     * 为什么不直接 `JBUI.Borders` 那样现取：`FlowLayout` 的 `hgap` 是构造时定死的字段，
     * 不登记就只能重建容器。
     */
    fun row(align: Int, gap: Int, vararg comps: java.awt.Component): JPanel {
        val p = JPanel(FlowLayout(align, pad(gap), 0))
        p.isOpaque = false
        spacedRows[p] = gap
        comps.forEach { p.add(it) }
        return p
    }

    /** 登记一个自己造布局的横排（例如 [WrapLayout] 的行），让 [refreshSpacing] 也能刷新它。 */
    fun registerSpacing(p: JPanel, gap: Int) {
        spacedRows[p] = gap
    }

    /** 改完「界面尺寸」调一次，把所有登记过的横排间距按新的块大小重设。 */
    fun refreshSpacing() {
        // synchronizedMap 的遍历仍要自己拿锁，先抄一份快照再动
        val snapshot = synchronized(spacedRows) { spacedRows.entries.toList() }
        for ((p, gap) in snapshot) {
            (p.layout as? FlowLayout)?.hgap = pad(gap)
            p.revalidate()
        }
    }

    /** 卡片外框：左侧角色色条 + 底部发丝线 + 内边距。 */
    fun cardFrame(bar: Color): Border = javax.swing.BorderFactory.createCompoundBorder(
        javax.swing.BorderFactory.createCompoundBorder(
            AccentBarBorder(bar, accentBar),
            BottomLineBorder(hairline()),
        ),
        cardPadding(),
    )

    // ── 字号 ───────────────────────────────────────────────
    // 正文基准可以被设置里的「对话字体大小」覆盖，其余档位都相对正文推算 ——
    // 所以改一个值，整条刻度一起跟着走。

    /** 「跟随 IDE」时用的正文大小。IDE 界面字号被调大时聊天正文会失控，所以封 13px 的顶。 */
    private val followBase: Float get() = (UIUtil.getLabelFont().size - 1).coerceAtMost(13).toFloat()

    /**
     * 正文字号。
     *
     * ⚠️ 这是**真的**字号 —— 所以 HTML 里必须写 `pt`，绝不能写 `px`：
     * Swing 把 CSS 的 `px` 按 96dpi 换算成 pt（×4/3），`font-size:11px` 会渲染成 14 号，
     * 于是设置里无论选多小，消息正文都比标称值大 30%，用户会觉得「调了没用」。
     * 见 [Html.pt]。
     */
    val bodySize: Float
        get() = AiSettings.get().chatFontSize.takeIf { it > 0 }?.toFloat() ?: followBase

    /** 代码、行内代码。比正文小一号，长行能多放下几个字符。 */
    val codeSize: Float get() = (bodySize - 1f).coerceAtLeast(8f)

    /** 卡片标题（角色名、改动建议）。 */
    val titleSize: Float get() = bodySize

    /** 时间戳、路径、状态。 */
    val metaSize: Float get() = (bodySize - 1f).coerceAtLeast(8f)

    /** 说明小字。 */
    val smallSize: Float get() = (bodySize - 2f).coerceAtLeast(7f)

    /** 消息气泡宽度占面板宽度的比例（1.0 = 铺满）。 */
    val bubbleRatio: Float
        get() = AiSettings.get().bubbleWidthPercent.coerceIn(40, 100) / 100f
}

/** 在左边画一条实色竖条。 */
class AccentBarBorder(
    private val bar: Color,
    private val width: Int = JBUI.scale(3),
) : AbstractBorder() {

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
        val g2 = g.create() as Graphics2D
        g2.color = bar
        g2.fillRect(x, y, width, h)
        g2.dispose()
    }

    override fun getBorderInsets(c: Component): Insets = Insets(0, width, 0, 0)
}

/** 只在底部画一条 1px 发丝线。 */
class BottomLineBorder(private val color: Color) : AbstractBorder() {

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
        val g2 = g.create() as Graphics2D
        g2.color = color
        g2.fillRect(x, y + h - 1, w, 1)
        g2.dispose()
    }

    override fun getBorderInsets(c: Component): Insets = Insets(0, 0, 1, 0)
}

/** 只在顶部画一条 1px 发丝线。 */
class TopLineBorder(private val color: Color) : AbstractBorder() {

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
        val g2 = g.create() as Graphics2D
        g2.color = color
        g2.fillRect(x, y, w, 1)
        g2.dispose()
    }

    override fun getBorderInsets(c: Component): Insets = Insets(1, 0, 0, 0)
}

/** 四周一圈 1px 圆角边框。颜色用 lambda 取，好让输入框在聚焦时换成主色。 */
class OutlineBorder(
    private val colorProvider: () -> Color,
    private val radius: Int = JBUI.scale(6),
) : AbstractBorder() {

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = colorProvider()
        g2.drawRoundRect(x, y, w - 1, h - 1, radius, radius)
        g2.dispose()
    }

    override fun getBorderInsets(c: Component): Insets = Insets(1, 1, 1, 1)
}

/**
 * 角色标签：一枚小圆角胶囊，底是角色色的低透明填充，字是角色色。
 * 比一行彩色粗体字更像「设计过的东西」，而且一眼能认出这条是谁说的。
 */
class RoleChip(text: String, private val color: Color) : JComponent() {

    /** 每次都现取：设置里改了字号，胶囊要跟着变大变小。 */
    private val chipFont: Font get() = UIUtil.getLabelFont().deriveFont(Font.BOLD, Theme.titleSize)

    var text: String = text
        set(value) {
            field = value
            syncSize()
        }

    init {
        isOpaque = false
        syncSize()
    }

    /** 字号 / 块大小变了重新量一遍尺寸（字体和间距都是现取的，量一次就够）。 */
    fun applyAppearance() = syncSize()

    private fun syncSize() {
        val w = getFontMetrics(chipFont).stringWidth(text) + Theme.pad(15)
        val h = getFontMetrics(chipFont).height + Theme.pad(5)
        preferredSize = Dimension(w, h)
        minimumSize = Dimension(w, h)
        maximumSize = Dimension(w, h)
        revalidate()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = Theme.pad(7)
        val fill = if (Theme.isDark()) 0.20 else 0.13
        val border = if (Theme.isDark()) 0.38 else 0.30

        g2.color = Theme.alpha(color, fill)
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        g2.color = Theme.alpha(color, border)
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

        val font = chipFont
        g2.font = font
        g2.color = color
        val fm = g2.fontMetrics
        g2.drawString(text, Theme.pad(7), (height + fm.ascent - fm.descent) / 2)
        g2.dispose()
    }
}

/**
 * 主操作按钮：填充主色。
 * Swing 默认按钮在任何主题下都是同一块灰，和「发送」「应用」这种主要动作不匹配。
 */
class PrimaryButton(text: String) : JButton(text) {

    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = Theme.onAccent()
        applyAppearance()
    }

    /** 字号 / 块大小变了之后重设字体和内边距（圆角在 paint 里现取）。 */
    fun applyAppearance() {
        // 跟着「对话字体大小」走：用界面字号的话，它会比旁边的次要按钮大一圈
        font = UIUtil.getLabelFont().deriveFont(Theme.bodySize)
        border = JBUI.Borders.empty(Theme.pad(4), Theme.pad(13))
        revalidate()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val fill = when {
            !isEnabled -> JBColor(Color(0xDA, 0xDE, 0xE4), Color(0x39, 0x3C, 0x40))
            model.isPressed -> shift(Theme.accent(), 0.84)
            model.isRollover -> shift(Theme.accent(), 0.92)
            else -> Theme.accent()
        }
        g2.color = fill
        g2.fillRoundRect(0, 0, width, height, Theme.pad(5), Theme.pad(5))
        g2.dispose()
        super.paintComponent(g)
    }

    private fun shift(c: Color, factor: Double): Color = Color(
        (c.red * factor).toInt().coerceIn(0, 255),
        (c.green * factor).toInt().coerceIn(0, 255),
        (c.blue * factor).toInt().coerceIn(0, 255),
    )
}

/**
 * 工具条上的图标按钮：平台图标（跟着主题走）+ 自绘的悬停底色。
 *
 * 用 `AllIcons` 而不是自己画：平台图标在深/浅主题下是同一套语义、同一套尺寸，
 * 自己画的加号很容易在某个主题下发虚。
 */
class ToolIconButton(private val icon: Icon, tooltip: String, action: () -> Unit) : JButton() {

    init {
        toolTipText = tooltip
        addActionListener { action() }
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        applyAppearance()
    }

    /** 块大小变了之后重新留出图标四周的点击区域。 */
    fun applyAppearance() {
        val w = icon.iconWidth + Theme.pad(13)
        val h = icon.iconHeight + Theme.pad(10)
        preferredSize = Dimension(w, h)
        minimumSize = Dimension(w, h)
        maximumSize = Dimension(w, h)
        revalidate()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (isEnabled && (model.isRollover || model.isPressed)) {
            g2.color = Theme.alpha(Theme.accent(), if (model.isPressed) 0.26 else 0.14)
            g2.fillRoundRect(0, 0, width, height, Theme.pad(5), Theme.pad(5))
        }
        icon.paintIcon(this, g2, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)
        g2.dispose()
    }
}

/**
 * 次要按钮：和 PrimaryButton 同一套视觉语言（圆角、自绘），但只有描边和淡底。
 * Swing 默认按钮在深浅主题下都是同一块系统灰，一排按钮放一起跟面板其它部分不搭。
 */
class SecondaryButton(text: String) : JButton(text) {

    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = Theme.text()
        applyAppearance()
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                if (isEnabled) foreground = Theme.accent()
            }

            override fun mouseExited(e: java.awt.event.MouseEvent) {
                foreground = Theme.text()
            }
        })
    }

    /** 字号 / 块大小变了之后重设字体和内边距。 */
    fun applyAppearance() {
        font = UIUtil.getLabelFont().deriveFont(Theme.bodySize)
        border = JBUI.Borders.empty(Theme.pad(4), Theme.pad(12))
        revalidate()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = Theme.pad(5)
        val fill = when {
            !isEnabled -> Theme.alpha(Theme.text(), if (Theme.isDark()) 0.035 else 0.025)
            model.isPressed -> Theme.alpha(Theme.accent(), 0.20)
            model.isRollover -> Theme.alpha(Theme.accent(), 0.10)
            else -> Theme.alpha(Theme.text(), if (Theme.isDark()) 0.055 else 0.035)
        }
        val line = when {
            !isEnabled -> Theme.alpha(Theme.text(), 0.10)
            model.isRollover || model.isPressed -> Theme.alpha(Theme.accent(), 0.50)
            else -> Theme.hairline()
        }
        g2.color = fill
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        g2.color = line
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.dispose()
        super.paintComponent(g)
    }
}

/** 卡片之间的缝隙。做成组件而不是 strut，是为了设置里改了字号之后缝隙还能跟着变。 */
class GapStrut : JComponent() {
    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension = Dimension(0, Theme.cardGap)
    override fun getMinimumSize(): Dimension = Dimension(0, Theme.cardGap)
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, Theme.cardGap)
}

/**
 * 会折行的 FlowLayout，**而且把折出来的行算进首选高度**。
 *
 * 平台自带的 FlowLayout 宽度不够时确实会折行，但它的首选高度按「不折行」算 ——
 * 折下去的那一行画到了容器外面，被父容器直接裁掉。表现是「有个控件莫名其妙不见了」，
 * 而且只在窗口变窄 / 字号调大时出现，最容易漏测（「带上下文」那个勾选框就是这么消失的）。
 *
 * 三个必须和 `FlowLayout.layoutContainer` 对齐的细节，少一个就会算错：
 * 1. 折行判断用的是 `width - insets - hgap*2`，**不是** `width - insets`；
 *    少减这 2 个 gap，恰好一行放得下时也会折行。
 * 2. 首选宽度也要 `+ hgap*2`，否则父容器（BorderLayout 的 EAST）按这个宽度给我们，
 *    我们的 maxwidth 又不够，于是自己把自己折了。
 * 3. 可用宽度**只能从父容器推**，不能用 `target.width` —— 父容器正是拿
 *    `getPreferredSize().width` 安排我们宽度的，用自身宽度会形成自引用：
 *    某轮算小一点 → 下轮可用宽度更小 → 折得更早 → 首选宽度更小，一路掉下去。
 */
class WrapLayout(private val hgap: Int, private val vgap: Int = 0) : FlowLayout(LEFT, hgap, vgap) {

    /** 和 `FlowLayout.layoutContainer` 里那个 maxwidth 同口径。 */
    private fun maxWidth(target: Container): Int {
        var parent = target.parent
        while (parent != null) {
            if (parent.width > 0) {
                var w = parent.width - parent.insets.left - parent.insets.right
                val lm = parent.layout
                if (lm is BorderLayout) {
                    for (place in arrayOf(BorderLayout.EAST, BorderLayout.WEST)) {
                        val c = lm.getLayoutComponent(parent, place)
                        // 别把自己减掉：EAST 里的组件往上找时，EAST 就是它自己
                        if (c != null && c !== target && c.isVisible) {
                            w -= c.width.takeIf { it > 0 } ?: c.preferredSize.width
                        }
                    }
                }
                return (w - target.insets.left - target.insets.right - hgap * 2).coerceAtLeast(0)
            }
            parent = parent.parent
        }
        return 0
    }

    override fun preferredLayoutSize(target: Container): Dimension {
        val ins = target.insets
        val comps = target.components.filter { it.isVisible }
        val maxwidth = maxWidth(target)
        if (maxwidth <= 0 || comps.isEmpty()) return super.preferredLayoutSize(target)

        // 每一行：[该行内容宽度, 该行最大高度]
        val lines = ArrayList<IntArray>()
        for (c in comps) {
            val d = c.preferredSize
            val cur = lines.lastOrNull()
            if (cur != null && cur[0] + hgap + d.width <= maxwidth) {
                cur[0] += hgap + d.width
                cur[1] = maxOf(cur[1], d.height)
            } else {
                lines.add(intArrayOf(d.width, d.height))
            }
        }
        val w = lines.maxOf { it[0] } + ins.left + ins.right + hgap * 2
        val h = lines.sumOf { it[1] } + (lines.size - 1) * vgap + ins.top + ins.bottom
        return Dimension(w, h)
    }

    override fun minimumLayoutSize(target: Container): Dimension = preferredLayoutSize(target)
}
