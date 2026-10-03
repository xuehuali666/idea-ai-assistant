plugins {
    id("org.jetbrains.intellij.platform") version "2.19.0"
    kotlin("jvm") version "2.2.21"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 直接使用本机安装的 IDEA 作为编译/运行目标（避免下载 1GB+ 的 IDE 发行包）
        local(providers.gradleProperty("platformPath").get())
    }
    implementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    // 本地自动化验证用：直接打开测试项目并自动展开 AI 面板
    if (providers.gradleProperty("autotest").isPresent) {
        // 示例工程放在仓库里，**不要用 /tmp**：机器重启会清空 /tmp，而 runIde 打开一个
        // 不存在的工程不会报错、只会一直等（出图脚本白等到超时，exit value 143）。
        val demoProject = layout.projectDirectory.dir("scripts/demo-project").asFile
        if (!demoProject.isDirectory) {
            demoProject.mkdirs()
            demoProject.resolve("src").mkdirs()
            demoProject.resolve("src/Main.java").writeText("public class Main {}\n")
        }
        args(demoProject.absolutePath)
        jvmArgs("-Dai.assistant.autotest=true")
        // 再加 -Pdemo 会塞几条示例消息并离屏出一张面板截图（不用连模型、不用截图权限）
        if (providers.gradleProperty("demo").isPresent) {
            jvmArgs("-Dai.assistant.demo=true")
        }
        // 这几个和 -Pdemo 独立：不加 -Pdemo 时渲染的是空面板
        providers.gradleProperty("demoOut").orNull?.let { jvmArgs("-Dai.assistant.demo.out=$it") }
        providers.gradleProperty("demoSize").orNull?.let { jvmArgs("-Dai.assistant.demo.size=$it") }
        providers.gradleProperty("demoSettings").orNull?.let { jvmArgs("-Dai.assistant.demo.settings=$it") }
        // 验证设置项：-PdemoFont=12 -PdemoBubble=75
        providers.gradleProperty("demoFont").orNull?.let { jvmArgs("-Dai.assistant.demo.font=$it") }
        providers.gradleProperty("demoBubble").orNull?.let { jvmArgs("-Dai.assistant.demo.bubble=$it") }
        // 验证「界面尺寸」：-PdemoDensity=85 / 130
        providers.gradleProperty("demoDensity").orNull?.let { jvmArgs("-Dai.assistant.demo.density=$it") }
        // 弹窗出图：-PdemoDialog=errors|qna -PdemoDialogOut=/tmp/d.png
        providers.gradleProperty("demoDialog").orNull?.let { jvmArgs("-Dai.assistant.demo.dialog=$it") }
        providers.gradleProperty("demoDialogOut").orNull?.let { jvmArgs("-Dai.assistant.demo.dialog.out=$it") }
        // 打印布局树（排查「有东西重叠 / 控件被裁」）
        if (providers.gradleProperty("demoDump").isPresent) {
            jvmArgs("-Dai.assistant.demo.dump=true")
        }
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        id = "com.ye.ai-assistant"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        description = """
            <p>自用 IDEA AI 助手插件：Agent 模式、问答（Q&amp;A）模式、报错最小化修复，改动以 Diff 预览后应用。</p>
        """.trimIndent()
        vendor {
            name = "ye"
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}
