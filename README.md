# AI Assistant — IntelliJ IDEA 插件

写代码时被报错卡住 → 在编辑器里按一个键 → AI 给出**最小改动** → 用 IDEA 自带的红绿 Diff 窗口看一眼 → 点「应用」。

完全独立的插件，不依赖任何云服务后台，直连任意 **OpenAI 兼容**接口（默认配置对齐 pi 的 `opencode zen` 网关）。

## 功能

| 功能 | 说明 |
| --- | --- |
| **修复当前文件报错** | 收集光标所在文件的报错 → 勾选要修的报错 → AI 只改和报错相关的行 → Diff 预览 → 应用 |
| **问答模式** | 普通对话，只回答不改代码 |
| **Agent 模式** | AI 可以 `read_file` / `list_files` / `search_text` / `get_errors` / `propose_edit`，改动一律先变成 Diff 提案 |
| **Q&A 抽取** | 从 AI 上一轮回复里抽出「选择题」，用方向键选完答案，自动把 Q&A 汇总写回输入框（和 pi 的 qna 插件同一套协议） |
| **最小修改模式** | 默认开启：提示词强约束 + 行数上限 + 报错行范围校验，防止 AI 顺手重构你的代码 |
| **Diff 预览** | IDEA 原生 Diff（红=删除，绿=新增），右侧可以直接编辑，改完再点应用；应用后可以 `Ctrl+Z` 撤销 |

## 安装

```bash
./gradlew buildPlugin          # 产物：build/distributions/idea-ai-assistant-0.1.0.zip
```

IDEA 里 `Settings → Plugins → ⚙️ → Install Plugin from Disk…` 选上面那个 zip，重启。

也可以直接跑沙箱 IDE 调试（会自动带上插件）：

```bash
./gradlew runIde
```

## 配置

`Settings → Tools → AI Assistant`：

- **Base URL**：默认 `https://opencode.ai/zen/go/v1`（pi 用的网关），也可以填 `https://api.deepseek.com`、`http://localhost:11434/v1` 等
- **API Key**：必填
- **模型**：默认 `deepseek-v4-flash`
- **代理**：留空直连；需要时填 `http://127.0.0.1:7897`
- **额外请求头**：每行一条 `Key: Value`，给自建网关用
- 其余开关：最小修改模式、自动打开 Diff、显示思考过程、允许 Agent 直接写文件（默认关）
- **对话外观**（三个旋钮，互相独立）：
  - **对话字体大小**：跟随 IDE / 9 / 10 / 11 / 12 / 13 / 14 / 16。**数字就是实际显示的字号**
    （见下面的「HTML 字号只能用 pt」）。「跟随 IDE」= 界面字号 − 1，最多按 13 算。
  - **界面尺寸（块大小）**：紧凑 85% / 标准 100% / 宽松 115% / 很宽松 130%。
    管「块」——内边距、圆角、按钮高度、列表行高、弹窗尺寸。字号另有一个 ±15% 的自动微调，
    所以把字调到 9 之后控件不会还留着一圈给 12 号字的空白。
  - **消息气泡宽度**：铺满 / 88% / 75% / 60%。
  改完 Apply 会立刻重排已经打开的面板（`ChatPanel.refreshAppearance()`）。

> opencode 网关会自动带上 `x-opencode-session` / `x-opencode-client` 请求头，无需手工配置。

点「测试连接」可以立刻验证配置是否通。

## 快捷键

| 快捷键 | 动作 |
| --- | --- |
| `Alt+Shift+E` | 修复当前文件报错 |
| `Alt+Shift+Q` | 从上一轮 AI 回复里抽取问答 |
| `Alt+Shift+A` | 打开/聚焦 AI 面板 |

## 典型用法

1. 打开有报错的文件，光标停在红线上
2. `Alt+Shift+E`
3. 在弹窗里勾选要修的报错（默认选中光标那一行）→ 确定
4. 等 AI 出补丁，弹出 Diff 窗口：左边是当前内容，右边是 AI 建议
5. 看着不顺眼可以直接在右边改，然后点「应用」；不满意就关掉窗口
6. 应用错了 → `Ctrl+Z`

## 代码结构

```
src/main/kotlin/com/ye/aiassistant/
├── settings/     AiSettings.kt（全局设置）· AiConfigurable.kt（设置界面）
├── llm/          LlmClient.kt（JDK HttpClient + Gson，SSE 流式 + function calling）
├── agent/        Prompts.kt（提示词）· Tools.kt（工具集）· ContextCollector.kt（当前文件上下文）
├── patch/        UnifiedDiff.kt（解析/套用 unified diff）· FixGuard.kt（最小修改校验）
│                 Proposal.kt（改动提案 + Diff 窗口 + 应用/撤销）
├── errors/       ErrorCollector.kt（读 DaemonCodeAnalyzer 的报错）
├── qna/          Qna.kt（[Q]/[O] 协议解析）
├── ui/           ChatPanel.kt（主面板）· Cards.kt（消息/提案卡片）· Html.kt（Markdown 渲染）
│                 Dialogs.kt（选报错 / 问答弹窗）· AiToolWindow.kt（工具窗口 + FixFlow）
└── actions/      Actions.kt（四个 Action 入口）
```

## 尺寸令牌：一切内边距都写 `Theme.pad(n)`

`Theme` 里只有一个换算点：

```kotlin
Theme.pad(9)          // 设计值 9 -> 实际像素（含 JBUI 的 HiDPI 因子 + 用户选的「界面尺寸」）
Theme.row(LEFT, 6, a, b)   // 建一个横排，间距会自动跟着「界面尺寸」刷新
```

设计基准是「12 号字 + 标准块大小」。**不要在业务代码里散着写 `JBUI.scale(9)` 或裸数字** ——
那样「界面尺寸」这个设置就管不到它，用户调了大小只变一半，比不能调更难受。

两个例外，写在注释里说明了原因：

- **平台外壳不动**：弹窗底部的「确定 / 取消」是平台按钮，和 IDEA 自己所有弹窗一致，
  按插件字号缩小反而和整个 IDE 格格不入。
- **`AllIcons` 图标本身不缩放**（平台图标有自己的尺寸），只缩放它四周的点击区域。

`FlowLayout` 的 `hgap` 是构造时定死的字段，改设置时无法现取 —— 所以 `Theme.row()` 会把
面板登记进一个 `WeakHashMap`，`Theme.refreshSpacing()` 统一刷新（用弱引用，不会 leaking 工具窗口）。

## HTML 字号只能用 pt，不能用 px

`Html.kt` 里给 Swing 的 HTML 写字号时，**必须写 `pt`**：

```kotlin
// ✅ 1:1，和 deriveFont(11f) 的标签/按钮同号
"font-size:${Theme.bodySize.toInt()}pt"
// ❌ Swing 按 96dpi 把 CSS px 换算成 pt（×4/3）：11px 实际是 14 号
"font-size:${Theme.bodySize}px"
```

实测（普通 Swing，`javax.swing.text.html.StyleSheet`）：

| font-size | 解析出的字号 | | font-size | 解析出的字号 |
| --- | --- | --- | --- | --- |
| `9px` | 12 | | `9pt` | 9 |
| `11px` | 14 | | `11pt` | 11 |
| `12px` | 16 | | `12pt` | 12 |
| `16px` | 21 | | `16pt` | 16 |

写 `px` 的后果是「设置里选多小都比标称值大 30%」，用户会觉得字号设置根本没用。
`pt` 是唯一和 `deriveFont` 数值对齐的单位。

## 安全边界（应用改动前会检查）

- **不会覆盖你后来的编辑**：提案是「原文 + 补丁」算出来的。如果生成提案之后你又改了目标文件，
  点「应用」会被拒绝并提示重新生成 —— 而不是悄悄把你的改动 setText 掉。撤销同理。
- **文件路径被限制在项目内**：绝对路径和相对路径都会校验，`../../x` 爬不出去；新建文件也拒绝含 `..` 的路径。
- **失败会说清楚**：建不出文件 / 打不开文档这类情况会抛出真实原因，不会出现「显示已应用但什么都没写」。

## 已知限制 / 后续可做

- 报错只取**当前文件**的（编译输出里的报错还没接）
- 只支持 OpenAI 兼容协议（Anthropic 原生协议没接）
- Agent 工具的 `apply_edit` 默认关闭，所有改动都走 Diff 预览
- 输入框里**组字中的回车不会发送**（中文输入法选候选词时按回车是确认候选，插件会识别并忽略）；
  生成过程中按 **Esc** 可以停止
- 一轮对话（含多次「模型 → 工具 → 模型」）合并在**一张** AI 卡片里，工具日志以代码块形式内嵌；
  底部的**「重新生成」**把上一轮整块撤掉重跑（停止 / 出错 / 答得不好之后用）
- 代码块是**折行**显示的（面板没有横向滚动条，`<pre>` 会把长行直接裁掉）。
  想按原样复制代码请看 Diff 窗口或文件本身

## 本地视觉验证

改完面板样式要亲眼看到渲染结果，不用连模型、也不需要屏幕录制权限：

```bash
./gradlew runIde -Pautotest -Pdemo
```

会自动打开 AI 面板 → 塞进几条示例消息（用户消息 / 带代码块和 diff 的 AI 回复 / 改动卡片）
→ 按 430px 宽排完版后**离屏渲染**成 PNG（默认 `/tmp/idea-ai-panel.png`）。

| 参数 | 作用 |
| --- | --- |
| `-PdemoSize=430x1500` | 出图尺寸（默认 430x900，就是右侧工具窗口的常见宽度） |
| `-PdemoOut=/tmp/x.png` | 出图路径。**只给这个、不给 `-Pdemo`，渲染的就是空状态**（未开始对话的样子） |
| `-PdemoSettings=/tmp/s.png` | 顺便把设置页也出一张 |
| `-PdemoFont=12` | 用指定的对话字号渲染（不填则用设置里的值） |
| `-PdemoDensity=130` | 用指定的界面尺寸百分比渲染（85 / 100 / 115 / 130） |
| `-PdemoBubble=75` | 用指定的气泡宽度百分比渲染 |
| `-PdemoDialog=errors` | 改成渲染「修复报错」弹窗的内容区（`qna` = Q&A 抽取弹窗） |
| `-PdemoDump` | 把每个容器的 bounds / 首选尺寸打到 stdout（排查「谁压着谁」） |

脚本版（推荐，出图后会自动把沙箱 IDE 关干净）：

```bash
scripts/shoot-panel.sh /tmp/panel.png 430x1500 -PdemoSettings=/tmp/settings.png
scripts/shoot-panel.sh /tmp/small.png 430x1500 -PdemoFont=9 -PdemoBubble=60
scripts/shoot-panel.sh /tmp/loose.png 430x1500 -PdemoDensity=130
scripts/shoot-panel.sh empty /tmp/empty.png 430x820            # 空状态
scripts/shoot-panel.sh dialog:errors /tmp/err.png 430x900      # 弹窗（内容区）
```

底层用的是 `PreviewShot`（`JComponent.printAll` 到 `BufferedImage`）：
比 `screencapture` 可靠，尺寸可控，而且只出面板本身、不带整个 IDE 窗口。
注意**两次 runIde 之间要把沙箱 IDE 关干净**，否则第二次会因为沙箱目录被占用而卡住。

`-Dai.assistant.autotest=true` 是更早的调试钩子：打开项目后自动展开面板（`./gradlew runIde -Pautotest`）。
