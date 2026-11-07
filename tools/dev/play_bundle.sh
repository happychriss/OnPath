#!/usr/bin/env bash
# Google Play bundle (A-003, docs/play-build.md): builds the build type "play" (no own updater, not debuggable, signed
# with the upload key) through a Gradle slot, checks the result and copies it to dist/onpath-<version>.aab for the
# upload by hand in the Play Console.
#   tools/dev/play_bundle.sh            build + check + copy
#   SKIP_BUILD=1 tools/dev/play_bundle.sh   check + copy the bundle already built
# Upload key: .secrets/upload-keystore.jks + .secrets/upload-keystore.properties (storeFile, storePassword, keyAlias,
# keyPassword) – only on the owner's machine, gitignored; ONPATH_SECRETS=<dir> points elsewhere (tests: a throwaway key).
# Passwords are never printed. Checks: signed with that upload key (SHA-256 fingerprint shown), not debuggable,
# versionCode / versionName / targetSdk, no REQUEST_INSTALL_PACKAGES, arm64 native libs, the assets (style, BRouter,
# fonts, overview map), a map key in the build. Exit 0 = dist/onpath-<version>.aab is ready to upload.
set -uo pipefail
DIR=$(cd "$(dirname "$0")/../.." && pwd)
cd "$DIR" || exit 2
fail() { echo "play_bundle: FAIL – $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }

SECRETS=${ONPATH_SECRETS:-.secrets}
case "$SECRETS" in /*) ;; *) SECRETS="$DIR/$SECRETS" ;; esac
export ONPATH_SECRETS="$SECRETS"
PROPS="$SECRETS/upload-keystore.properties"
prop() { [ -f "$PROPS" ] && sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$PROPS" | head -1 | sed "s/[[:space:]]*\$//; s/^[\"']//; s/[\"']\$//"; }
STORE=$(prop storeFile); STORE=${STORE:-upload-keystore.jks}
case "$STORE" in /*) ;; *) STORE="$SECRETS/$STORE" ;; esac
[ -f "$STORE" ] || fail "no upload key at $STORE (+ $PROPS) – without it the play build is unsigned and cannot go to Play"

VERSION=$(sed -n 's/.*?: "\([0-9][0-9.]*\)".*/\1/p' app/build.gradle.kts | head -1)
[ -n "$VERSION" ] || fail "no default appVersion in app/build.gradle.kts"
IFS=. read -r MAJ MIN PAT <<<"$VERSION"
CODE=$(( ${MAJ:-0} * 10000 + ${MIN:-0} * 100 + ${PAT:-0} ))
echo "play_bundle: OnPath $VERSION (versionCode $CODE), upload key $STORE"

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  tools/dev/gradle_slot.sh -- ./gradlew --max-workers=2 :app:bundlePlay :app:packagePlayUniversalApk \
    || fail "the Gradle build failed"
fi
AAB=app/build/outputs/bundle/play/app-play.aab
APK=$(find app/build/outputs/apk_from_bundle/play -name '*universal*.apk' 2>/dev/null | head -1)
[ -f "$AAB" ] || fail "$AAB not found"
[ -f "$APK" ] || fail "the universal APK from the bundle (packagePlayUniversalApk) not found"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}}
BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
[ -x "$BT/aapt2" ] && [ -x "$BT/apksigner" ] || fail "no build-tools with aapt2 + apksigner under $SDK"

# 1. signed with the upload key: the bundle's certificate = the keystore's certificate
command -v keytool >/dev/null && command -v jarsigner >/dev/null || fail "keytool / jarsigner not found (a JDK on PATH)"
jarsigner -verify "$AAB" 2>/dev/null | grep -q 'jar verified' || fail "the bundle is not signed (or does not verify)"
fp() { grep -m1 -E 'SHA-?256:' | sed 's/.*SHA-\{0,1\}256:[[:space:]]*//'; }
AAB_FP=$(keytool -printcert -jarfile "$AAB" 2>/dev/null | fp)
ALIAS=$(prop keyAlias)
ONPATH_PLAY_STOREPASS=$(prop storePassword); export ONPATH_PLAY_STOREPASS
KEY_FP=$(keytool -list -v -keystore "$STORE" -storepass:env ONPATH_PLAY_STOREPASS -alias "$ALIAS" 2>/dev/null | fp)
unset ONPATH_PLAY_STOREPASS
[ -n "$KEY_FP" ] || fail "cannot read the certificate of alias '$ALIAS' in $STORE (password / alias in $PROPS?)"
[ "$AAB_FP" = "$KEY_FP" ] || fail "the bundle is signed with another key ($AAB_FP), not the upload key ($KEY_FP)"
ok "signed with the upload key, SHA-256 $KEY_FP"
"$BT/apksigner" verify "$APK" >/dev/null 2>&1 || fail "the APK from the bundle does not verify"

# 2. manifest: not debuggable, version, target SDK, no self-update permission
BADGING=$("$BT/aapt2" dump badging "$APK") || fail "aapt2 cannot read $APK"
! grep -q 'application-debuggable' <<<"$BADGING" || fail "the play build is debuggable"
ok "not debuggable"
grep -q "package: name='com.onpath.app' versionCode='$CODE' versionName='$VERSION'" <<<"$BADGING" \
  || fail "version: $(grep -m1 '^package:' <<<"$BADGING") – expected versionCode $CODE / versionName $VERSION"
ok "com.onpath.app versionCode $CODE, versionName $VERSION"
TARGET=$(sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$BADGING")
[ "${TARGET:-0}" -ge 36 ] || fail "targetSdk $TARGET – Google Play needs 36 or higher since 2026-08-31"
ok "targetSdk $TARGET"
! grep -q 'REQUEST_INSTALL_PACKAGES' <<<"$BADGING" || fail "the play build asks for REQUEST_INSTALL_PACKAGES"
ok "no REQUEST_INSTALL_PACKAGES"

# 3. contents: arm64 native libs, assets, map key
LIST=$(unzip -l "$AAB") || fail "unzip cannot read $AAB"
NLIB=$(grep -c 'base/lib/arm64-v8a/.*\.so$' <<<"$LIST")
[ "$NLIB" -gt 0 ] || fail "no arm64-v8a native libraries in the bundle"
! grep -qE 'base/lib/(x86|x86_64|armeabi-v7a)/' <<<"$LIST" || echo "  WARN other ABIs than arm64-v8a in the bundle"
ok "$NLIB arm64-v8a native libraries"
for a in style/ brouter/ fonts/ sprites/ licences/deps.txt; do
  grep -q "base/assets/$a" <<<"$LIST" || fail "asset $a missing in the bundle"
done
ok "assets: style, brouter, fonts, sprites, licences"
if grep -q 'base/assets/overview/base.pmtiles' <<<"$LIST"; then ok "overview map (assets/overview)"
else echo "  WARN no overview map in the bundle (set onpath.overviewAssets, docs/map-pipeline.md §9b) – empty map below zoom 10"; fi
BC=app/build/generated/source/buildConfig/play/com/onpath/app/BuildConfig.java
if [ -f "$BC" ]; then
  grep -q 'MAP_SERVER_KEY = "[^"]' "$BC" && ok "map key set (test map server)" \
    || echo "  WARN no map key in the build (onpath.mapKey.test) – this bundle cannot download maps"
fi

mkdir -p dist
OUT="dist/onpath-$VERSION.aab"
cp "$AAB" "$OUT" || fail "cannot copy to $OUT"
echo "play_bundle: ready – $OUT ($(du -h "$OUT" | cut -f1)), upload it in the Play Console (docs/play-build.md)"
