package com.ye.aiassistant.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.ye.aiassistant.errors.ErrorCollector
import com.ye.aiassistant.llm.ToolCall
import com.ye.aiassistant.llm.ToolSpec
import com.ye.aiassistant.patch.FixGuard
import com.ye.aiassistant.patch.PatchPaths
import com.ye.aiassistant.patch.PatchProposal
import com.ye.aiassistant.patch.Proposals
import com.ye.aiassistant.patch.UnifiedDiff
import com.ye.aiassistant.settings.AiSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ToolContext(
    val project: Project,
    val minimal: Boolean,
    val onProposal: (PatchProposal) -> Unit,
)

interface AgentTool {
    val name: String
    val description: String
    fun parameters(): JsonObject
    fun run(args: JsonObject, ctx: ToolContext): String
    fun spec(): ToolSpec = ToolSpec(name, description, parameters())
}

private const val MAX_RESULT_CHARS = 20_000

/** 等 EDT 执行的上限：主线程万一被卡住，后台的 Agent 循环不能永久挂着。 */
private const val EDT_WAIT_SECONDS = 60L

/** Agent 模式下可用的工具集合。 */
class ToolRegistry(private val ctx: ToolContext, autoApply: Boolean) {

    private val tools: List<AgentTool> = buildList {
        add(ReadFileTool())
        add(ListFilesTool())
        add(SearchTextTool())
        add(GetErrorsTool())
        add(ProposeEditTool())
        add(WriteFileTool())
        add(GetOpenFilesTool())
        if (autoApply) add(ApplyEditTool())
    }

    fun specs(): List<ToolSpec> = tools.map { it.spec() }

    fun execute(call: ToolCall): String {
        val tool = tools.firstOrNull { it.name == call.name }
            ?: return "未知工具: ${call.name}。可用工具: ${tools.joinToString(", ") { it.name }}"
        val args = runCatching {
            if (call.arguments.isBlank()) JsonObject() else JsonParser.parseString(call.arguments).asJsonObject
        }.getOrElse { return "参数不是合法 JSON: ${call.arguments.take(300)}" }
        return try {
            tool.run(args, ctx).take(MAX_RESULT_CHARS)
        } catch (e: Exception) {
            "工具执行失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}

// ---------- 通用工具方法 ----------

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

private fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt

private fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean

private fun schema(required: List<String>, props: Map<String, JsonObject>): JsonObject = JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply { props.forEach { (k, v) -> add(k, v) } })
    add("required", JsonArray().apply { required.forEach { add(it) } })
}

private fun prop(type: String, description: String): JsonObject = JsonObject().apply {
    addProperty("type", type)
    addProperty("description", description)
}

private fun readAction(block: () -> String): String {
    var result = ""
    ApplicationManager.getApplication().runReadAction { result = block() }
    return result
}

/** 深度优先遍历项目文件；visit 返回 false 表示停止。 */
private fun walkFiles(root: VirtualFile, visit: (VirtualFile) -> Boolean) {
    var stopped = false
    fun go(file: VirtualFile) {
        if (stopped) return
        if (file.isDirectory) {
            if (file.name in SKIP_DIRS || file.name.startsWith(".")) return
            for (child in file.children ?: return) {
                go(child)
                if (stopped) return
            }
        } else {
            if (!visit(file)) stopped = true
        }
    }
    go(root)
}

private fun matchGlob(name: String, glob: String): Boolean {
    val sb = StringBuilder("^")
    for (ch in glob) {
        when (ch) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(ch.toString()))
        }
    }
    sb.append("$")
    return Regex(sb.toString(), RegexOption.IGNORE_CASE).matches(name)
}

private val SKIP_DIRS = setOf(
    ".git", ".idea", "build", "out", "target", "node_modules", "dist", ".gradle", ".venv", "venv",
    "vendor", ".next", "bin", "obj", ".kotlin", "gradle", ".git-rewrite",
)

private val BINARY_EXT = setOf(
    "jar", "zip", "gz", "png", "jpg", "jpeg", "gif", "ico", "pdf", "class", "so", "dylib", "dll", "exe",
    "woff", "woff2", "ttf", "mp3", "mp4", "mov", "lock", "bin", "webp", "svg",
)

// ---------- 工具实现 ----------

private class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "读取项目内某个文件的文本（带行号）。大文件请用 start_line/end_line 分段读。"
    override fun parameters() = schema(
        listOf("path"),
        mapOf(
            "path" to prop("string", "相对项目根目录的路径，如 src/main/java/com/demo/Foo.java"),
            "start_line" to prop("integer", "起始行（从 1 开始），可省略"),
            "end_line" to prop("integer", "结束行，可省略"),
        ),
    )

    override fun run(args: JsonObject, ctx: ToolContext): String {
        val path = args.str("path") ?: return "缺少参数 path"
        val file = PatchPaths.resolve(ctx.project, path) ?: return "找不到文件: $path"
        if (file.isDirectory) return "$path 是目录，请用 list_files"
        val text = readAction { Proposals.readText(file) }
        val lines = text.split("\n")
        val start = (args.int("start_line") ?: 1).coerceAtLeast(1)
        val end = (args.int("end_line") ?: lines.size).coerceAtMost(lines.size)
        if (start > end || start > lines.size) return "行号范围不合法（文件共 ${lines.size} 行）"
        val capped = end.coerceAtMost(start + 399)
        val body = (start..capped).joinToString("\n") { i -> "%5d| %s".format(i, lines[i - 1]) }
        val suffix = if (capped < lines.size) "\n...(文件共 ${lines.size} 行，已截断)" else ""
        return "$path（共 ${lines.size} 行）\n$body$suffix"
    }
}

private class ListFilesTool : AgentTool {
    override val name = "list_files"
    override val description = "列出项目里的文件（自动忽略 .git/build/out/node_modules 等目录）。"
    override fun parameters() = schema(
        emptyList(),
        mapOf(
            "dir" to prop("string", "相对目录，默认项目根目录"),
            "glob" to prop("string", "通配文件名，如 *.kt、*Test.java，可省略"),
            "max" to prop("integer", "最多返回条数，默认 200"),
        ),
    )

    override fun run(args: JsonObject, ctx: ToolContext): String {
        val project = ctx.project
        val root = args.str("dir")?.let { PatchPaths.resolve(project, it) } ?: PatchPaths.root(project)
            ?: return "无法定位项目根目录"
        val pattern = args.str("glob")
        val max = (args.int("max") ?: 200).coerceIn(1, 1000)
        val result = mutableListOf<String>()
        readAction {
            walkFiles(root) { file ->
                if (pattern == null || matchGlob(file.name, pattern)) {
                    result += PatchPaths.relative(project, file)
                }
                result.size < max
            }
            ""
        }
        return if (result.isEmpty()) "没有匹配的文件" else result.joinToString("\n")
    }
}

private class SearchTextTool : AgentTool {
    override val name = "search_text"
    override val description = "在项目里按关键字/正则搜索文本，返回「文件:行:内容」。"
    override fun parameters() = schema(
        listOf("query"),
        mapOf(
            "query" to prop("string", "关键字或正则"),
            "regex" to prop("boolean", "是否按正则匹配，默认 false"),
            "case_sensitive" to prop("boolean", "是否区分大小写，默认 false"),
            "max" to prop("integer", "最多返回条数，默认 40"),
        ),
    )

    override fun run(args: JsonObject, ctx: ToolContext): String {
        val query = args.str("query") ?: return "缺少参数 query"
        val useRegex = args.bool("regex") ?: false
        val caseSensitive = args.bool("case_sensitive") ?: false
        val max = (args.int("max") ?: 40).coerceIn(1, 200)
        val project = ctx.project
        val root = PatchPaths.root(project) ?: return "无法定位项目根目录"
        val regex = runCatching {
            val options = if (caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
            if (useRegex) Regex(query, options) else Regex(Regex.escape(query), options)
        }.getOrElse { return "正则不合法: ${it.message}" }

        val hits = mutableListOf<String>()
        readAction {
            walkFiles(root) { file ->
                if (file.length > 1_000_000) return@walkFiles true
                if (file.extension?.lowercase() in BINARY_EXT) return@walkFiles true
                val text = runCatching { Proposals.readText(file) }.getOrNull()
                if (text != null && "\u0000" !in text) {
                    text.split("\n").forEachIndexed { i, line ->
                        if (hits.size < max && regex.containsMatchIn(line)) {
                            hits += "${PatchPaths.relative(project, file)}:${i + 1}: ${line.trim().take(200)}"
                        }
                    }
                }
                hits.size < max
            }
            ""
        }
        return if (hits.isEmpty()) "没有匹配结果" else hits.joinToString("\n")
    }
}

private class GetErrorsTool : AgentTool {
    override val name = "get_errors"
    override val description = "读取某个文件当前在 IDE 里的编译/检查报错（文件需已在编辑器中打开）。"
    override fun parameters() = schema(
        emptyList(),
        mapOf("path" to prop("string", "相对路径；省略则取当前编辑器里的文件")),
    )

    override fun run(args: JsonObject, ctx: ToolContext): String {
        val project = ctx.project
        val manager = FileEditorManager.getInstance(project)
        val target = args.str("path")?.let { PatchPaths.resolve(project, it) }
        val editor = if (target == null) {
            manager.selectedTextEditor
        } else {
            manager.allEditors.filterIsInstance<TextEditor>().firstOrNull { it.file == target }?.editor
        } ?: return "目标文件没有在编辑器里打开，无法读取报错"
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val errors = ErrorCollector.collect(project, editor)
        if (errors.isEmpty()) return "该文件当前没有报错"
        val name = file?.let { PatchPaths.relative(project, it) } ?: "当前文件"
        return errors.joinToString("\n") { "$name:${it.startLine}: ${it.description}\n    ${it.text}" }
    }
}

private class ProposeEditTool : AgentTool {
    override val name = "propose_edit"
    override val description = "提交 unified diff 补丁，生成待用户确认的改动（在 IDEA 中以 Diff 展示），不会直接写文件。"
    override fun parameters() = schema(
        listOf("diff"),
        mapOf(
            "diff" to prop("string", "unified diff 文本，含 ---/+++ 与 @@ 行"),
            "summary" to prop("string", "一句话说明这次改了什么"),
        ),
    )

    override fun run(args: JsonObject, ctx: ToolContext): String {
        val diff = args.str("diff") ?: return "缺少参数 diff"
        val summary = args.str("summary") ?: "AI 改动"
        val patches = UnifiedDiff.extract(diff).ifEmpty { return "没有解析出补丁，请检查 unified diff 格式" }
        if (ctx.minimal) {
            val sizeCheck = FixGuard.validateSize(patches, AiSettings.get().maxFixLines)
            if (!sizeCheck.ok) {
                return "最小修改模式拒绝了这次改动：${sizeCheck.message()} 请给出更小的补丁。"
            }
        }
        val created = mutableListOf<String>()
        for (patch in patches) {
            val latch = CountDownLatch(1)
            var error: String? = null
            ApplicationManager.getApplication().invokeLater {
                try {
                    val proposal = Proposals.fromPatch(ctx.project, patch, summary)
                    ctx.onProposal(proposal)
                    created += "${patch.path}（${proposal.changedStats()}）"
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(EDT_WAIT_SECONDS, TimeUnit.SECONDS)) return "生成改动超时（IDE 主线程繁忙）"
            error?.let { return "生成改动失败: $it" }
        }
        return "已生成待确认改动：${created.joinToString(", ")}。用户会在 Diff 中确认后应用。"
    }
}

private class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description = "整体写入或新建一个文件（生成待确认 Diff）。仅在新建文件或必须整体重写时使用。"
    override fun parameters() = schema(
        listOf("path", "content"),
        mapOf(
            "path" to prop("string", "相对项目根目录的路径"),
            "content" to prop("string", "完整文件内容"),
            "summary" to prop("string", "一句话说明"),
        ),
    )

    override fun run(args: JsonObject, ctx: ToolContext): String {
        val path = args.str("path") ?: return "缺少参数 path"
        val content = args.str("content") ?: return "缺少参数 content"
        val summary = args.str("summary") ?: "写入文件"
        val existing = PatchPaths.resolve(ctx.project, path)
        if (existing != null && ctx.minimal) {
            return "最小修改模式下不允许整体重写已有文件（$path），请改用 propose_edit 给出最小 diff。"
        }
        val original = if (existing != null) readAction { Proposals.readText(existing) } else ""
        val latch = CountDownLatch(1)
        var error: String? = null
        ApplicationManager.getApplication().invokeLater {
            try {
                val proposal = Proposals.fromContent(
                    ctx.project, existing, path, original, content, summary,
                    content.split("\n").size, original.split("\n").size,
                )
                ctx.onProposal(proposal)
            } catch (e: Exception) {
                error = e.message
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(EDT_WAIT_SECONDS, TimeUnit.SECONDS)) return "写入超时（IDE 主线程繁忙）"
        return error?.let { "生成改动失败: $it" } ?: "已生成待确认改动：$path"
    }
}

private class ApplyEditTool : AgentTool {
    override val name = "apply_edit"
    override val description = "直接把 unified diff 应用到文件（立即写盘）。仅在用户开启「允许 Agent 直接改文件」时可用。"
    override fun parameters() = schema(
        listOf("diff"),
        mapOf("diff" to prop("string", "unified diff 文本")),
    )

    override fun run(args: JsonObject, ctx: ToolContext): String {
        val diff = args.str("diff") ?: return "缺少参数 diff"
        val patches = UnifiedDiff.extract(diff).ifEmpty { return "没有解析出补丁" }
        for (patch in patches) {
            val latch = CountDownLatch(1)
            var error: String? = null
            ApplicationManager.getApplication().invokeLater {
                try {
                    Proposals.apply(Proposals.fromPatch(ctx.project, patch, "直接应用"))
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(EDT_WAIT_SECONDS, TimeUnit.SECONDS)) return "应用超时（IDE 主线程繁忙）"
            error?.let { return "应用失败: $it" }
        }
        return "已写入文件：${patches.joinToString(", ") { it.path }}"
    }
}

private class GetOpenFilesTool : AgentTool {
    override val name = "get_open_files"
    override val description = "列出用户当前打开的文件，以及光标所在的文件。"
    override fun parameters() = schema(emptyList(), emptyMap())

    override fun run(args: JsonObject, ctx: ToolContext): String {
        val project = ctx.project
        val manager = FileEditorManager.getInstance(project)
        val selected = manager.selectedFiles.firstOrNull()
        val all = manager.openFiles.joinToString("\n") { "- " + PatchPaths.relative(project, it) }
        return buildString {
            appendLine("当前选中文件: ${selected?.let { PatchPaths.relative(project, it) } ?: "无"}")
            appendLine("已打开文件:")
            appendLine(all.ifBlank { "无" })
        }.trim()
    }
}
