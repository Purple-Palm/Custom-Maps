#!/usr/bin/env bash
# Checks that an APK is ready for devices with 16 KB memory pages (a Google Play requirement):
#   1. every 64-bit native library has its ELF LOAD segments aligned to at least 16 KB;
#   2. native libraries are stored 16 KB aligned inside the APK (zipalign -P 16).
# Needs readelf (binutils) and the Android SDK build-tools.
set -euo pipefail

apk="${1:?usage: scripts/check-16kb-alignment.sh path/to/app.apk}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk" ]] || { echo "ANDROID_HOME is not set" >&2; exit 2; }

zipalign="$(ls -d "$sdk"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -n 1)"
[[ -x "$zipalign" ]] || { echo "zipalign not found under $sdk/build-tools" >&2; exit 2; }
command -v readelf >/dev/null || { echo "readelf not found (install binutils)" >&2; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
unzip -q "$apk" 'lib/*' -d "$work" 2>/dev/null || true

failed=0
checked=0
for so in "$work"/lib/arm64-v8a/*.so "$work"/lib/x86_64/*.so; do
    [[ -e "$so" ]] || continue
    checked=$((checked + 1))
    while read -r align; do
        if (( align < 16384 )); then
            echo "Not 16 KB aligned: ${so#"$work"/} (LOAD align $align)"
            failed=1
        fi
    done < <(readelf -lW "$so" | awk '$1 == "LOAD" { print $NF }' | sort -u)
done

if ! "$zipalign" -c -P 16 -v 4 "$apk" > "$work/zipalign.log"; then
    grep -v ' (OK' "$work/zipalign.log" || true
    echo "zipalign: APK entries are not 16 KB aligned"
    failed=1
fi

if (( failed )); then
    echo "16 KB page size check FAILED"
    exit 1
fi
echo "16 KB page size check passed ($checked native libraries)"
