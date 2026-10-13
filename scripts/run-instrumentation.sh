#!/usr/bin/env bash
# 在已启动的设备上直接跑仪器测试，跳过 Gradle 的重新打包与安装。
#
# 背景（前三行是 21 个用例时期实测的对照；本脚本与 Gradle 任务的差距全在
# 「跳过重新打包与安装」上，与用例数无关，所以用例涨到 64 个这几行仍然说明得了问题）：
#   ./gradlew :app:connectedDebugAndroidTest          56.6s  冷构建
#   ./gradlew :app:connectedDebugAndroidTest          35.4s  构建命中缓存
#   本脚本（首次会构建并安装，之后只跑）             26.6s  只跑用例
# 当前 112 个用例，am instrument 本身约 76s、单个类约 3s（模拟器实测）。
# 反复调试单个测试类时用本脚本最划算；正式验收仍用 Gradle 任务。
#
# 三种模式：
#   （不传）auto  模拟器跑全套，真机自动跳过多语言——真机屏幕窄、语言与模拟器不同，
#                    像素类断言在那里证明不了什么，还白占时间
#   full          全跑，含多语言
#   fast          跳过多语言（标了 @NeedsI18n 的类/方法）
#   <类名 | '类#方法'>  只跑这一个，模式按 auto 的规则套用
#                    类名给全限定名（io.github.hecate2.D7.CaptureShutterGestureTest）；
#                    不含点的简名会自动补上 io.github.hecate2.D7. 前缀——am instrument 的
#                    -e class 只认全限定名，给简名不会报错，而是安静地跑一个名为「找不到
#                    这个类」的失败用例（Tests run: 1, Failures: 1），白跑一趟还容易误判。
#
# 模拟器上还会顺手把三个动画时长全局设成 0：Espresso 等的是主线程空闲，动画没停就等着。
# 实测 54 个用例 60.4s → 36.6s（省 40%），比跳过多语言那 14% 值钱得多。只改模拟器，
# 不动真机，免得把主人正在看的界面弄成瞬移。跑完不恢复：模拟器本来就是跑测试的。
#
# Gradle 任务没法这么切，但同一个注解可以用：
#   ./gradlew :app:connectedDebugAndroidTest \
#     -Pandroid.testInstrumentationRunnerArguments.notAnnotation=io.github.hecate2.D7.NeedsI18n
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$ANDROID_HOME/platform-tools/adb"
I18N=io.github.hecate2.D7.NeedsI18n

# Gradle.properties 里固定了 Temurin 17；这里只作兜底，避免 java 缺失导致 adb 不可用
[ -x "$JAVA_HOME/bin/java" ] || export PATH="$JAVA_HOME/bin:$PATH"

# 多设备时必须钉死设备：本机常同时连着真机，connectedAndroidTest 会一并跑
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
if ! "$ADB" devices | grep -q "^${DEVICE}[[:space:]]"; then
  echo "设备 $DEVICE 未连接。先启动模拟器，或用 ANDROID_SERIAL 指定。" >&2
  "$ADB" devices
  exit 1
fi

IS_EMULATOR=no
[[ "$DEVICE" == emulator-* ]] && IS_EMULATOR=yes

MODE="${1:-auto}"
FILTER=()
case "$MODE" in
  auto)
    if [[ "$IS_EMULATOR" == yes ]]; then MODE=full; else MODE=fast; fi
    ;;
  full | fast) ;;
  *)
    # 简名补包名；带点的原样传（子包要自己写全，如 view.ViewfinderFillLayerTest 不成立，
    # 得写 io.github.hecate2.D7.view.ViewfinderFillLayerTest）
    [[ "$MODE" == *.* ]] || MODE="io.github.hecate2.D7.$MODE"
    FILTER=(-e class "$MODE")
    MODE="单类 $MODE"
    ;;
esac

# 装好两个 APK（Gradle 只在必要时才重装，这里先确保存在且是最新构建）
./gradlew --quiet :app:assembleDebug :app:assembleDebugAndroidTest
"$ADB" -s "$DEVICE" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$DEVICE" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

if [[ "$IS_EMULATOR" == yes ]]; then
  for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" -s "$DEVICE" shell settings put global "$s" 0.0 >/dev/null
  done
fi

if [[ "$MODE" == fast ]]; then
  FILTER+=(-e notAnnotation "$I18N")
fi

echo "模式=$MODE 设备=$DEVICE${FILTER[1]:+ 过滤=${FILTER[*]}}"
RUNNER=io.github.hecate2.D7.test/androidx.test.runner.AndroidJUnitRunner
REPORT="$(mktemp)"
trap 'rm -f "$REPORT"' EXIT

# 注意：`am instrument` 就算有用例失败也一律返回 0，直接看 $? 会把红灯当绿灯放过去
# （实测过：54 跑挂 1，脚本仍然退出 0）。所以输出留一份，最后只认 `OK (n tests)`。
# 注意 ${F[@]+"${F[@]}"} 这个写法：macOS 自带的是 bash 3.2，空数组在 set -u 下直接
# 报 unbound variable，而 full 模式恰好就是空数组——不这么写 full 根本跑不起来
"$ADB" -s "$DEVICE" shell am instrument -w ${FILTER[@]+"${FILTER[@]}"} "$RUNNER" \
  | tr -d '\r' | tee "$REPORT"

grep -qE '^OK \([0-9]+ tests?\)$' "$REPORT"