#!/usr/bin/env bash
# Everything the CI job runs. Kept outside .github/workflows so that build changes do not
# require editing the workflow file.
#
# The two signing keys come from the repository secrets, four values each, all four or none (README,
# "Shared debug signing key" and "Release through Google Play"). Each key is given only to the Gradle
# run that signs with it: the debug key only to the debug build, the upload key only to the app
# bundle build. The script tests and the debug build get no release value in their environment. That
# keeps the values out of their output, not away from hostile code: this script's own start
# environment keeps all eight (/proc/<pid>/environ), which any process of the job can read
# (DECISIONS 42). Never trace this script: a trace would print the values.
set +x
set -euo pipefail
cd "$(dirname "$0")/.."

debug_secrets=(DEBUG_KEYSTORE_BASE64 DEBUG_KEYSTORE_PASSWORD DEBUG_KEY_ALIAS DEBUG_KEY_PASSWORD)
release_secrets=(RELEASE_KEYSTORE_BASE64 RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD)

# Prints true when every named secret is set and false when none is. With only some of them set it
# names the missing ones, never a value, and fails.
key_configured() {
    local title="$1" name missing=()
    shift
    for name in "$@"; do
        [[ -n "${!name:-}" ]] || missing+=("$name")
    done
    if ((${#missing[@]} == 0)); then
        echo true
    elif ((${#missing[@]} == $#)); then
        echo false
    else
        echo "$title is only partly configured; missing secrets: ${missing[*]}" >&2
        return 1
    fi
}

# Both sets are checked by name before anything runs. The values then leave the environment of every
# child: they stay in this shell and in its start environment, and each Gradle run below gets only
# its own key's values.
debug_key="$(key_configured "The shared debug signing key" "${debug_secrets[@]}")"
release_key="$(key_configured "The release signing key" "${release_secrets[@]}")"
export -n "${debug_secrets[@]}" "${release_secrets[@]}" DEBUG_KEYSTORE_FILE RELEASE_KEYSTORE_FILE

# Decoded keys live in a private folder (mode 700, files 600) in the runner's temporary directory,
# which is deleted when this script ends, also when it fails or is stopped. A cancelled run on GitHub
# sends SIGINT and, 7.5 s later, SIGTERM to the step's own process, which is this script (the
# workflow starts it with exec); the SIGTERM at the latest ends it and runs the EXIT trap at once,
# even while Gradle runs. There is deliberately no INT or TERM trap: bash would hold it back until
# the running command ends. Only a SIGKILL leaves the folder behind, to the runner, which empties its
# temporary directory at the end of the job.
key_dir="$(umask 077 && mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/signing-keys.XXXXXXXX")"
trap 'rm -rf "$key_dir"' EXIT

# Tests of the CI scripts themselves; they need no network, no Android SDK and no secret. The upload
# to Google Play is tested against a local stand-in for Google's endpoints with a throwaway key.
scripts/test/version-test.sh
scripts/test/play-upload-test.sh

# The version of this build (scripts/version.sh): on CI the versionCode rises with every run and re-run.
version="$(scripts/version.sh)"
read -r version_code version_name <<<"$version"
version_args=("-Pcustommaps.versionCode=$version_code" "-Pcustommaps.versionName=$version_name")
echo "Version $version_name; release builds get versionCode $version_code."

if [[ "$debug_key" == true ]]; then
    echo "Debug build is signed with the shared key."
else
    echo "No shared debug key configured: the APK is signed with a temporary key."
fi
if [[ "$release_key" == true ]]; then
    echo "The app bundle for Google Play is signed with the upload key."
else
    echo "No release key configured: no app bundle is built."
fi

# The debug build with its unit tests and lint. The shared debug key, when it is set, is decoded just
# before and given to this Gradle run only. The published APK is for phones only (arm64), which
# roughly halves its size.
if [[ "$debug_key" == true ]]; then
    (umask 077 && printf '%s' "$DEBUG_KEYSTORE_BASE64" | base64 --decode --ignore-garbage >"$key_dir/debug.p12")
    unset DEBUG_KEYSTORE_BASE64
fi
(
    if [[ "$debug_key" == true ]]; then
        export DEBUG_KEYSTORE_FILE="$key_dir/debug.p12" DEBUG_KEYSTORE_PASSWORD DEBUG_KEY_ALIAS DEBUG_KEY_PASSWORD
    fi
    exec ./gradlew assembleDebug testDebugUnitTest :core-nav:test :app:lintDebug -Pcustommaps.abis=arm64-v8a \
        "${version_args[@]}" --stacktrace
)
rm -f "$key_dir/debug.p12"
unset DEBUG_KEYSTORE_PASSWORD DEBUG_KEY_ALIAS DEBUG_KEY_PASSWORD
scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk

# The APK is published as is (not zipped); its name says which run and commit it comes from. It is
# copied here only after its tests, lint and 16 KB check passed, so CI publishes it even when the app
# bundle part below fails.
revision="${GITHUB_SHA:-local}"
mkdir -p build/ci
cp app/build/outputs/apk/debug/app-debug.apk "build/ci/custommaps-debug-${GITHUB_RUN_NUMBER:-local}-${revision:0:7}.apk"

if [[ "$release_key" == true ]]; then
    # The upload key exists on disk only for this Gradle run, which alone is given its three values.
    (umask 077 && printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 --decode --ignore-garbage >"$key_dir/upload.p12")
    unset RELEASE_KEYSTORE_BASE64

    # The app bundle has both ABIs (its own Gradle run, without the arm64 filter): Google Play sends each
    # phone only its own. AGP's universal APK is made from the bundle by the bundletool inside AGP, as
    # Google Play makes its APKs; checking it shows that every native library in the bundle reaches the
    # phone 16 KB aligned. It is only checked, never published.
    (
        export RELEASE_KEYSTORE_FILE="$key_dir/upload.p12" RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD
        exec ./gradlew :app:bundleRelease :app:packageReleaseUniversalApk "${version_args[@]}" --stacktrace
    )
    rm -f "$key_dir/upload.p12"
    unset RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD
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
