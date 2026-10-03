package com.ye.aiassistant.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * 极简 Markdown -> Swing HTML 渲染。
 * Swing 的 HTML 只支持 HTML3.2 的一部分，所以只能用内联 style，不能用 CSS class。
 *
 * 字号统一走 Theme：正文、代码、标题之间要拉得开，不然整块文字糊在一起。
 */
object Html {

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun hex(color: Color): String = "#%02x%02x%02x".format(color.red, color.green, color.blue)

    private fun monoFamily(): String = "JetBrains Mono, Menlo, Consolas, monospace"

    private fun codeBackground(): String = hex(Theme.codeBlock())

    /**
     * HTML 里的字号 —— **只写 pt，不写 px**。
     *
     * Swing 的 HTML 把 CSS 的 `px` 当 96dpi 的像素换算成 pt（×4/3）：
     * `font-size:11px` 解析出来是 14 号，比标称值大 30%。
     * 写 `pt` 才是 1:1，和 `deriveFont(nf)` 的标签/按钮对得上。
     * （实测：8~20px 一律 ×4/3 后截断；pt 则原样通过。）
     */
    private fun pt(size: Float): String = "${size.toInt()}pt"

    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d+)\\.\\s+(.*)$")
    private val HEADING = Regex("^(#{1,4})\\s+(.*)$")

    fun markdown(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val out = StringBuilder()
        val code = StringBuilder()
        var inCode = false
        var list: String? = null

        // 连续的列表项要包进同一个 <ul>/<ol>，Swing 才会给出真正的项目符号和悬挂缩进；
        // 原来是用 &nbsp; 手工推三个空格，换行后对不齐。
        fun closeList() {
            if (list != null) {
                out.append("</").append(list).append("><br>")
                list = null
            }
        }

        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    out.append(codeBlock(code.toString()))
                    code.setLength(0)
                    inCode = false
                } else {
                    closeList()
                    inCode = true
                }
                continue
            }
            if (inCode) {
                code.append(line).append('\n')
                continue
            }
            when {
                BULLET.matches(line) -> {
                    if (list != "ul") {
                        closeList()
                        out.append("<ul>")
                        list = "ul"
                    }
                    out.append("<li>").append(inlineFormat(BULLET.find(line)!!.groupValues[1])).append("</li>")
                }
                NUMBERED.matches(line) -> {
                    if (list != "ol") {
                        closeList()
                        out.append("<ol>")
                        list = "ol"
                    }
                    out.append("<li>").append(inlineFormat(NUMBERED.find(line)!!.groupValues[2])).append("</li>")
                }
                else -> {
                    closeList()
                    out.append(inline(line)).append("<br>")
                }
            }
        }
        closeList()
        if (inCode && code.isNotEmpty()) out.append(codeBlock(code.toString()))
        return out.toString()
    }

    /**
     * 代码块 / diff 块：一行一个表格行。
     *
     * 为什么要表格而不是 <pre>：右侧工具窗口只有 400px 左右宽，<pre> 不折行、
     * JEditorPane 又没有横向滚动条，长一点的代码行会被直接裁掉看不到。
     * 表格单元格会正常折行，同时每行有独立底色 —— diff 的绿/红是整行通铺的
     * （<pre> 里给 <span> 上背景色只能贴着文字，像用荧光笔划了一道）。
     */
    private fun codeBlock(code: String): String {
        val body = code.trimEnd('\n')
        val diff = isDiff(body)
        val rows = body.split("\n").joinToString("") { line ->
            val bg = if (diff) diffRowBackground(line) else Theme.codeBlock()
            "<tr><td bgcolor=\"${hex(bg)}\">${codeLineHtml(line, diff)}</td></tr>"
        }
        return "<table width=\"100%\" cellpadding=\"3\" cellspacing=\"0\">$rows</table>"
    }

    /** 看起来像 unified diff 的代码块：+ 行绿色、- 行红色，和 IDEA 的 Diff 窗口一致。 */
    private fun isDiff(code: String): Boolean {
        var hunk = false
        var change = false
        for (line in code.lineSequence()) {
            if (line.startsWith("@@")) hunk = true
            if (line.startsWith("+") || line.startsWith("-")) change = true
            if (hunk && change) return true
        }
        return false
    }

    private fun diffRowBackground(line: String): java.awt.Color = when {
        line.startsWith("+") -> addBackground()
        line.startsWith("-") -> delBackground()
        else -> Theme.codeBlock()
    }

    /**
     * 一行代码 -> HTML。
     * 缩进换成 `&nbsp;`（不然 HTML 会把行首空格吃掉，代码就没有缩进了），
     * 行内其余空格保持可断行，长行才会折行而不是被裁掉。
     */
    private fun codeLineHtml(rawLine: String, diff: Boolean): String {
        val line = rawLine.replace("\t", "    ")
        val font = "font-family:${monoFamily()};font-size:${pt(Theme.codeSize)}"

        if (!diff) {
            val indent = line.takeWhile { it == ' ' }
            val rest = line.substring(indent.length)
            val text = indent.replace(" ", "&nbsp;") + escape(rest).ifEmpty { "&nbsp;" }
            return "<span style=\"$font;color:${hex(Theme.text())};\">$text</span>"
        }

        // diff 行首是一个标记字符（+ / - / 空格 / @），标记后的缩进也要保住
        val hasMarker = line.isNotEmpty() &&
            (line[0] == '+' || line[0] == '-' || line[0] == '@' || line[0] == ' ')
        val marker = if (hasMarker) line.substring(0, 1) else ""
        val payload = if (hasMarker) line.substring(1) else line
        val indent = payload.takeWhile { it == ' ' }
        val rest = payload.substring(indent.length)

        val color = when {
            line.startsWith("@@") -> hunkColor()
            line.startsWith("+++") || line.startsWith("+") -> addColor()
            line.startsWith("---") || line.startsWith("-") -> delColor()
            line.startsWith("diff ") || line.startsWith("index ") -> Theme.subtle()
            else -> Theme.text()
        }
        val text = escape(marker) + indent.replace(" ", "&nbsp;") + escape(rest).ifEmpty { "&nbsp;" }
        return "<span style=\"$font;color:${hex(color)};\">$text</span>"
    }

    private fun addColor(): Color = JBColor(Color(0x11, 0x6B, 0x2E), Color(0x7E, 0xE7, 0x87))
    private fun delColor(): Color = JBColor(Color(0xB7, 0x1C, 0x1C), Color(0xFF, 0x8B, 0x82))
    private fun hunkColor(): Color = JBColor(Color(0x0B, 0x5C, 0xAB), Color(0x79, 0xC0, 0xFF))
    private fun addBackground(): Color = JBColor(Color(0xDD, 0xF2, 0xE1), Color(0x18, 0x33, 0x22))
    private fun delBackground(): Color = JBColor(Color(0xFB, 0xE4, 0xE4), Color(0x38, 0x1B, 0x1E))

    private fun inline(line: String): String {
        if (line.isBlank()) return "<br>"
        val heading = HEADING.find(line)
        if (heading != null) {
            val level = heading.groupValues[1].length
            val size = when (level) {
                1 -> Theme.bodySize + 3
                2 -> Theme.bodySize + 2
                else -> Theme.bodySize + 1
            }
            // 大标题上面留一行空，段落之间才不会挤在一起
            val lead = if (level <= 2) "<br>" else ""
            return "$lead<span style=\"font-size:${pt(size)};\"><b>${inlineFormat(heading.groupValues[2])}</b></span>"
        }
        if (line.startsWith("> ")) {
            return "<span style=\"color:${hex(Theme.subtle())};\">▏${inlineFormat(line.removePrefix("> "))}</span>"
        }
        return inlineFormat(line)
    }

    private fun inlineFormat(text: String): String {
        var s = escape(text)
        s = Regex("`([^`]+)`").replace(s) { m ->
            "<code style=\"font-family:${monoFamily()};font-size:${pt(Theme.codeSize)};" +
                "background-color:${codeBackground()};\">${m.groupValues[1]}</code>"
        }
        s = Regex("\\*\\*([^*]+)\\*\\*").replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = Regex("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])").replace(s) { "<i>${it.groupValues[1]}</i>" }
        s = Regex("~~([^~]+)~~").replace(s) { "<strike>${it.groupValues[1]}</strike>" }
        return s
    }

    /**
     * 思考过程：一块独立底色的引用块，字号比正文再小一号、颜色压暗。
     * 之前只是一段灰色文字，和正文糊在一起，看不出"这是模型的内部推理"。
     */
    fun thinking(text: String): String {
        val bg = hex(Theme.thinkBlock())
        return "<table width=\"100%\" cellpadding=\"6\" cellspacing=\"0\"><tr><td bgcolor=\"$bg\">" +
            "<span style=\"font-size:${pt(Theme.smallSize)};color:${hex(Theme.meta())};\">" +
            "<b>思考过程</b><br>" + markdown(text) +
            "</span></td></tr></table><br>"
    }

    /** 消息正文的外层 HTML（颜色、字号随主题走）。 */
    fun page(bodyHtml: String): String {
        val font = UIUtil.getLabelFont().family
        return "<html><body style=\"font-family:$font;font-size:${pt(Theme.bodySize)};" +
            "color:${hex(Theme.text())};margin:0;\">" + bodyHtml + "</body></html>"
    }

    fun accent(): Color = Theme.accent()
    fun userColor(): Color = Theme.user()
    fun warnColor(): Color = Theme.warn()

}
