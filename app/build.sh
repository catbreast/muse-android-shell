#!/usr/bin/env bash
# Muse Shell APK 手工构建流水线（无 Gradle）：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 需要：JDK 17+、Android SDK build-tools（aapt2/d8/zipalign/apksigner）、一个 android.jar。
# 路径可用环境变量覆盖：ANDROID_BT（build-tools 目录）、ANDROID_JAR、JAVA_HOME。
# 默认在本仓库 toolchain/ 下找：toolchain/android-16 = build-tools，toolchain/android-35/android.jar。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
TC="$ROOT/toolchain"
BT="${ANDROID_BT:-$TC/android-16}"
JAR="${ANDROID_JAR:-$TC/android-35/android.jar}"
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$BT:$PATH"
# 签名口令：首次构建自动生成（仅本地保存，切勿提交）
if [ ! -f "$ROOT/.ks-pass" ]; then
  (umask 077; head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24 > "$ROOT/.ks-pass")
  echo "[sign] generated new keystore password in .ks-pass (keep it private)"
fi
OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/core-classes" "$OUT/core-dex" "$OUT/assets"

echo '[core] build vscreen core dex -> assets/core.dex'
find "$ROOT/core/src" -name '*.java' > "$OUT/core-sources.txt"
javac --release 8 -cp "$JAR" -d "$OUT/core-classes" @"$OUT/core-sources.txt"
d8 --lib "$JAR" --min-api 26 --output "$OUT/core-dex" $(find "$OUT/core-classes" -name '*.class')
cp "$OUT/core-dex/classes.dex" "$OUT/assets/core.dex"

echo '[1/6] aapt2 compile+link'
aapt2 compile --dir "$APP/res" -o "$OUT/compiled.zip"
aapt2 link -o "$OUT/app-unsigned.apk" -I "$JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -A "$OUT/assets" \
  --min-sdk-version 26 --target-sdk-version 28 \
  "$OUT/compiled.zip"

echo '[2/6] javac'
find "$APP/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
LIBS="$(ls "$APP"/libs/*.jar 2>/dev/null | paste -sd: -)"
javac --release 8 -cp "$JAR:$LIBS" -d "$OUT/classes" @"$OUT/sources.txt"

echo '[3/6] d8'
d8 --lib "$JAR" --min-api 26 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') "$APP"/libs/*.jar

echo '[4/6] package dex'
cp "$OUT/app-unsigned.apk" "$OUT/app-dex.apk"
(cd "$OUT/dex" && zip -q -u "$OUT/app-dex.apk" classes.dex)

echo '[5/6] zipalign'
zipalign -p -f 4 "$OUT/app-dex.apk" "$OUT/app-aligned.apk"

echo '[6/6] sign'
KS="$ROOT/muse-shell.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias muse -keyalg RSA -keysize 2048 \
    -validity 3650 -storepass "$(cat "$ROOT/.ks-pass")" -keypass "$(cat "$ROOT/.ks-pass")" \
    -dname "CN=Muse Shell, O=MuseShell" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass "pass:$(cat "$ROOT/.ks-pass")" \
  --out "$ROOT/MuseShell.apk" "$OUT/app-aligned.apk"
apksigner verify "$ROOT/MuseShell.apk"
aapt2 dump badging "$ROOT/MuseShell.apk" | head -4
ls -la "$ROOT/MuseShell.apk"
echo BUILD_OK
