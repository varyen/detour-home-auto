#!/bin/sh
# Сборка неподписанного .ipa Detour Home Auto: sh ios/build.sh (на macOS с Xcode).
# Перед этим — sh core/build-ios.sh (ядро). Нужны xcodegen и ldid в PATH.
#
# Подписи Apple нет, поэтому entitlements (Network Extension, Wi-Fi info) вшивает
# ldid. Такой .ipa ставится через TrollStore; на обычном iOS без платного аккаунта
# Apple Developer туннель не запустится.
set -e
cd "$(dirname "$0")"
export PATH="$HOME/.local/bin:$PATH"
VERSION=$(sed -n 's/^ *MARKETING_VERSION: "\(.*\)"/\1/p' project.yml)
xcodegen generate
rm -rf build/DetourHome.xcarchive && mkdir -p build
xcodebuild -project DetourHome.xcodeproj -scheme DetourHome -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -archivePath build/DetourHome.xcarchive archive \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" > build/xcodebuild.log 2>&1 || { grep -E "error:" build/xcodebuild.log | sort -u | head -20; exit 1; }

APP=build/DetourHome.xcarchive/Products/Applications/DetourHome.app
EXT="$APP/PlugIns/DetourHomeTunnel.appex"
[ -d "$EXT" ] || { echo "в .app нет расширения туннеля"; exit 1; }

if command -v ldid >/dev/null; then
  ldid -S "$APP/Frameworks/Dhcore.framework/Dhcore" 2>/dev/null || true
  ldid -S"App/DetourHome.entitlements" "$APP/DetourHome"
  ldid -S"Tunnel/DetourHomeTunnel.entitlements" "$EXT/DetourHomeTunnel"
else
  echo "ldid не найден: entitlements не вшиты, туннель не запустится" >&2
fi

TMP=$(mktemp -d)
mkdir "$TMP/Payload"
cp -R "$APP" "$TMP/Payload/"
IPA="$PWD/build/DetourHomeAuto-$VERSION.ipa"
rm -f "$IPA"
( cd "$TMP" && zip -qry "$IPA" Payload )
rm -rf "$TMP"
ls -la "$IPA"
