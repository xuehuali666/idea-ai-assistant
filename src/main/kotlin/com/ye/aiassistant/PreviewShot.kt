package com.ye.aiassistant

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * 本地视觉验证用：把一个已经布局好的 Swing 组件离屏渲染成 PNG。
 *
 * 为什么不用截屏：`screencapture` 需要「屏幕录制」权限，CI/终端里经常拿不到，
 * 而且截出来带整个 IDE 窗口，没法只看面板。离屏渲染既不需要权限，尺寸也可控
 * （可以直接按右侧工具窗口的真实宽度 420px 出图）。
 *
 * 只在 `-Dai.assistant.demo=true` 时被 AutoTestStartup 调用。
 */
object PreviewShot {

    /**
     * 按指定尺寸重排并渲染。
     * 组件必须已经在屏幕上（已 realize），否则 JEditorPane 的 HTML 排版拿不到字体度量。
     */
    fun write(component: JComponent, path: String, width: Int, height: Int) {
        component.setSize(width, height)
        // 消息卡片的高度依赖「当前宽度」，而宽度要等父容器排完一遍才有。
        // 只排一遍的话第一轮会按兜底宽度（480）算高度，正文比实际需要的矮，直接显示不全。
        // 多走几遍让测量收敛（卡片拿到真实宽度后重算高度）。
        // 卡片高度依赖宽度、宽度又依赖父容器给的宽度，是个反馈环，排一遍不够。
        //
        // ⚠️ 每一遍之前必须先把布局缓存失效：BoxLayout 会把子项的首选尺寸缓存起来，
        // 子项的首选高度变了它并不知道，于是容器高度还是旧值 —— 表现就是
        // 「卡片需要 165px、只分到 124px」，卡片内部两块内容直接叠在一起。
        repeat(6) {
            invalidateTree(component)
            layoutTree(component)
        }
        // 出图要从顶部看：seed 数据比视口高时，加卡时的自动滚底会把第一张卡卷出视口，
        // 渲染出来「第一张卡只剩一条缝」，看着像布局塌了（其实是滚动位置）。
        // 布局收敛完、printAll 之前把所有滚动条归零。
        resetScrolls(component)

        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = component.background ?: java.awt.Color.WHITE
        g.fillRect(0, 0, width, height)
        component.printAll(g)
        g.dispose()

        val file = File(path)
        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)

        if (System.getProperty("ai.assistant.demo.dump") == "true") dump(component, 0)
    }

    /**
     * 把布局树打出来（`-PdemoDump`）。
     *
     * 排版出问题时，看图只能看出「有东西重叠了」，看不出是谁压着谁 ——
     * 有了每个容器的真实 bounds，一眼就能知道是哪个容器的首选高度算少了。
     */
    fun dump(component: java.awt.Container, depth: Int, maxDepth: Int = 9) {
        val pad = "  ".repeat(depth)
        val b = component.bounds
        println(
            "LAYOUT $pad${component.javaClass.simpleName} " +
                "bounds=${b.x},${b.y},${b.width}x${b.height} " +
                "pref=${component.preferredSize.width}x${component.preferredSize.height} " +
                "layout=${component.layout?.javaClass?.simpleName}",
        )
        if (depth >= maxDepth) return
        for (child in component.components) {
            if (child is java.awt.Container) dump(child, depth + 1, maxDepth) else {
                val cb = child.bounds
                println("LAYOUT $pad  - ${child.javaClass.simpleName} bounds=${cb.x},${cb.y},${cb.width}x${cb.height}")
            }
        }
    }

    /** 把整棵树的布局缓存都丢掉，逼 BoxLayout 之类的布局重新量一遍子项。 */
    private fun invalidateTree(container: java.awt.Container) {
        container.invalidate()
        (container.layout as? java.awt.LayoutManager2)?.invalidateLayout(container)
        for (child in container.components) {
            if (child is java.awt.Container) invalidateTree(child)
        }
    }

    /** Container.validateTree() 是 protected，自己从根往下走一遍。 */
    private fun layoutTree(container: java.awt.Container) {
        container.doLayout()
        for (child in container.components) {
            if (child is java.awt.Container) layoutTree(child)
        }
    }

    /** 把树里所有滚动条归零（见 write 里的注释）。 */
    private fun resetScrolls(container: java.awt.Container) {
        for (child in container.components) {
            if (child is javax.swing.JScrollPane) {
                child.verticalScrollBar.value = 0
                child.horizontalScrollBar.value = 0
            }
            if (child is java.awt.Container) resetScrolls(child)
        }
    }
}
