package com.ye.aiassistant.errors

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

data class ErrorItem(
    val startLine: Int,
    val endLine: Int,
    val description: String,
    val severity: String,
    val text: String,
) {
    fun label(): String = "行 $startLine: $description"
}

/** 从 IDEA 的 daemon 里取当前文件真实的编译/检查错误。 */
object ErrorCollector {

    fun collect(
        project: Project,
        editor: Editor,
        includeWarnings: Boolean = false,
    ): List<ErrorItem> {
        var items: List<ErrorItem> = emptyList()
        ApplicationManager.getApplication().runReadAction { items = collectInReadAction(project, editor, includeWarnings) }
        return items
    }

    private fun collectInReadAction(
        project: Project,
        editor: Editor,
        includeWarnings: Boolean,
    ): List<ErrorItem> {
        val document = editor.document
        val severity = if (includeWarnings) HighlightSeverity.WARNING else HighlightSeverity.ERROR
        val result = LinkedHashMap<String, ErrorItem>()

        DaemonCodeAnalyzerEx.processHighlights(document, project, severity, 0, document.textLength) { info ->
            val description = info.description
            if (!description.isNullOrBlank()) {
                val start = info.startOffset.coerceIn(0, document.textLength)
                val end = info.endOffset.coerceIn(start, document.textLength)
                val startLine = document.getLineNumber(start) + 1
                val endLine = document.getLineNumber(end) + 1
                val lineText = runCatching {
                    val ls = document.getLineStartOffset(startLine - 1)
                    val le = document.getLineEndOffset(startLine - 1)
                    document.getText(TextRange(ls, le)).trim()
                }.getOrDefault("")
                val item = ErrorItem(
                    startLine = startLine,
                    endLine = endLine,
                    description = description,
                    severity = info.severity?.toString().orEmpty(),
                    text = lineText,
                )
                result["$startLine|$description"] = item
            }
            true
        }
        return result.values.sortedBy { it.startLine }
    }
}
