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
./gradlew :app:assembleRelease -q

UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
OUT="app/build/outputs/apk/release/securemsg-release.apk"
cp -f "$UNSIGNED" "$OUT"

# Both signers: v2 is signed by the original debug key so anything that only
# understands v2 still verifies, while v3 carries the new key plus the lineage
# that proves the rotation. apksigner refuses v2 with a lineage unless the
# oldest signer is supplied, which is exactly why both appear here.
# v1 is off: it only matters below API 24 and minSdk is 31.
"$APKSIGNER" sign \
  --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --next-signer --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --lineage "$LINEAGE" \
  --min-sdk-version 31 \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  "$OUT"

"$APKSIGNER" verify --print-certs --min-sdk-version 31 "$OUT"
echo "signed: $OUT"
