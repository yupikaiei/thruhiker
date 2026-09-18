#!/usr/bin/env bash
#
# Assembles the 3D-terrain MapLibre Android SDK into third_party/maplibre-android/.
#
# MapLibre Native has no terrain in any released SDK; the implementation is on the
# `feature/terrain-3d` branch (draft PR maplibre/maplibre-native#4190). Building that
# branch's native library needs an NDK and a long C++ build, but it does not need to be
# built here: the branch's CI already produces it, and the Android/Kotlin half is plain
# sources that this project can compile itself.
#
# So this script:
#   1. finds the newest green `android-ci` run on that branch (requires `gh`),
#   2. takes its arm64-v8a libmaplibre.so out of the release APK artifact,
#   3. strips it (needs an NDK or aarch64 binutils for a sane size),
#   4. sparse-clones the branch's Android SDK sources,
#   5. assembles both into third_party/maplibre-android/ with a Gradle build file.
#
# The result is gitignored, so it is rebuilt rather than committed. Delete the
# directory to go back to the published, flat SDK.
#
# Usage: scripts/build-maplibre-terrain.sh
set -euo pipefail

REPO="maplibre/maplibre-native"
BRANCH="feature/terrain-3d"
WORKFLOW="android-ci"
ABI="arm64-v8a"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$ROOT/third_party/maplibre-android"
SCRATCH="$ROOT/build/maplibre-terrain"
BUILD_SCRIPT="$(dirname "${BASH_SOURCE[0]}")/maplibre-terrain-build.gradle.kts"

log() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

for tool in gh git unzip curl; do
  command -v "$tool" >/dev/null || die "$tool is required"
done
[ -f "$BUILD_SCRIPT" ] || die "missing $BUILD_SCRIPT"

# --- 1. Newest green Android CI run on the terrain branch ---------------------

log "Looking up the newest green $WORKFLOW run on $REPO $BRANCH"
RUN_ID="$(gh run list -R "$REPO" -b "$BRANCH" -w "$WORKFLOW" -s success -L 1 \
  --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
[ -n "${RUN_ID:-}" ] && [ "$RUN_ID" != "null" ] || die "no successful $WORKFLOW run found on $BRANCH"
RUN_SHA="$(gh run view "$RUN_ID" -R "$REPO" --json headSha --jq '.headSha' 2>/dev/null || echo unknown)"
log "Using run $RUN_ID (${RUN_SHA:0:12})"

# --- 2. Native library out of the release APK ---------------------------------

rm -rf "$SCRATCH"
mkdir -p "$SCRATCH"

download_artifact() {
  local name="$1"
  log "Downloading artifact $name"
  gh run download "$RUN_ID" -R "$REPO" -n "$name" -D "$SCRATCH/$name" >/dev/null 2>&1
}

SO=""
# The benchmark artifact is a release build, so its library is already optimised; the
# UI test app is the fallback because it is built debug and carries debug info.
for artifact in benchmarkAPKs android-ui-test-opengl; do
  if [ -z "$SO" ] && download_artifact "$artifact"; then
    APK="$(find "$SCRATCH/$artifact" -name "*.apk" ! -name "*androidTest*" | head -1)"
    if [ -n "$APK" ]; then
      unzip -o -q "$APK" "lib/$ABI/libmaplibre.so" -d "$SCRATCH/lib" || true
      [ -f "$SCRATCH/lib/lib/$ABI/libmaplibre.so" ] && SO="$SCRATCH/lib/lib/$ABI/libmaplibre.so"
    fi
  fi
done
[ -n "$SO" ] || die "no $ABI libmaplibre.so in the run's artifacts"
log "Extracted $(du -h "$SO" | cut -f1) $ABI libmaplibre.so"

# --- 3. Strip it ---------------------------------------------------------------

find_strip() {
  local candidate
  for base in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" ${ANDROID_HOME:-}/ndk/*; do
    [ -n "$base" ] || continue
    for candidate in "$base"/toolchains/llvm/prebuilt/*/bin/llvm-strip; do
      [ -x "$candidate" ] && { printf '%s' "$candidate"; return 0; }
    done
  done
  for candidate in aarch64-linux-gnu-strip llvm-strip; do
    command -v "$candidate" >/dev/null 2>&1 && { printf '%s' "$candidate"; return 0; }
  done
  return 1
}

if STRIP="$(find_strip)"; then
  log "Stripping with $STRIP"
  "$STRIP" --strip-unneeded "$SO"
  log "Stripped to $(du -h "$SO" | cut -f1)"
else
  log "No NDK or aarch64 strip found: shipping the library unstripped, so the APK will be large"
fi

# A library without terrain here would build an app that silently stays flat.
if [ "$(grep -a -o -F terrain "$SO" | wc -l)" -lt 1 ]; then
  die "the downloaded library has no terrain support; refusing to assemble it"
fi

# Captured rather than piped into `grep -q`: grep exits on the first match, the
# writer takes SIGPIPE, and `pipefail` then reports the whole pipeline as failed.
SYMBOLS="$(readelf -sW "$SO" 2>/dev/null || true)"
case "$SYMBOLS" in
  *JNI_OnLoad*) ;;
  *) log "Warning: JNI_OnLoad is missing from the symbol table; the library may not load" ;;
esac

# --- 4. Android SDK sources ----------------------------------------------------

log "Cloning the Android SDK sources from $BRANCH"
git clone --quiet --depth 1 --branch "$BRANCH" --filter=blob:none --sparse \
  "https://github.com/$REPO.git" "$SCRATCH/src"
git -C "$SCRATCH/src" sparse-checkout set platform/android/MapLibreAndroid

MODULE="$SCRATCH/src/platform/android/MapLibreAndroid"
[ -d "$MODULE/src/main" ] || die "unexpected source layout in $BRANCH"

# --- 5. Assemble the module ----------------------------------------------------

log "Assembling $TARGET"
rm -rf "$TARGET"
# Destinations are created first and copied into explicitly: `cp -r src/main target/src/`
# with `target/src` absent copies main *as* src, which silently drops the main source set.
mkdir -p "$TARGET/src/main"
cp -r "$MODULE/src/main/." "$TARGET/src/main/"
cp -r "$MODULE/src/opengl" "$TARGET/src/"
mkdir -p "$TARGET/src/sharedRenderer"
cp -r "$MODULE/src/sharedRenderer/opengl" "$TARGET/src/sharedRenderer/"
cp "$MODULE/proguard-rules.pro" "$TARGET/"
cp "$BUILD_SCRIPT" "$TARGET/build.gradle.kts"
mkdir -p "$TARGET/src/main/jniLibs/$ABI"
cp "$SO" "$TARGET/src/main/jniLibs/$ABI/libmaplibre.so"

cat > "$TARGET/README.md" <<EOF
Vendored MapLibre Android SDK with 3D terrain support. Generated by
\`scripts/build-maplibre-terrain.sh\` — refresh it with that script rather than editing
by hand.

Sources and the arm64-v8a native library come from MapLibre Native's
\`$BRANCH\` branch, commit \`${RUN_SHA:0:12}\`. That branch is the only place 3D
terrain exists: no released MapLibre has it, and the released parser ignores the
style's \`terrain\` property.

It is committed on purpose, so a fresh clone and CI build 3D without an NDK, a token,
or a network fetch. Delete this directory and the build falls back to the published,
flat SDK.

MapLibre Native is BSD 2-Clause — see \`LICENSE.md\`.
EOF

if [ ! -f "$TARGET/LICENSE.md" ]; then
  curl -sfL "https://raw.githubusercontent.com/$REPO/$BRANCH/LICENSE.md" \
    -o "$TARGET/LICENSE.md" || log "Warning: could not fetch the upstream licence"
fi

log "Done: $(du -sh "$TARGET" | cut -f1) in $TARGET"
log "Next: ./gradlew :app:assembleDebug   (APK is $ABI only while this module exists)"
