package com.ye.aiassistant.patch

/** 一个 hunk：old 侧 / new 侧 的起始行与行数，以及带前缀的原始行 */
class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<String>,
) {
    /** 被替换掉的原文（context + 删除行） */
    fun oldBlock(): List<String> = lines.filter { it.isEmpty() || it[0] != '+' }.map { lineText(it) }

    /** 替换后的新文本（context + 新增行） */
    fun newBlock(): List<String> = lines.filter { it.isEmpty() || it[0] != '-' }.map { lineText(it) }

    internal fun lineText(line: String): String =
        if (line.isEmpty()) "" else line.substring(1).removeSuffix("\r")

    /** 被删除的原文行（不含前缀） */
    fun removedLines(): List<String> = lines.filter { it.startsWith("-") }.map { lineText(it) }

    /** 新增的行（不含前缀） */
    fun addedLines(): List<String> = lines.filter { it.startsWith("+") }.map { lineText(it) }

    val addedCount: Int get() = lines.count { it.startsWith("+") }
    val removedCount: Int get() = lines.count { it.startsWith("-") }
}

class FilePatch(val oldPath: String, val newPath: String, val hunks: List<Hunk>) {
    val path: String get() = if (newPath == "/dev/null" || newPath.isEmpty()) oldPath else newPath
    val isNewFile: Boolean get() = oldPath == "/dev/null" || oldPath.isEmpty()
    val addedCount: Int get() = hunks.sumOf { it.addedCount }
    val removedCount: Int get() = hunks.sumOf { it.removedCount }
}

class PatchException(message: String) : RuntimeException(message)

/**
 * unified diff 解析 + 应用。
 * 应用时对上下文做「精确 → 去尾部空白 → 折叠空白」三级模糊匹配，
 * 所以模型给的 @@ 行号有偏差也能对上。
 */
object UnifiedDiff {

    private val HUNK_HEADER = Regex("^@@\\s+-([0-9]+)(?:,([0-9]+))?\\s+\\+([0-9]+)(?:,([0-9]+))?\\s+@@")

    /** 从模型回复里提取补丁：优先取 ```diff 代码块，没有就整体当补丁解析。 */
    fun extract(text: String): List<FilePatch> {
        val fenced = Regex("```(?:diff|patch|udiff)?\\s*\\r?\\n([\\s\\S]*?)```")
            .findAll(text).map { it.groupValues[1] }.toList()
        val blocks = if (fenced.isNotEmpty()) fenced else listOf(text)
        return blocks.flatMap { parse(it) }
    }

    fun parse(text: String): List<FilePatch> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val result = mutableListOf<FilePatch>()
        var i = 0
        var oldPath: String? = null
        var newPath: String? = null
        var hunks = mutableListOf<Hunk>()

        fun flush() {
            if (oldPath != null || newPath != null || hunks.isNotEmpty()) {
                result += FilePatch(oldPath.orEmpty(), newPath.orEmpty(), hunks.toList())
            }
            oldPath = null; newPath = null; hunks = mutableListOf()
        }

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("diff --git") -> {
                    flush()
                    i++
                }
                line.startsWith("--- ") && i + 1 < lines.size && lines[i + 1].startsWith("+++ ") -> {
                    flush()
                    oldPath = cleanPath(line.removePrefix("--- "))
                    newPath = cleanPath(lines[i + 1].removePrefix("+++ "))
                    i += 2
                }
                line.startsWith("@@") -> {
                    val m = HUNK_HEADER.find(line)
                    if (m != null) {
                        val oldStart = m.groupValues[1].toIntOrNull() ?: 1
                        val oldCount = m.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: 1
                        val newStart = m.groupValues[3].toIntOrNull() ?: 1
                        val newCount = m.groupValues[4].ifEmpty { "1" }.toIntOrNull() ?: 1
                        val body = mutableListOf<String>()
                        i++
                        while (i < lines.size) {
                            val l = lines[i]
                            // 空行也算 hunk 的一行：模型经常把「文件里的空行」直接写成空行，
                            // 而不是标准的单个空格。以前这里 break 掉，补丁会被截断 →
                            // 后面 apply 时上下文对不上，整块报「找不到能匹配的上下文」。
                            val isBody = l.isEmpty() ||
                                l.startsWith("+") || l.startsWith("-") || l.startsWith(" ") || l.startsWith("\\")
                            if (!isBody) break
                            if (l.startsWith("\\")) { i++; continue } // "\ No newline at end of file"
                            body += l
                            i++
                        }
                        // 去掉 diff 包裹时可能带的 trailing 空行
                        while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.size - 1)
                        hunks += Hunk(oldStart, oldCount, newStart, newCount, body)
                        continue
                    }
                    i++
                }
                else -> i++
            }
        }
        flush()
        return result.filter { it.hunks.isNotEmpty() && (it.oldPath.isNotEmpty() || it.newPath.isNotEmpty()) }
    }

    private fun cleanPath(raw: String): String {
        var p = raw.trim()
        // 去掉时间戳部分（tab 之后）
        p = p.substringBefore('\t').trim()
        p = p.removeSurrounding("\"")
        p = p.substringBefore("  ") // "path  (date)" 形式
        if (p == "/dev/null") return p
        if (p.startsWith("a/") || p.startsWith("b/")) p = p.substring(2)
        return p.trim()
    }

    /** 把补丁应用到原文，返回新文本。 */
    fun apply(original: String, patch: FilePatch): String {
        if (patch.hunks.isEmpty()) return original
        val hasCrlf = original.contains("\r\n")
        val text = original.replace("\r\n", "\n")
        val src = text.split("\n")
        val out = ArrayList<String>(src.size + 64)
        var cursor = 0
        for (hunk in patch.hunks) {
            val oldBlock = hunk.oldBlock()
            var replacement = hunk.newBlock()
            var index = if (oldBlock.isEmpty()) {
                (hunk.oldStart - 1).coerceIn(0, src.size)
            } else {
                findBlock(src, oldBlock, hunk.oldStart - 1)
            }
            var consumed = oldBlock.size
            if (index < 0) {
                // 退化匹配：模型给的上下文行对不上时，只按「被删除的行」定位，原地替换
                val removed = hunk.removedLines()
                if (removed.isNotEmpty()) {
                    val at = findBlock(src, removed, hunk.oldStart - 1)
                    if (at >= 0) {
                        index = at
                        consumed = removed.size
                        replacement = hunk.addedLines()
                    }
                }
            }
            if (index < 0 || index < cursor) {
                throw PatchException("找不到能匹配的上下文（@@ -${hunk.oldStart}），补丁可能已过期或上下文不足")
            }
            out.addAll(src.subList(cursor, index))
            out.addAll(replacement)
            cursor = index + consumed
        }
        out.addAll(src.subList(cursor, src.size))
        val joined = out.joinToString("\n")
        return if (hasCrlf) joined.replace("\n", "\r\n") else joined
    }

    /** 在 src 里为 block 找最合适的插入位置；找不到返回 -1。 */
    private fun findBlock(src: List<String>, block: List<String>, expected: Int): Int {
        if (block.isEmpty()) return expected.coerceIn(0, src.size)
        val candidates = mutableListOf<Int>()
        for (mode in 0..2) {
            candidates.clear()
            var i = 0
            while (i + block.size <= src.size) {
                if (matches(src, block, i, mode)) candidates += i
                i++
            }
            if (candidates.isNotEmpty()) return candidates.minByOrNull { kotlin.math.abs(it - expected) }!!
        }
        return -1
    }

    private fun matches(src: List<String>, block: List<String>, at: Int, mode: Int): Boolean {
        for (j in block.indices) {
            if (!lineEquals(src[at + j], block[j], mode)) return false
        }
        return true
    }

    private fun lineEquals(a: String, b: String, mode: Int): Boolean = when (mode) {
        0 -> a == b
        1 -> a.trimEnd() == b.trimEnd()
        else -> normalize(a) == normalize(b)
    }

    private fun normalize(s: String): String = s.trim().replace(Regex("\\s+"), " ")
}
