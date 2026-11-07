#!/usr/bin/env bash
# Make a fresh Claude cloud session ready to build OnPath and run tools/ci/gate.sh (no emulator there: no KVM –
# E2E runs on GitHub, tools/ci/e2e_github.sh). Idempotent and quiet when everything is already there.
#   1. Android SDK (cmdline-tools from dl.google.com, platforms;android-36, build-tools;35.0.0 + 36.0.0, platform-tools) in
#      $ANDROID_SDK (default /opt/android-sdk); licences accepted
#   2. local.properties sdk.dir (git-ignored)
#   3. ~/.gradle/init.d/onpath-mirror.gradle: Google's Maven Central mirror first (Maven Central answers 429 to cloud
#      containers until the cache is warm). Only `beforeSettings` repos – an `allprojects { repositories }` block breaks
#      this project's settings (FAIL_ON_PROJECT_REPOS).
#   4. warms the Gradle dependency cache (./gradlew help) unless ONPATH_SETUP_WARM=0
# Run from the repo or from the folder that holds the checkouts (the environment's setup script does
# `if [ -d OnPath ]; then bash OnPath/tools/cloud/session_setup.sh; fi`). Not OnPath → exit 0 quietly.
set -uo pipefail
here=$(cd "$(dirname "$0")/../.." && pwd)
[ -f "$here/settings.gradle.kts" ] && grep -q 'rootProject.name = "OnPath"' "$here/settings.gradle.kts" || exit 0
SDK=${ANDROID_SDK:-/opt/android-sdk}
TOOLS_ZIP=commandlinetools-linux-13114758_latest.zip   # cmdline-tools 19.0
PKGS=("platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0" "platform-tools")   # AGP fetches 36 itself otherwise
log() { echo "session_setup: $*"; }
t0=$(date +%s)

# 1. SDK
sm=$SDK/cmdline-tools/latest/bin/sdkmanager
if [ ! -x "$sm" ]; then
  log "cmdline-tools → $SDK"
  tmp=$(mktemp -d) || exit 1
  curl -fsSL --retry 3 -o "$tmp/t.zip" "https://dl.google.com/android/repository/$TOOLS_ZIP" || { log "download failed"; exit 1; }
  mkdir -p "$SDK/cmdline-tools" && (cd "$tmp" && unzip -q t.zip) && rm -rf "$SDK/cmdline-tools/latest" &&
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest" || { log "unzip failed"; exit 1; }
  rm -rf "$tmp"
fi
missing=()
[ -d "$SDK/platforms/android-36" ] || missing+=("${PKGS[0]}")
[ -d "$SDK/build-tools/35.0.0" ] || missing+=("${PKGS[1]}")
[ -d "$SDK/build-tools/36.0.0" ] || missing+=("${PKGS[2]}")
[ -x "$SDK/platform-tools/adb" ] || missing+=("${PKGS[3]}")
if [ ${#missing[@]} -gt 0 ]; then
  log "sdkmanager ${missing[*]}"
  yes 2>/dev/null | "$sm" --sdk_root="$SDK" --licenses >/dev/null 2>&1
  "$sm" --sdk_root="$SDK" "${missing[@]}" >/dev/null || { log "sdkmanager failed"; exit 1; }
fi

# 2. local.properties
if ! grep -qx "sdk.dir=$SDK" "$here/local.properties" 2>/dev/null; then
  { grep -v '^sdk.dir=' "$here/local.properties" 2>/dev/null; echo "sdk.dir=$SDK"; } > "$here/local.properties.new" &&
    mv "$here/local.properties.new" "$here/local.properties"
  log "local.properties sdk.dir=$SDK"
fi

# 3. Maven Central mirror
init=${GRADLE_USER_HOME:-$HOME/.gradle}/init.d/onpath-mirror.gradle
if [ ! -f "$init" ]; then
  mkdir -p "$(dirname "$init")"
  cat > "$init" <<'EOF'
// session_setup.sh (OnPath): Google's Maven Central mirror before Maven Central – cloud containers get HTTP 429
beforeSettings { settings ->
    def mirror = { r -> r.maven { url "https://maven-central.storage-download.googleapis.com/maven2/"; name "MavenCentralMirror" } }
    mirror(settings.pluginManagement.repositories)
    mirror(settings.dependencyResolutionManagement.repositories)
}
EOF
  log "Gradle init script $init"
fi

# 4. warm the Gradle cache (wrapper + plugins + dependencies of the build scripts)
if [ "${ONPATH_SETUP_WARM:-1}" != 0 ] && [ ! -f "$here/.gradle/onpath-warm" ]; then
  log "warming Gradle (./gradlew help)"
  if (cd "$here" && ./gradlew --no-daemon -q help >/dev/null 2>&1); then
    mkdir -p "$here/.gradle" && touch "$here/.gradle/onpath-warm"
  else log "⚠ ./gradlew help failed – the first build will retry"; fi
fi
log "ready ($(( $(date +%s) - t0 )) s)"
