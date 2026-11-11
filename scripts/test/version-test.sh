#!/usr/bin/env bash
# Tests scripts/version.sh: outside CI it is 1 and 0.1.0; on CI the versionCode rises with every run
# and every re-run, stays below Google Play's limit, and bad input fails. Needs only bash.
set -euo pipefail
cd "$(dirname "$0")/../.."

passed=0
failed=0

# version RUN ATTEMPT: what version.sh prints for that run and attempt ("-" leaves a variable unset).
version() {
    local vars=(-u GITHUB_RUN_NUMBER -u GITHUB_RUN_ATTEMPT)
    [[ "$1" == "-" ]] || vars+=("GITHUB_RUN_NUMBER=$1")
    [[ "$2" == "-" ]] || vars+=("GITHUB_RUN_ATTEMPT=$2")
    env "${vars[@]}" scripts/version.sh 2>/dev/null
}

check() { # check NAME COMMAND...
    if "${@:2}"; then passed=$((passed + 1)); else failed=$((failed + 1)); echo "FAIL: $1"; fi
}

expect() { # expect RUN ATTEMPT OUTPUT
    local got
    got="$(version "$1" "$2")" || got="(failed)"
    check "run $1, attempt $2: '$got', want '$3'" test "$got" = "$3"
}

refuses() { # refuses RUN ATTEMPT
    if version "$1" "$2" >/dev/null; then
        failed=$((failed + 1))
        echo "FAIL: run '$1', attempt '$2' was not refused"
    else
        passed=$((passed + 1))
    fi
}

expect - - "1 0.1.0"
expect - 3 "1 0.1.0"
expect 1 - "101 0.1.1"
expect 1 1 "101 0.1.1"
expect 42 1 "4201 0.1.42"
expect 42 2 "4202 0.1.42"
expect 42 51 "4251 0.1.42"
expect 42 150 "4299 0.1.42"
expect 43 1 "4301 0.1.43"
expect 9999999 51 "999999951 0.1.9999999"

refuses 0 1
refuses 042 1
refuses abc 1
refuses 10000000 1
refuses "4 2" 1
refuses 42 0
refuses 42 x
refuses 42 -1

# Every run and every re-run gets a higher versionCode than all before it.
previous=0
rising=true
for run in 1 2 3 9 10 11 99 100 101 999 1000 12345; do
    for attempt in 1 2 9 10 11 50 51; do
        code="$(version "$run" "$attempt" | cut -d ' ' -f 1)"
        if ((code <= previous)); then rising=false; echo "run $run attempt $attempt: $code after $previous"; fi
        previous="$code"
    done
done
check "versionCodes rise with every run and re-run" "$rising"

echo "version.sh: passed $passed, failed $failed"
((failed == 0))
