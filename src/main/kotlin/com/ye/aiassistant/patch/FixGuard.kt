package com.ye.aiassistant.patch

import com.ye.aiassistant.errors.ErrorItem

class GuardResult(val ok: Boolean, val violations: List<String>) {
    fun message(): String = violations.joinToString("\n")
}

/**
 * 「最小修改模式」的硬校验：模型给的补丁只能落在报错行附近，且改动行数受限。
 * 校验不过就不允许应用，并把违反项回喂给模型重试。
 */
object FixGuard {

    fun validate(
        patches: List<FilePatch>,
        errorFilePaths: Set<String>,
        errors: List<ErrorItem>,
        contextLines: Int,
        maxLines: Int,
    ): GuardResult {
        val violations = mutableListOf<String>()
        if (patches.isEmpty()) return GuardResult(false, listOf("没有解析出任何补丁"))

        val allowedRanges = errors.map { (it.startLine - contextLines)..(it.endLine + contextLines) }

        for (patch in patches) {
            if (!patch.isNewFile && errorFilePaths.none { it == patch.path || it.endsWith(patch.path) || patch.path.endsWith(it) }) {
                violations += "改动了报错之外的文件: ${patch.path}"
                continue
            }
            for (hunk in patch.hunks) {
                val oldStart = hunk.oldStart
                val oldEnd = hunk.oldStart + hunk.oldCount - 1
                val touchesError = allowedRanges.any { range ->
                    oldStart <= range.last && oldEnd >= range.first ||
                        (hunk.oldCount == 0 && (oldStart - 1) in range)
                }
                if (!touchesError) {
                    violations += "${patch.path} 的 hunk @@ -$oldStart,${hunk.oldCount} 不在报错行附近（报错行: ${errors.joinToString(",") { it.startLine.toString() }}）"
                }
            }
        }

        val changed = patches.sumOf { it.addedCount + it.removedCount }
        if (changed > maxLines) {
            violations += "改动行数 $changed 超过最小修改模式上限 $maxLines"
        }
        return GuardResult(violations.isEmpty(), violations)
    }

    /** 只校验改动规模（Agent 模式下写代码时用）。 */
    fun validateSize(patches: List<FilePatch>, maxLines: Int): GuardResult {
        if (patches.isEmpty()) return GuardResult(false, listOf("没有解析出任何补丁"))
        val changed = patches.sumOf { it.addedCount + it.removedCount }
        if (changed > maxLines) {
            return GuardResult(false, listOf("改动行数 $changed 超过最小修改模式上限 $maxLines"))
        }
        return GuardResult(true, emptyList())
    }
}
