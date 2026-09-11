#!/usr/bin/env bash
# Build and sign a release APK.
#
# The installed base was signed with the Android debug key, whose password is
# public, so the update to a real key has to carry a v3 rotation lineage or
# every existing install would refuse it — and reinstalling means losing the
# Keystore-held device key and every local message (allowBackup=false).
# The Gradle signing config cannot express a lineage, so the release build type
# is left unsigned and apksigner signs it here.
#
# Credentials live in ~/.gradle/gradle.properties, never in the repo.
#
# CI only compiles the androidTest source set — it has no emulator. Run
# `./gradlew :app:connectedDebugAndroidTest` on a real phone before cutting a
# release: the device-trust chain, its migration and lazysodium's native path
# are the things a green JVM run is explicitly not evidence for.
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export PATH="$JAVA_HOME/bin:$PATH"

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../android" && pwd)"
APKSIGNER="$(ls -1 "$HOME"/Library/Android/sdk/build-tools/*/apksigner | sort -V | tail -1)"
LINEAGE="$HOME/keystores/securemsg-lineage.bin"
PROPS="$HOME/.gradle/gradle.properties"

prop() { grep "^$1=" "$PROPS" | cut -d= -f2-; }
KS="$(prop SECUREMSG_RELEASE_STORE_FILE)"
KS_PASS="$(prop SECUREMSG_RELEASE_STORE_PASSWORD)"
KS_ALIAS="$(prop SECUREMSG_RELEASE_KEY_ALIAS)"

for f in "$LINEAGE" "$KS"; do
  [ -f "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

cd "$ANDROID_DIR"
# Always a clean build. kotlinc inlines BuildConfig.VERSION_NAME at every use
# site, and an incremental assembleRelease once shipped a dex whose field said
# 0.20.0 while LoginScreen still carried 0.17.0 and the updater 0.19.0 — the
# updater would have judged its own release "newer" and reinstalled it forever.
./gradlew :app:clean :app:assembleRelease -q

UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
OUT="app/build/outputs/apk/release/securemsg-release.apk"
cp -f "$UNSIGNED" "$OUT"

# Both signers: v2 is signed by the original debug key so anything that only
# understands v2 still verifies, while v3 carries the new key plus the lineage
# that proves the rotation. apksigner refuses v2 with a lineage unless the
# oldest signer is supplied, which is exactly why both appear here.
# v1 is off: it only matters below API 24 and minSdk is 31.
#
# --rotation-min-sdk-version 31 forces one plain v3 block instead of apksigner's
# default SDK-targeted v3.1 + v3.0 pair. With two blocks the app's own updater
# reads the archive through getPackageArchiveInfo and it is not decidable here
# which signer it would report; if it picked the older block, the check
# "history.last() == archiveCurrent" fails and the app refuses its own update.
# minSdk is 31, so targeting rotation at 31 covers every device that can install
# this at all and leaves exactly one answer.
"$APKSIGNER" sign \
  --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --next-signer --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --lineage "$LINEAGE" \
  --min-sdk-version 31 \
  --rotation-min-sdk-version 31 \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  "$OUT"

"$APKSIGNER" verify --print-certs --min-sdk-version 31 "$OUT"

# Prove the clean build did its job: no version literal other than the one in
# build.gradle.kts may remain inlined in this app's own classes.
DEXDUMP="$(dirname "$APKSIGNER")/dexdump"
VERSION="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')"
# Theme.kt's design preview draws a mock update row reading "v0.11.1 · 최신";
# it is a screenshot string, not a version, and the only literal allowed to differ.
PREVIEW_LITERAL="0.11.1"
TMPD="$(mktemp -d)"
unzip -o -q "$OUT" 'classes*.dex' -d "$TMPD"
# Most use sites fold the constant into the enclosing template, so the dex holds
# "v0.17.0" or "securemsg-android/0.19.0" rather than a bare literal — demanding
# a quote before the digits made this blind to the exact LoginScreen and updater
# strings the incident above was about. Bounding on non-[0-9.] instead also keeps
# the leading version family open (0.x was the whole match once) while stopping
# "127.0.0.1" in LoginScreen from reading as a 127.0.0 release.
# LC_ALL=C is load-bearing, and not for anything under this repo's control.
# dexdump prints string data as MUTF-8: NUL is C0 80 and anything outside the
# BMP is a CESU-8 surrogate pair, neither of which is valid UTF-8. Under a
# UTF-8 locale awk aborts the entire scan at the first one ("towc: multibyte
# conversion failure") with rc 2, which `set -e` turns into a failed release
# AFTER the APK is built and signed. The first such byte in a real build is
# `const-string "Exif\xC0\x80\xC0\x80"` inside androidx.camera, i.e. a
# dependency this project cannot edit, and it sits in classes.dex while every
# app version literal lives in classes2.dex — so before this, the guard aborted
# before reaching a single app class and silently proved nothing. The scan only
# ever matches ASCII digits and dots, so reading bytes loses it nothing.
REPORT="$("$DEXDUMP" -d "$TMPD"/classes*.dex 2>/dev/null | LC_ALL=C awk -v want="$VERSION" -v allow="$PREVIEW_LITERAL" '
  /Class descriptor/ { inapp = ($0 ~ /Lcom\/yunjelee\/securemsg\//) }
  inapp && /const-string/ {
    s = $0 " "
    while (match(s, /[^0-9.][0-9]+\.[0-9]+\.[0-9]+[^0-9.]/)) {
      v = substr(s, RSTART + 1, RLENGTH - 2)
      if (v == want) found = 1
      else if (v != allow) stale[v] = 1
      s = substr(s, RSTART + RLENGTH - 1)
    }
  }
  END { printf "%d", found + 0; for (v in stale) printf " %s", v }')"
rm -rf "$TMPD"
read -r FOUND STALE <<< "$REPORT"
if [ -n "$STALE" ]; then
  echo "stale inlined version literals in app dex: $STALE (expected only $VERSION)" >&2
  exit 1
fi
# A dexdump that stops emitting the lines this parse depends on produces an
# empty stale list, i.e. it looks exactly like a clean build from here.
if [ "$FOUND" != 1 ]; then
  echo "no $VERSION literal found in app dex: the scan is broken, not the build" >&2
  exit 1
fi
echo "dex version literals OK ($VERSION)"
echo "signed: $OUT"
