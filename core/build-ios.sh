#!/bin/sh
# Сборка Dhcore.xcframework (ядро на mihomo) для iOS: sh core/build-ios.sh (на macOS с Xcode).
# Результат — ios/Frameworks/Dhcore.xcframework.
# Если с машины сборки не открывается proxy.golang.org: на другой машине `go mod download`
# в отдельный GOMODCACHE, его cache/download скопировать сюда и указать DHA_GOPROXY=<путь>.
set -e
MIHOMO_VERSION=${MIHOMO_VERSION:-1.19.32}
cd "$(dirname "$0")"
export PATH="$HOME/.local/go/bin:$HOME/go/bin:$PATH"
if [ -n "$DHA_GOPROXY" ]; then
  export GOPROXY="file://$DHA_GOPROXY" GOSUMDB=off GOFLAGS=-mod=mod
fi
command -v gomobile >/dev/null || go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
command -v gobind >/dev/null || go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12
mkdir -p ../ios/Frameworks
rm -rf ../ios/Frameworks/Dhcore.xcframework
# iOS 15 — iPad Air 2 на iPadOS 15.7 тоже должен потянуть
gomobile bind -v -target ios -iosversion 15.0 -trimpath -tags with_gvisor \
  -ldflags "-X github.com/metacubex/mihomo/constant.Version=$MIHOMO_VERSION -checklinkname=0 -s -w -buildid=" \
  -o ../ios/Frameworks/Dhcore.xcframework .
# gomobile кладёт фреймворк «глубоким» бандлом (Versions/A, как на macOS), а iOS требует
# плоский с Info.plist в корне — иначе Xcode 26 отказывается его внедрять.
for fw in ../ios/Frameworks/Dhcore.xcframework/*/Dhcore.framework; do
  if [ -d "$fw/Versions/A" ]; then
    tmp="$fw.flat"; mkdir "$tmp"
    cp -R "$fw/Versions/A/." "$tmp/"
    rm -rf "$fw"; mv "$tmp" "$fw"
    rm -rf "$fw/Resources"
  fi
  cat > "$fw/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key><string>en</string>
	<key>CFBundleExecutable</key><string>Dhcore</string>
	<key>CFBundleIdentifier</key><string>io.github.varyen.detourhome.dhcore</string>
	<key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
	<key>CFBundleName</key><string>Dhcore</string>
	<key>CFBundlePackageType</key><string>FMWK</string>
	<key>CFBundleShortVersionString</key><string>$MIHOMO_VERSION</string>
	<key>CFBundleVersion</key><string>1</string>
	<key>MinimumOSVersion</key><string>15.0</string>
</dict>
</plist>
PLIST
done
du -sh ../ios/Frameworks/Dhcore.xcframework
