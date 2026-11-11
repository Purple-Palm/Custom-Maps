#!/usr/bin/env bash
# Everything the CI job runs. Kept outside .github/workflows so that build changes do not
# require editing the workflow file.
set -euo pipefail
cd "$(dirname "$0")/.."

# Tests of the CI scripts themselves; they need no network, no Android SDK and no secret. The upload
# to Google Play is tested against a local stand-in for Google's endpoints with a throwaway key.
scripts/test/version-test.sh
scripts/test/play-upload-test.sh

# The version of this build (scripts/version.sh): on CI the versionCode rises with every run and re-run.
version="$(scripts/version.sh)"
read -r version_code version_name <<<"$version"
version_args=("-Pcustommaps.versionCode=$version_code" "-Pcustommaps.versionName=$version_name")
echo "Version $version_name; release builds get versionCode $version_code."

# Optional shared debug signing key from the repository secrets (README, "Shared debug signing key").
if [[ -n "${DEBUG_KEYSTORE_BASE64:-}" ]]; then
    keystore="${RUNNER_TEMP:-$(mktemp -d)}/debug-signing.p12"
    printf '%s' "$DEBUG_KEYSTORE_BASE64" | base64 --decode --ignore-garbage > "$keystore"
    export DEBUG_KEYSTORE_FILE="$keystore"
    echo "Debug build is signed with the shared key."
else
    echo "No shared debug key configured: the APK is signed with a temporary key."
fi

# Optional upload key for Google Play from the repository secrets: all four or none. It is decoded
# before the first Gradle run, because every Gradle run checks that all four release values or none
# are set. The keystore lives in a private folder of the runner's temporary directory and is deleted
# when this script ends.
release_secrets=(RELEASE_KEYSTORE_BASE64 RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD)
missing=()
for name in "${release_secrets[@]}"; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
done
build_bundle=false
if ((${#missing[@]} == 0)); then
    release_dir="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-key.XXXXXXXX")"
    trap 'rm -rf "$release_dir"' EXIT
    (umask 077 && printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 --decode --ignore-garbage > "$release_dir/upload.p12")
    unset RELEASE_KEYSTORE_BASE64
    export RELEASE_KEYSTORE_FILE="$release_dir/upload.p12"
    build_bundle=true
    echo "The app bundle for Google Play is signed with the upload key."
elif ((${#missing[@]} < ${#release_secrets[@]})); then
    echo "The release signing key is only partly configured; missing secrets: ${missing[*]}" >&2
    exit 1
else
    echo "No release key configured: no app bundle is built."
fi

# The published APK is for phones only (arm64), which roughly halves its size.
./gradlew assembleDebug testDebugUnitTest :core-nav:test :app:lintDebug -Pcustommaps.abis=arm64-v8a \
    "${version_args[@]}" --stacktrace
scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk

# The APK is published as is (not zipped); its name says which run and commit it comes from.
revision="${GITHUB_SHA:-local}"
mkdir -p build/ci
cp app/build/outputs/apk/debug/app-debug.apk "build/ci/custommaps-debug-${GITHUB_RUN_NUMBER:-local}-${revision:0:7}.apk"

if [[ "$build_bundle" == true ]]; then
    # The app bundle has both ABIs (its own Gradle run, without the arm64 filter): Google Play sends each
    # phone only its own. AGP's universal APK is made from the bundle by the bundletool inside AGP, as
    # Google Play makes its APKs; checking it shows that every native library in the bundle reaches the
    # phone 16 KB aligned. It is only checked, never published.
    ./gradlew :app:bundleRelease :app:packageReleaseUniversalApk "${version_args[@]}" --stacktrace
    scripts/check-16kb-alignment.sh app/build/outputs/apk_from_bundle/release/app-release-universal.apk

    # Published as its own artifact, named with its versionCode.
    bundle="custommaps-release-${version_code}-${revision:0:7}.aab"
    cp app/build/outputs/bundle/release/app-release.aab "build/ci/$bundle"
    echo "App bundle for Google Play: build/ci/$bundle"
    if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
        {
            echo "bundle=$bundle"
            echo "version-code=$version_code"
        } >>"$GITHUB_OUTPUT"
    fi
fi
