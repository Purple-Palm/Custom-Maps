#!/usr/bin/env bash
# Prints the version of this build as "<versionCode> <versionName>". scripts/ci.sh hands both to
# Gradle (-Pcustommaps.versionCode, -Pcustommaps.versionName); release builds use the versionCode,
# every build shows the versionName in Settings.
#
# On GitHub Actions the versionCode is the run number times 100 plus the run attempt: every run and
# every re-run of a run gets a higher one (GitHub allows at most 50 re-runs of a run), as Google Play
# needs for each upload. The versionName shows the run: 0.1.<run>. Outside CI it is 1 and 0.1.0.
#
# Google Play allows versionCodes up to 2,100,000,000. If the workflow is ever renamed or replaced,
# its run numbers start again at 1: raise BASE above the highest versionCode on Google Play first.
set -euo pipefail

readonly BASE=0
readonly PLAY_MAX=2100000000

run="${GITHUB_RUN_NUMBER:-}"
attempt="${GITHUB_RUN_ATTEMPT:-1}"

if [[ -z "$run" ]]; then
    echo "1 0.1.0"
    exit 0
fi
[[ "$run" =~ ^[1-9][0-9]{0,6}$ ]] || { echo "version.sh: GITHUB_RUN_NUMBER is not a run number: $run" >&2; exit 1; }
[[ "$attempt" =~ ^[1-9][0-9]{0,4}$ ]] || { echo "version.sh: GITHUB_RUN_ATTEMPT is not a run attempt: $attempt" >&2; exit 1; }
# Two digits for the attempt. GitHub stops at 51 attempts, so the cap is never reached.
((attempt <= 99)) || attempt=99

code=$((BASE + run * 100 + attempt))
((code <= PLAY_MAX)) || { echo "version.sh: versionCode $code is above Google Play's limit" >&2; exit 1; }
echo "$code 0.1.$run"
