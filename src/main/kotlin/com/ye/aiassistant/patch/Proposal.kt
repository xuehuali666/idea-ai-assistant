package com.ye.aiassistant.patch

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 一条待确认的 AI 改动：右侧内容可编辑，用户在 Diff 里可以直接改。 */
class PatchProposal(
    val project: Project,
    val file: VirtualFile?,
    val pathLabel: String,
    val originalText: String,
    val isNewFile: Boolean,
    val summary: String,
    val added: Int,
    val removed: Int,
    val content: DocumentContent,
) {
    val id: String = UUID.randomUUID().toString().substring(0, 8)

    @Volatile
    var applied: Boolean = false
        internal set

    @Volatile
    var rejected: Boolean = false
        internal set

    fun currentText(): String = content.document.text

    val fileType: FileType
        get() = file?.fileType
            ?: FileTypeManager.getInstance().getFileTypeByFileName(pathLabel.substringAfterLast('/'))

    fun changedStats(): String = "+$added / -$removed"
}

object Proposals {

    /** 用统一 diff 生成改动提案（在 EDT 上调用）。 */
    fun fromPatch(project: Project, patch: FilePatch, summary: String): PatchProposal {
        val file = PatchPaths.resolve(project, patch.path)
        val original = file?.let { readText(it) }.orEmpty()
        val updated = try {
            if (patch.isNewFile) UnifiedDiff.apply("", patch) else UnifiedDiff.apply(original, patch)
        } catch (e: PatchException) {
            throw PatchException("${patch.path}: ${e.message}")
        }
        return fromContent(project, file, patch.path, original, updated, summary, patch.addedCount, patch.removedCount)
    }

    fun fromContent(
        project: Project,
        file: VirtualFile?,
        pathLabel: String,
        original: String,
        updated: String,
        summary: String,
        added: Int = 0,
        removed: Int = 0,
    ): PatchProposal {
        val fileType = file?.fileType
            ?: FileTypeManager.getInstance().getFileTypeByFileName(pathLabel.substringAfterLast('/'))
        val content = DiffContentFactory.getInstance()
            .createEditable(project, updated, fileType ?: PlainTextFileType.INSTANCE)
        return PatchProposal(
            project = project,
            file = file,
            pathLabel = pathLabel,
            originalText = original,
            isNewFile = file == null,
            summary = summary,
            added = added,
            removed = removed,
            content = content,
        )
    }

    /** 读文件内容：优先读编辑器里的（含未保存改动），没有编辑器就读磁盘。 */
    fun readText(file: VirtualFile): String {
        var text = ""
        ApplicationManager.getApplication().runReadAction {
            val doc: Document? = FileDocumentManager.getInstance().getDocument(file)
            text = doc?.text ?: VfsUtilCore.loadText(file)
        }
        return text
    }

    /** 打开 IDEA 原生 Diff 窗口：左边当前内容，右边 AI 建议（可编辑）。 */
    fun showDiff(proposal: PatchProposal) {
        val factory = DiffContentFactory.getInstance()
        val left = if (proposal.isNewFile) {
            factory.createEmpty()
        } else {
            factory.create(proposal.project, proposal.originalText, proposal.fileType)
        }
        val request = SimpleDiffRequest(
            "AI 修改建议 — ${proposal.pathLabel}",
            left,
            proposal.content,
            "当前内容",
            "AI 建议（可直接编辑）",
        )
        DiffManager.getInstance().showDiff(proposal.project, request, DiffDialogHints.FRAME)
    }

    fun apply(proposal: PatchProposal) {
        if (proposal.applied) return
        val project = proposal.project
        val text = proposal.currentText()
        val file = proposal.file
            ?: createFile(project, proposal.pathLabel, text)
            ?: throw PatchException("无法创建文件 ${proposal.pathLabel}（请检查路径是否在项目内）")
        val doc = FileDocumentManager.getInstance().getDocument(file)
            ?: throw PatchException("无法打开 ${proposal.pathLabel} 的文档")
        // 提案是「原文 + 补丁」算出来的。如果目标文件在生成提案之后被改过，
        // 直接 setText 会把用户自己的改动悄悄冲掉 —— 宁可拒绝，也不要覆盖别人的编辑。
        if (!proposal.isNewFile && doc.text != proposal.originalText) {
            throw PatchException(
                "${proposal.pathLabel} 在生成提案之后已被修改，为避免覆盖你的改动，本次不应用。\n" +
                    "请重新生成一次改动，或打开 Diff 窗口手动合并。",
            )
        }
        CommandProcessor.getInstance().executeCommand(project, {
            ApplicationManager.getApplication().runWriteAction { doc.setText(text) }
        }, "AI 应用修改", "ai-assistant")
        FileDocumentManager.getInstance().saveDocument(doc)
        proposal.applied = true
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    fun revert(proposal: PatchProposal) {
        val project = proposal.project
        val file = proposal.file
        if (proposal.isNewFile) {
            if (file != null && proposal.applied) {
                ApplicationManager.getApplication().runWriteAction { file.delete(proposal) }
            }
            proposal.applied = false
            return
        }
        if (file == null) throw PatchException("找不到 ${proposal.pathLabel}，无法撤销")
        val doc = FileDocumentManager.getInstance().getDocument(file)
            ?: throw PatchException("无法打开 ${proposal.pathLabel} 的文档")
        // 同样的道理：应用之后如果用户又改过，撤销会把那些改动一起吃掉。
        if (proposal.applied && doc.text != proposal.currentText()) {
            throw PatchException(
                "${proposal.pathLabel} 在应用之后又被修改过，撤销会覆盖这些改动，已中止。\n" +
                    "请在编辑器里用 Ctrl+Z 逐步回退。",
            )
        }
        CommandProcessor.getInstance().executeCommand(project, {
            ApplicationManager.getApplication().runWriteAction { doc.setText(proposal.originalText) }
        }, "AI 撤销修改", "ai-assistant")
        FileDocumentManager.getInstance().saveDocument(doc)
        proposal.applied = false
    }

    private fun createFile(project: Project, path: String, text: String): VirtualFile? {
        // 新建文件也不许爬出项目目录
        if (path.isBlank() || path.split('/').any { it == ".." }) return null
        val base = ProjectRootManager.getInstance(project).contentRoots.firstOrNull() ?: project.baseDir ?: return null
        var result: VirtualFile? = null
        ApplicationManager.getApplication().runWriteAction {
            runCatching {
                val parentPath = path.substringBeforeLast('/', "")
                val dir = VfsUtil.createDirectoryIfMissing(base, parentPath)
                val name = path.substringAfterLast('/')
                val created = dir.createChildData(base, name)
                VfsUtil.saveText(created, text)
                result = created
            }
        }
        return result
    }
}

object PatchPaths {

    /** 项目根目录：优先取 content root，退化为 baseDir。 */
    fun root(project: Project): VirtualFile? =
        ProjectRootManager.getInstance(project).contentRoots.firstOrNull() ?: project.baseDir

    fun resolve(project: Project, raw: String): VirtualFile? {
        val cleaned = raw.trim().removeSurrounding("\"").removePrefix("./")
        val base = ProjectRootManager.getInstance(project).contentRoots.firstOrNull() ?: project.baseDir ?: return null
        if (cleaned.isEmpty()) return null
        val file = if (cleaned.startsWith("/")) {
            LocalFileSystem.getInstance().findFileByPath(cleaned) ?: return null
        } else {
            base.findFileByRelativePath(cleaned) ?: return null
        }
        // 绝对路径和相对路径都要挡住项目外的东西：`../../x` 这种相对路径同样能爬出去，
        // 而 read_file / propose_edit 都直接吃模型给的 path。
        return if (VfsUtilCore.isAncestor(base, file, false) || file == base) file else null
    }

    fun relative(project: Project, file: VirtualFile): String {
        val base = root(project) ?: return file.path
        return VfsUtilCore.getRelativePath(file, base, '/') ?: file.path
    }
}

/** 项目级：保存所有改动提案，并广播给 UI。 */
@Service(Service.Level.PROJECT)
class ProposalService {

    private val listeners = CopyOnWriteArrayList<(PatchProposal) -> Unit>()

    fun add(proposal: PatchProposal) {
        listeners.forEach { runCatching { it(proposal) } }
    }

    fun addListener(listener: (PatchProposal) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (PatchProposal) -> Unit) {
        listeners.remove(listener)
    }

    companion object {
        fun getInstance(project: Project): ProposalService = project.service()
    }
}
