package com.ye.aiassistant.ui

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.ye.aiassistant.errors.ErrorItem
import com.ye.aiassistant.qna.Qna
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/*
 * 弹窗的视觉约定
 * ──────────────
 * 这两个弹窗原来是「平台默认长相」，和工具窗口里的面板像两个产品：
 *   · 字体用 IDE 界面字号 → 面板里的字被用户调小之后，弹窗里的字反而更大
 *   · 代码行写死 `#888888` → 深色主题下发灰，和面板的 meta 色对不上
 *   · `preferredSize` 写死 760x320 → 用户把界面调紧 / 调松，弹窗纹丝不动
 *
 * 现在统一走 Theme：字号跟「对话字体大小」，内边距 / 行高 / 圆角 / 弹窗尺寸跟「界面尺寸」。
 *
 * 刻意**没动**弹窗底部的「确定 / 取消」按钮：那是平台外壳（和 IDEA 自己所有弹窗一致），
 * 把它按插件的字号缩小反而和整个 IDE 格格不入。
 */

/**
 * 会折行的横排（弹窗里的页脚/头部用）。
 * `hgap` 走 Theme.pad，所以跟着「界面尺寸」走；折行后的高度由 WrapLayout 正确上报。
 */
class WrapLayoutPanel(align: Int, hgap: Int, private val topPad: Int) : JPanel(WrapLayout(hgap, 0)) {
    init {
        isOpaque = false
        border = JBUI.Borders.emptyTop(topPad)
        alignmentX = LEFT_ALIGNMENT
    }
}

/** 弹窗里的一行说明文字。 */
private fun caption(text: String, font: Float = Theme.metaSize, color: Color = Theme.meta()): JBLabel =
    JBLabel(text).apply {
        this.font = UIUtil.getLabelFont().deriveFont(font)
        this.foreground = color
    }

/**
 * 列表行的统一排版。
 *
 * 行高由「内边距 + 字体」决定，所以字号和块大小一改，行高自然跟着走
 * （原来固定 `empty(3, 4)`，字号调大了行就挤在一起）。
 */
private class ThemedCellRenderer(
    private val decorate: (value: Any?, index: Int) -> String?,
    /** 窄弹窗里文字会被横向截断，完整内容挂 tooltip。 */
    private val tooltip: (value: Any?) -> String? = { null },
) : DefaultListCellRenderer() {

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        label.font = UIUtil.getLabelFont().deriveFont(Theme.bodySize)
        label.border = JBUI.Borders.empty(Theme.pad(4), Theme.pad(6))
        decorate(value, index)?.let { label.text = it }
        label.toolTipText = tooltip(value)
        return label
    }
}

/** 选择要交给 AI 修复的报错。 */
class ErrorSelectDialog(
    project: Project,
    private val errors: List<ErrorItem>,
    caretLine: Int,
) : DialogWrapper(project, false) {

    private val list = JBList<ErrorItem>()
    private val countLabel = caption("")

    init {
        title = "AI 修复报错"
        list.model = CollectionListModel(errors)
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.cellRenderer = ThemedCellRenderer(
            decorate = { value, _ ->
                val item = value as? ErrorItem ?: return@ThemedCellRenderer null
                "<html>行 ${item.startLine}: ${Html.escape(item.description)}" +
                    "<br><font color=\"${Html.hex(Theme.meta())}\">${Html.escape(item.text.take(120))}</font></html>"
            },
            tooltip = { value -> (value as? ErrorItem)?.text },
        )
        val onCaret = errors.indices.filter { errors[it].startLine == caretLine }
        if (onCaret.isNotEmpty()) {
            onCaret.forEach { list.addSelectionInterval(it, it) }
        } else {
            list.setSelectionInterval(0, errors.size - 1)
        }
        // 焦点在列表里时 Enter 也要能确认（不然得先用鼠标点一下「确定」）
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ai-errors-ok")
        list.actionMap.put("ai-errors-ok", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = doOKAction()
        })
        list.addListSelectionListener { updateCount() }

        init()
        updateCount()
    }

    /**
     * 随时告诉用户「会修几条」——多选列表最容易忘记自己选了什么。
     * 一条都没选时把「确定」也禁掉：原来点了会静默返回，什么也不发生。
     */
    private fun updateCount() {
        val n = list.selectedIndices.size
        countLabel.text = "已选 $n / ${errors.size}"
        countLabel.foreground = if (n == 0) Theme.warn() else Theme.meta()
        okAction.isEnabled = n > 0
    }

    val selectedItems: List<ErrorItem> get() = list.selectedValuesList

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        // ⚠️ **不要给整个弹窗写死 preferredSize**：那样「头 + 列表 + 脚」被压在一个固定高度里，
        // 行数一多就把最后一行截掉（真踩过：三行报错只显示了两行半）。
        // 只给列表一个高度，其余让 BorderLayout 自己加起来，跟着「界面尺寸」一起缩放。
        val scroll = JBScrollPane(list).apply {
            preferredSize = Dimension(Theme.pad(380), Theme.pad(140))
        }

        // ⚠️ 提示文字和「已选 N/M」**不能**用 BorderLayout 的 WEST/EAST：
        // 两边加起来超过可用宽度时它们会直接叠在一起（真踩过，一渲染就看见两层字）。
        // 用会折行的行，装不下就换行，高度也算得对。
        val head = toolRow(10)
        head.border = JBUI.Borders.emptyBottom(Theme.pad(6))
        head.add(caption("勾选要修复的报错（默认选中光标所在行，可多选）"))
        head.add(countLabel)

        val foot = toolRow(8)
        foot.border = JBUI.Borders.emptyTop(Theme.pad(6))
        foot.add(caption("Enter 确认 · Esc 取消", Theme.smallSize, Theme.subtle()))

        panel.add(head, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        panel.add(foot, BorderLayout.SOUTH)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    override fun getDimensionServiceKey(): String = "com.ye.aiassistant.ErrorSelectDialog"

    /** 出图用：把内容面板拿出来（`createCenterPanel` 是 protected，这里开个口子）。 */
    fun contentForPreview(): JComponent = createCenterPanel()
}

/** 逐题选择答案：↑↓ 选择，Enter 确认，← 上一题，Esc 跳过本题。 */
class QnaDialog(
    project: Project,
    private val questions: List<Qna.Question>,
) : DialogWrapper(project, false) {

    /**
     * 题号 -> 选中的答案。
     * 用**题号**做键而不是题干：两道题文字一样的话，用题干当键会互相覆盖。
     */
    private val picked = LinkedHashMap<Int, String>()

    /** 按题号顺序给出「题干 -> 答案」。 */
    val answers: List<Pair<String, String>>
        get() = picked.entries.sortedBy { it.key }.map { questions[it.key].text to it.value }

    private var index = 0

    /** DialogWrapper 本身不是组件，revalidate 要落到中间面板上。 */
    private var center: JComponent? = null

    private val questionLabel = JBLabel()
    private val optionsModel = DefaultListModel<String>()
    private val options = JBList(optionsModel)
    private val progressLabel = caption("")

    /**
     * 「上一题」做成面板里的次要按钮，而不是 DialogWrapper 的 action。
     *
     * `createActions()` 里返回的**第一个** action 会被当成默认按钮（回车触发），
     * 把「上一题」放第一个的话，敲回车就变成退回去了 —— 而且按钮顺序也拧。
     * 放在内容区里，位置、样式、启用状态都自己说了算。
     */
    private val prevButton = SecondaryButton("← 上一题").apply { addActionListener { goBack() } }

    init {
        title = "AI 问答（从回复中抽取问题）"
        options.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // 尾随 lambda 会绑到最后一个参数（tooltip）上，所以这里必须写具名参数
        options.cellRenderer = ThemedCellRenderer(decorate = { value, i -> "${i + 1}. $value" })
        options.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ai-qna-choose")
        options.actionMap.put("ai-qna-choose", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                advance(options.selectedValue)
            }
        })
        // ← / Backspace 回上一题：答错了不至于只能干掉整个弹窗重来
        for (key in listOf(KeyEvent.VK_LEFT, KeyEvent.VK_BACK_SPACE)) {
            options.inputMap.put(KeyStroke.getKeyStroke(key, 0), "ai-qna-prev-$key")
            options.actionMap.put("ai-qna-prev-$key", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = goBack()
            })
        }
        for (i in 0..4) {
            val key = KeyStroke.getKeyStroke('1' + i)
            options.inputMap.put(key, "ai-qna-$i")
            options.actionMap.put("ai-qna-$i", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    if (i < optionsModel.size()) {
                        options.selectedIndex = i
                        advance(optionsModel.get(i))
                    }
                }
            })
        }
        init()
        showQuestion()
    }

    private fun showQuestion() {
        val q = questions[index]
        questionLabel.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, Theme.bodySize + 1f)
        questionLabel.foreground = Theme.text()
        // 换行宽度跟着「界面尺寸」走，原来写死 640px，字调小了却还是那么宽
        questionLabel.text = "<html><div style=\"width:${Theme.pad(320)}px\">" +
            "<font color=\"${Html.hex(Theme.accent())}\">${index + 1}/${questions.size}</font>" +
            "&nbsp;&nbsp;${Html.escape(q.text)}</div></html>"
        optionsModel.clear()
        q.options.forEach { optionsModel.addElement(it) }
        if (optionsModel.size() > 0) options.selectedIndex = 0
        options.visibleRowCount = optionsModel.size().coerceIn(2, 8)
        progressLabel.text = "↑↓ 选择 · Enter 确认 · 数字键直选 · ← 上一题 · Esc 跳过本题"
        progressLabel.foreground = Theme.meta()
        prevButton.isEnabled = index > 0
        center?.revalidate()
        center?.repaint()
    }

    private fun advance(answer: String?) {
        // 回上一题改答案就是覆盖同一个键，不会留下两条
        if (answer != null) picked[index] = answer
        if (index < questions.size - 1) {
            index++
            showQuestion()
        } else {
            doOKAction()
        }
    }

    private fun goBack() {
        if (index == 0) return
        index--
        val previous = picked[index]
        showQuestion()
        // 恢复上一次的选择，改一下就能重答
        val i = optionsModel.indexOf(previous)
        if (i >= 0) options.selectedIndex = i
    }

    /**
     * Esc = 跳过本题。
     *
     * 注意最后一题：原来会走 `super.doCancelAction()`，也就是把前面答好的**全部丢掉** ——
     * 答到第三题不小心按一下 Esc，前两题的答案就没了。现在最后一题按 Esc 当「完成」处理。
     * 真想全部放弃用「取消」按钮。
     */
    override fun doCancelAction() {
        if (index < questions.size - 1) {
            index++
            showQuestion()
        } else {
            doOKAction()
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        // 高度按**实际行数**给，别写死：选项多一条就会被截（真踩过：三个选项只显示两个）。
        // 宽度给个基准，高度让 JList 自己按 visibleRowCount 量。
        val scroll = JBScrollPane(options).apply {
            preferredSize = Dimension(Theme.pad(350), options.preferredSize.height.coerceAtLeast(Theme.pad(60)))
        }
        questionLabel.border = JBUI.Borders.emptyBottom(Theme.pad(8))
        // 同样不用 WEST/EAST：提示和按钮加起来放不下时会重叠，交给会折行的行
        val foot = WrapLayoutPanel(FlowLayout.LEFT, Theme.pad(8), Theme.pad(6))
        foot.add(progressLabel)
        foot.add(prevButton)
        panel.add(questionLabel, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        panel.add(foot, BorderLayout.SOUTH)
        center = panel
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = options

    override fun getDimensionServiceKey(): String = "com.ye.aiassistant.QnaDialog"

    /** 出图用：把内容面板拿出来（`createCenterPanel` 是 protected，这里开个口子）。 */
    fun contentForPreview(): JComponent = createCenterPanel()

    override fun createActions(): Array<Action> {
        okAction.putValue(Action.NAME, "完成")
        cancelAction.putValue(Action.NAME, "取消（丢弃全部）")
        // 顺序：完成在左、取消在右 —— 和 IDEA 自己的弹窗一致；第一个是回车默认触发的那个
        return arrayOf(okAction, cancelAction)
    }
}

/**
 * 本地视觉验证：造两个假数据的弹窗，把它们的**内容面板**交出来（不显示窗口）。
 *
 * 弹窗是模态的，平时的出图钩子（渲染工具窗口面板）根本照不到它 ——
 * 于是「弹窗长什么样」长期只能靠猜，改了字号也没法核对。这里给它开个口子：
 * 用真实实例的 `createCenterPanel()` 产物，走同一套离屏渲染出图。
 * 只在 `-Dai.assistant.demo.dialog=errors|qna` 时由 AutoTestStartup 调用。
 */
object DialogPreview {

    private val FAKE_ERRORS = listOf(
        ErrorItem(
            39, 39, "找不到符号 customerRepository", "ERROR",
            "Customer customer = customerRepository.findById(order.getCustomerId()).get();",
        ),
        ErrorItem(
            42, 42, "方法 get() 可能返回 null", "WARNING",
            "return customer.getName();",
        ),
        ErrorItem(
            57, 58, "变量 sql 定义后未被使用", "WARNING",
            "String sql = \"select 1\";",
        ),
    )

    private val FAKE_QUESTIONS = listOf(
        Qna.Question(
            "这个改动要不要同时兼容老版本的 JDK 8？",
            listOf("要，保持兼容", "不要，直接用 21 的新写法", "先不确定，按最小改动来"),
        ),
        Qna.Question(
            "要不要顺带补一个单元测试？",
            listOf("要", "不要，这次只修 bug", "补测试但先不加到 CI"),
        ),
    )

    fun panel(project: Project, which: String): JComponent? = when (which) {
        "errors" -> ErrorSelectDialog(project, FAKE_ERRORS, 42).contentForPreview()
        "qna" -> QnaDialog(project, FAKE_QUESTIONS).contentForPreview()
        else -> null
    }
}

/** 打开插件设置页。 */
fun openSettings(project: Project) {
    ShowSettingsUtil.getInstance().showSettingsDialog(project, com.ye.aiassistant.settings.AiConfigurable::class.java)
}
