#!/bin/zsh
# 把 AI 助手的界面离屏渲染成 PNG，用来核对排版（不需要屏幕录制权限、不带整个 IDE 窗口）。
#
# 用法:
#   scripts/shoot-panel.sh <输出png> <宽x高> [额外 -P 参数...]      # 带示例消息的面板
#   scripts/shoot-panel.sh empty <输出png> <宽x高> [...]            # 空状态（没开始对话的样子）
#   scripts/shoot-panel.sh dialog:errors <输出png> <宽x高> [...]    # 「修复报错」弹窗（内容区）
#   scripts/shoot-panel.sh dialog:qna <输出png> <宽x高> [...]       # 「Q&A 抽取」弹窗（内容区）
#
# 例:
#   scripts/shoot-panel.sh /tmp/panel.png 430x1500 -PdemoSettings=/tmp/settings.png
#   scripts/shoot-panel.sh /tmp/small.png 430x1500 -PdemoFont=9 -PdemoBubble=60
#   scripts/shoot-panel.sh /tmp/loose.png 430x1500 -PdemoDensity=130
#   scripts/shoot-panel.sh dialog:errors /tmp/d.png 430x900
#
# 说明:
#   · 走 runIde 起真实沙箱 IDE，再用 PreviewShot 离屏渲染。
#   · 出图后会把沙箱 IDE 杀掉：残留的旧实例会占住目录锁，下一次 runIde 直接以
#     `DirectoryLock ... exit value 6` 失败。
#   · 杀进程的匹配串用 "[I]U-..." 这种写法，既能避开 grep 自己，也**不能**写成
#     `[I]` + `IU-...`（拼出来是 [I]IU-...，永远匹配不到，等于没杀）。
#   · 冷启动可能很久：机器刚重启 / 重建索引时实测超过 360s，所以等待上限给到 900s。
#     等太久会白等，但太短会像之前那样把已经起好的 IDE 掐死（exit value 143）。
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
MODE=panel
case "${1:-}" in
  empty) MODE=empty; shift ;;
  dialog:*) MODE="${1}"; shift ;;
esac
OUT="${1:-/tmp/panel.png}"
SIZE="${2:-430x1500}"
shift 2 || true
NEEDLE='[I]U-2026.1.2'

cd "$PROJ" || exit 1
rm -f "$OUT"

GRADLE_ARGS=(-Pautotest -PdemoSize="$SIZE" "$@")
case "$MODE" in
  panel)  GRADLE_ARGS+=(-Pdemo -PdemoOut="$OUT") ;;
  empty)  GRADLE_ARGS+=(-PdemoOut="$OUT") ;;
  dialog:*) GRADLE_ARGS+=("-PdemoDialog=${MODE#dialog:}" -PdemoDialogOut="$OUT") ;;
esac

./gradlew runIde "${GRADLE_ARGS[@]}" --console=plain > /tmp/shoot-panel.log 2>&1 &
GRADLE_PID=$!

for i in $(seq 1 180); do
  sleep 5
  if [ -f "$OUT" ]; then
    echo "shot ok @ $((i * 5))s -> $OUT"
    break
  fi
done

for p in $(ps aux | grep "$NEEDLE" | awk '{print $2}'); do kill "$p" 2>/dev/null; done
sleep 2
for p in $(ps aux | grep "$NEEDLE" | awk '{print $2}'); do kill -9 "$p" 2>/dev/null; done
# 主进程死后会留下孤儿 JCEF 辅助进程（cef_server / cef_helper），按沙箱路径再补一刀
for p in $(pgrep -f "sandbox/idea-ai-assistant" 2>/dev/null); do kill -9 "$p" 2>/dev/null; done
kill "$GRADLE_PID" 2>/dev/null

if [ -f "$OUT" ]; then
  echo done
else
  echo FAILED
  tail -20 /tmp/shoot-panel.log
  exit 1
fi
