#!/usr/bin/env bash
# 手工构建 TV 应用 APK（不依赖 Gradle / AGP）
#
# 依赖：JDK 17、Android SDK build-tools 34、platforms/android-34/android.jar
# 通过环境变量指定工具链：
#   ANDROID_SDK_ROOT   / ANDROID_HOME   SDK 根目录
#   BUILD_TOOLS                          build-tools 目录（默认 $SDK/build-tools/34.0.0）
#   JAVA_HOME                            JDK 根目录（默认取 PATH 里的 javac/java）
set -e

VERSION=1.0.0
VCODE=10
NAME=${1:-danmu-tv-$VERSION}

ROOT=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/d/nb-build/sdk}}
BT=${BUILD_TOOLS:-$SDK/build-tools/34.0.0}
if [ -n "$JAVA_HOME" ]; then
  JDK="$JAVA_HOME/bin"
else
  JDK=/d/nb-build/jdk/jdk-17.0.20.1+1/bin
fi
AJAR="$SDK/platforms/android-34/android.jar"
OUT="$ROOT/build"

if [ ! -f "$AJAR" ]; then
  echo "找不到 $AJAR" >&2
  echo "请设置 ANDROID_SDK_ROOT 并确保装了 platforms;android-34 与 build-tools;34.0.0" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/resflat" "$ROOT/keys"

echo "[1/6] 编译 Java"
"$JDK/javac" -encoding UTF-8 --release 8 -nowarn -Xlint:-options \
  -cp "$AJAR" -d "$OUT/classes" $(find "$ROOT/src" -name "*.java")

echo "[2/6] dex"
"$JDK/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$AJAR" --min-api 26 --release --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")

echo "[3/6] 编译资源"
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/resflat"

echo "[4/6] 链接资源"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VCODE" --version-name "$VERSION" \
  $(find "$OUT/resflat" -name "*.flat")

if [ ! -f "$ROOT/keys/mod.jks" ]; then
  echo "[5/6] 生成签名密钥"
  "$JDK/keytool" -genkeypair -keystore "$ROOT/keys/mod.jks" -alias mod \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Yaohui Danmu, OU=dev, O=local, L=CN, ST=CN, C=CN" >/dev/null 2>&1
else
  echo "[5/6] 复用已有密钥"
fi

echo "[6/6] 打包 + 对齐 + 签名"
python - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/unsigned.apk" <<'PY'
import sys, zipfile, shutil
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$JDK/java" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$ROOT/keys/mod.jks" --ks-key-alias mod \
  --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/$NAME.apk" "$OUT/aligned.apk"
"$JDK/java" -jar "$BT/lib/apksigner.jar" verify "$ROOT/$NAME.apk"

ls -lh "$ROOT/$NAME.apk"
echo "OK -> $ROOT/$NAME.apk"
