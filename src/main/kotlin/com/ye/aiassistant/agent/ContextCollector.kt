package com.ye.aiassistant.agent

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.ye.aiassistant.errors.ErrorCollector
import com.ye.aiassistant.patch.PatchPaths

/**
 * 发送消息时自动附带的上下文。
 *
 * ⚠️ 这里必须带上**文件正文**。
 *
 * 原来只给了「文件路径 + 光标行 + 用户选中内容 + 报错」，没有正文。于是
 * 「帮我看下我的代码合适吗」这种问法下，模型只拿到一个文件名和一个行号，
 * 只能回一句「我无法直接读取你的代码文件，请把 llm_test.py 中相关代码片段
 * （尤其是光标附近的第 57 行）粘贴出来」—— 而它准确报出了文件名和行号，
 * 看的人只会更困惑：你既然知道是哪个文件哪一行，为什么读不到？
 *
 * 上下文要么给全（含正文），要么就别给一半。
 */
object ContextCollector {

    /** 附带上了什么：`text` 是真正发给模型的内容，`summary` 是给面板显示的一句话。 */
    data class Result(val text: String, val summary: String) {
        val isEmpty: Boolean get() = text.isBlank()

        companion object {
            val EMPTY = Result("", "")
        }
    }

    /** 不超过这么多行就直接给全文。 */
    private const val FULL_FILE_LINES = 240

    /** 超长文件只给光标附近的这么多行。 */
    private const val WINDOW_LINES = 100

    /** 大到这个字符数就干脆不给正文（压缩过的 js / 生成代码），免得把上下文挤爆。 */
    private const val MAX_FILE_CHARS = 400_000

    private const val MAX_SELECTION_CHARS = 4000

    fun build(project: Project): Result {
        val manager = FileEditorManager.getInstance(project)
        val editor = manager.selectedTextEditor ?: return Result.EMPTY
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return Result.EMPTY
        val path = PatchPaths.relative(project, file)
        val caret = editor.caretModel.logicalPosition.line            // 0-based
        val language = file.extension ?: ""
        val text = editor.document.text
        val lines = text.split('\n')
        val selection = editor.selectionModel.selectedText

        val sb = StringBuilder()
        sb.appendLine("【当前编辑器上下文】")
        sb.appendLine("文件: $path（共 ${lines.size} 行）")
        sb.appendLine("光标位置: 第 ${caret + 1} 行")

        if (!selection.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("用户选中了这段代码（这是他当前最关心的部分）:")
            sb.appendLine("```$language")
            sb.appendLine(selection.take(MAX_SELECTION_CHARS))
            sb.appendLine("```")
        }

        if (text.length > MAX_FILE_CHARS) {
            sb.appendLine()
            sb.appendLine("（文件过大，没有附带正文；需要哪一部分请让用户选中或指明行号）")
        } else if (lines.size <= FULL_FILE_LINES) {
            sb.appendLine()
            sb.appendLine("当前文件全文:")
            sb.appendLine("```$language")
            sb.appendLine(text.trimEnd('\n'))
            sb.appendLine("```")
        } else {
            // 大文件给光标附近的窗口：不必让用户先说行号，模型也能直接看到问题所在
            val start = (caret - WINDOW_LINES / 2).coerceIn(0, (lines.size - WINDOW_LINES).coerceAtLeast(0))
            val end = (start + WINDOW_LINES).coerceAtMost(lines.size)
            sb.appendLine()
            sb.appendLine("当前文件片段（第 ${start + 1} ~ $end 行，光标在中间那一片）:")
            sb.appendLine("```$language")
            sb.appendLine(lines.subList(start, end).joinToString("\n"))
            sb.appendLine("```")
            sb.appendLine("（如果问题不在这个范围内，用 read_file 读其它部分，或让用户在编辑器里选中后再问）")
        }

        val errors = ErrorCollector.collect(project, editor)
        if (errors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("当前文件报错:")
            errors.take(20).forEach { sb.appendLine("- 行 ${it.startLine}: ${it.description}") }
        }

        val summary = buildString {
            append(file.name)
            append(" 第 ").append(caret + 1).append(" 行")
            if (!selection.isNullOrBlank()) append(" · 含选中片段")
            if (errors.isNotEmpty()) append(" · ${errors.size} 条报错")
        }
        return Result(sb.toString().trim(), summary)
    }
}
