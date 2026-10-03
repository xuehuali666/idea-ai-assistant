package com.ye.aiassistant.qna

/** 与 pi 的 qna.ts 相同的 [Q]/[O] 协议解析。 */
object Qna {

    class Question(val text: String, val options: List<String>)

    fun parse(raw: String): List<Question> {
        val result = mutableListOf<Question>()
        var current: MutableList<String>? = null
        var currentText = ""
        for (line in raw.replace("\r\n", "\n").split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            when {
                trimmed == "[NONE]" -> return emptyList()
                trimmed.startsWith("[Q]") -> {
                    current?.let { if (it.isNotEmpty()) result += Question(currentText, it.toList()) }
                    currentText = trimmed.removePrefix("[Q]").trim()
                    current = mutableListOf()
                }
                trimmed.startsWith("[O]") && current != null -> current!!.add(trimmed.removePrefix("[O]").trim())
            }
        }
        current?.let { if (it.isNotEmpty()) result += Question(currentText, it.toList()) }
        return result
    }

    fun summary(answers: List<Pair<String, String>>): String = buildString {
        appendLine("### Q&A 汇总")
        appendLine()
        answers.forEach { (q, a) ->
            appendLine("**Q: $q**")
            appendLine("A: $a")
            appendLine()
        }
        append("---")
    }
}
