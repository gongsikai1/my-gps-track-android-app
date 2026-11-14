#!/usr/bin/env bash
# 拉取高德 3D 地图 SDK（默认 10.0.600），并把原生库 / 地图样式资源铺到工程里。
# 需要有网络，仅首次或升级 SDK 版本时执行一次。
set -euo pipefail

SDK_VER="${AMAP_SDK_VERSION:-10.0.600}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
URL="https://repo1.maven.org/maven2/com/amap/api/3dmap/${SDK_VER}/3dmap-${SDK_VER}.jar"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> 下载高德 3D 地图 SDK ${SDK_VER}"
curl -L --fail --progress-bar -o "$TMP/3dmap.jar" "$URL"

echo "==> 解压原生库与地图样式资源"
mkdir -p "$TMP/x"
( cd "$TMP/x" && unzip -q "$TMP/3dmap.jar" 'lib/*' 'assets/*' )

mkdir -p "$ROOT/app/src/main/jniLibs" "$ROOT/app/src/main/assets" "$ROOT/app/libs"
cp -R "$TMP/x/lib/." "$ROOT/app/src/main/jniLibs/"
cp -R "$TMP/x/assets/." "$ROOT/app/src/main/assets/"

echo "==> 生成仅含 class 的 jar（AGP 不接受 jar 内带 jniLibs 目录）"
cp "$TMP/3dmap.jar" "$ROOT/app/libs/3dmap.jar"
( cd "$ROOT/app/libs" && zip -q -d 3dmap.jar 'lib/*' 'assets/*' )

echo "==> 完成"
ls -lh "$ROOT/app/libs/3dmap.jar"
du -sh "$ROOT/app/src/main/jniLibs" "$ROOT/app/src/main/assets"
