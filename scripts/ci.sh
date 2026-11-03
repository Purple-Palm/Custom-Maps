#!/usr/bin/env bash
# Everything the CI job runs. Kept outside .github/workflows so that build changes do not
# require editing the workflow file.
set -euo pipefail
cd "$(dirname "$0")/.."

# Optional shared debug signing key from the repository secrets (README, "Shared debug signing key").
if [[ -n "${DEBUG_KEYSTORE_BASE64:-}" ]]; then
    keystore="${RUNNER_TEMP:-$(mktemp -d)}/debug-signing.p12"
    printf '%s' "$DEBUG_KEYSTORE_BASE64" | base64 --decode --ignore-garbage > "$keystore"
    export DEBUG_KEYSTORE_FILE="$keystore"
    echo "Debug build is signed with the shared key."
else
    echo "No shared debug key configured: the APK is signed with a temporary key."
fi

# The published APK is for phones only (arm64), which roughly halves its size.
./gradlew assembleDebug testDebugUnitTest :core-nav:test :app:lintDebug -Pcustommaps.abis=arm64-v8a --stacktrace
scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk

# The APK is published as is (not zipped); its name says which run and commit it comes from.
revision="${GITHUB_SHA:-local}"
mkdir -p build/ci
cp app/build/outputs/apk/debug/app-debug.apk "build/ci/custommaps-debug-${GITHUB_RUN_NUMBER:-local}-${revision:0:7}.apk"
