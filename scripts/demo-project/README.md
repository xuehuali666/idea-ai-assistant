# demo-project

给 `scripts/shoot-panel.sh` 用的示例工程 —— `runIde` 会打开它，然后跑 `AutoTestStartup`
把 AI 面板自动展开并离屏出图。

**必须放在仓库里，不要放 `/tmp`。** 机器重启会清空 `/tmp`，而 `runIde` 打开一个不存在的
工程时不会报错，只会一直等 —— 出图脚本会白等到超时（`exit value 143`），
排查起来非常费劲（2026-09-28 就这么浪费了两轮渲染）。

`build.gradle.kts` 会在跑 `-Pautotest` 时确保这个目录存在。
