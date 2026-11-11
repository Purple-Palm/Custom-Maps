#!/usr/bin/env bash
# Tests scripts/play-upload.sh against scripts/test/mock_play.py, a local stand-in for Google's
# endpoints, with a throwaway key made here: no network, no secret, no Google account.
#
# Checked: the JWT and the edits flow, the internal testing track and no other, the resumable upload
# and its resume, a bundle that is not newer than the track's, a commit that is never sent twice and
# whose lost answer is reported as an unknown outcome, the errors and their plain words, and
# in every run that nothing secret reaches the output (even under bash -x), any command line (curl,
# jq, openssl, base64 and sed are wrapped to record their arguments) or the disk afterwards, and that
# the key file is private while it exists. Needs bash, curl, jq, openssl, python3 and zip.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../play-upload.sh"
t="$(mktemp -d)"
mock_pid=""
trap '[[ -z "$mock_pid" ]] || kill "$mock_pid" 2>/dev/null || true; rm -rf "$t"' EXIT

# A throwaway service account key, shaped like the JSON key Google Cloud hands out.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$t/key.pem" 2>/dev/null
openssl pkey -in "$t/key.pem" -pubout -out "$t/pub.pem"
jq -n --rawfile pk "$t/key.pem" '{type: "service_account", project_id: "mock-project",
    private_key_id: "0123456789abcdef0123456789abcdef01234567", private_key: $pk,
    client_email: "uploader@mock-project.example", client_id: "1",
    token_uri: "https://oauth2.googleapis.com/token"}' >"$t/sa.json"
secret="$(base64 -w 0 "$t/sa.json")"
key_line="$(sed -n 5p "$t/key.pem")" # a line from the middle of the private key

# A stand-in bundle: a zip with 3 MB of random bytes.
head -c 3000000 /dev/urandom >"$t/payload.bin"
(cd "$t" && zip -q -0 custommaps-release-1042-abcdef0.aab payload.bin)
bundle="$t/custommaps-release-1042-abcdef0.aab"
size="$(stat -c %s "$bundle")"

# Tools wrapped to record their command lines; the openssl wrapper also records the modes of the key
# file it signs with and of its folder.
mkdir -p "$t/shims"
for tool in curl jq openssl base64 sed; do
    real="$(command -v "$tool")"
    cat >"$t/shims/$tool" <<EOF
#!/usr/bin/env bash
printf '%s\n' "$tool \$*" >>"$t/argv.log"
if [[ "$tool" == openssl ]]; then
    previous=""
    for argument in "\$@"; do
        [[ "\$previous" != -sign ]] || printf 'key %s folder %s\n' "\$(stat -c %a "\$argument")" \
            "\$(stat -c %a "\$(dirname "\$argument")")" >>"$t/modes.log"
        previous="\$argument"
    done
fi
exec "$real" "\$@"
EOF
    chmod +x "$t/shims/$tool"
done
# The waits between retries are real only on CI's way to Google.
printf '#!/bin/sh\nexit 0\n' >"$t/shims/sleep"
chmod +x "$t/shims/sleep"

passed=0
failed=0
scenario=""
scenario_failed=0
check() { # check NAME COMMAND...
    if "${@:2}"; then passed=$((passed + 1)); else failed=$((failed + 1)); echo "FAIL: $scenario: $1"; fi
}

# Ends the scenario before: when one of its checks failed, shows what the script printed, without the
# trace and without the ::add-mask:: lines, which the CI runner would take as commands.
end_scenario() {
    if [[ -n "$scenario" ]] && ((failed > scenario_failed)); then
        echo "--- $scenario (exit $status):"
        grep -v -e '^+' -e '^::' "$t/out.txt" | sed 's/^/    /' || true
    fi
    scenario=""
}
logged() { grep -qF -- "$1" "$t/log.jsonl"; }
not_logged() { ! grep -qF -- "$1" "$t/log.jsonl"; }
said() { grep -qF -- "$1" "$t/out.txt"; }
not_said() { ! grep -qF -- "$1" "$t/out.txt"; }
last_status() { test "$status" -eq "$1"; }
# requests_to METHOD PATH_END COUNT: the mock got COUNT requests with that method to a path ending so.
requests_to() {
    test "$(jq -s --arg m "$1" --arg p "$2" '[.[] | select(.method == $m and ((.path // "") | endswith($p)))] | length' \
        "$t/log.jsonl")" -eq "$3"
}

start_mock() { # start_mock [ENV=VALUE...]
    rm -f "$t/log.jsonl" "$t/log.jsonl.secrets" "$t/port"
    : >"$t/log.jsonl"
    env "$@" python3 -I "$here/mock_play.py" "$t/pub.pem" "$t/log.jsonl" "$t/port" &
    mock_pid=$!
    for _ in $(seq 100); do [[ -s "$t/port" ]] && break; sleep 0.1; done
    port="$(<"$t/port")"
    # The script under test, with Google's hosts pointed at the mock: whole lines are replaced, and
    # each replacement is checked, so that a renamed constant cannot send a test to Google.
    sed -e "s#^readonly OAUTH_TOKEN_URL=.*#readonly OAUTH_TOKEN_URL=\"http://127.0.0.1:$port/token\"#" \
        -e "s#^readonly PLAY_API=.*#readonly PLAY_API=\"http://127.0.0.1:$port/androidpublisher/v3\"#" \
        -e "s#^readonly PLAY_UPLOAD_API=.*#readonly PLAY_UPLOAD_API=\"http://127.0.0.1:$port/upload/androidpublisher/v3\"#" \
        -e "s#^readonly UPLOAD_URL_PATTERN=.*#readonly UPLOAD_URL_PATTERN='^http://127\\\\.0\\\\.0\\\\.1:[0-9]+/[^[:space:]\"\\\\\\\\]*\$'#" \
        "$script" >"$t/play-upload.sh"
    if [[ "$(grep -c '^readonly [A-Z_]*=.*127' "$t/play-upload.sh")" != 4 ]] || grep -v '^ *#' "$t/play-upload.sh" |
        grep -v '^readonly SCOPE=' | grep -qE 'https://[a-z0-9.-]*googleapis'; then
        echo "the test copy of play-upload.sh still names a Google host"
        exit 1
    fi
}

stop_mock() {
    kill "$mock_pid" 2>/dev/null || true
    wait "$mock_pid" 2>/dev/null || true
    mock_pid=""
}

# run NAME [--mock ENV=VALUE...] [--env ENV=VALUE...] [-- SCRIPT_ARGUMENTS...]
# Runs the script under bash -x against a fresh mock; the default arguments publish the bundle
# above as versionCode 1042. Every outgoing request goes either to the mock or to a dead proxy.
run() {
    end_scenario
    scenario="$1"
    scenario_failed="$failed"
    local mock_env=() script_env=() arguments=()
    shift
    while (($#)); do
        case "$1" in
            --mock) mock_env+=("$2"); shift 2 ;;
            --env) script_env+=("$2"); shift 2 ;;
            --) shift; arguments=("$@"); break ;;
        esac
    done
    ((${#arguments[@]})) || arguments=(--package io.github.purplepalm.custommaps --track internal --version-code 1042 "$bundle")
    start_mock "${mock_env[@]}"
    rm -rf "$t/argv.log" "$t/modes.log" "$t/runner-temp"
    mkdir -p "$t/runner-temp"
    set +e
    env -u GITHUB_ACTIONS PATH="$t/shims:$PATH" RUNNER_TEMP="$t/runner-temp" \
        http_proxy=http://127.0.0.1:9 https_proxy=http://127.0.0.1:9 HTTP_PROXY=http://127.0.0.1:9 \
        HTTPS_PROXY=http://127.0.0.1:9 no_proxy=127.0.0.1 NO_PROXY=127.0.0.1 \
        PLAY_SERVICE_ACCOUNT_JSON_BASE64="$secret" "${script_env[@]}" \
        bash -x "$t/play-upload.sh" "${arguments[@]}" >"$t/out.txt" 2>&1
    status=$?
    set -e
    stop_mock
    # Nothing secret in the output (outside ::add-mask:: lines), in any command line, or on disk.
    mapfile -t handed_out <"$t/log.jsonl.secrets"
    local s
    for s in "$secret" "${secret:0:40}" "$key_line" "PRIVATE KEY" "${handed_out[0]}" "${handed_out[1]}" \
        "eyJhbGciOiJSUzI1NiIs" "uploader@mock-project"; do
        check "output has no secret (${s:0:12}...)" bash -c '! grep -v "^::add-mask::" "$1" | grep -qF -- "$2"' _ "$t/out.txt" "$s"
        check "no command line has a secret (${s:0:12}...)" bash -c '! grep -qF -- "$2" "$1" 2>/dev/null' _ "$t/argv.log" "$s"
    done
    check "the temporary folder is gone" test -z "$(ls -A "$t/runner-temp")"
    if [[ -f "$t/modes.log" ]]; then
        check "the key file and its folder are private" test "$(sort -u "$t/modes.log")" = "key 600 folder 700"
    fi
}

run "publish on internal" --mock MOCK_INTERNAL_CODES="1040 1041"
check "exit 0" last_status 0
check "JWT signature, header and claims" logged '"jwt": {"signature": true, "header": true, "claims": true}'
check "every API call has the token" bash -c 'jq -se "[.[] | select(.method and .path != \"/token\" and .auth == false)] | length == 0" "$1" >/dev/null' _ "$t/log.jsonl"
check "whole bundle uploaded" logged "\"upload_complete\": $size"
check "release body" logged '{"track_update": "internal", "track_body": {"track": "internal", "releases": [{"versionCodes": ["1042"], "status": "completed"}]}}'
check "commit keeps reviews" logged '"path": "/androidpublisher/v3/applications/io.github.purplepalm.custommaps/edits/mockedit123:commit", "query": {"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"}'
check "committed" logged '"committed": true'
check "edit kept" not_logged '"deleted": true'
check "upload not chunked" not_logged '"transfer_encoding": "chunked"'
check "no other track touched" not_logged '/tracks/production'
check "key file was signed with" test -s "$t/modes.log"
check "success line" said "Published versionCode 1042 on internal testing (track internal) as a completed release."

run "track named qa" --mock MOCK_TRACKS="production qa"
check "exit 0" last_status 0
check "qa used" logged '"track_update": "qa"'

run "interrupted upload resumes" --mock MOCK_INTERRUPT=1
check "exit 0" last_status 0
check "was cut off" logged '"interrupted_after"'
check "asked how much arrived" logged "\"content_range\": \"bytes */$size\""
check "resumed with Content-Range" grep -qE "\"content_range\": \"bytes [1-9][0-9]*-$((size - 1))/$size\"" "$t/log.jsonl"
check "whole bundle uploaded" logged "\"upload_complete\": $size"

run "resume with a plain Range header" --mock MOCK_INTERRUPT=1 --mock MOCK_RANGE_PLAIN=1
check "exit 0" last_status 0
check "resumed with Content-Range" grep -qE "\"content_range\": \"bytes [1-9][0-9]*-$((size - 1))/$size\"" "$t/log.jsonl"

run "a newer build is already there" --mock MOCK_INTERNAL_CODES="1041 1050"
check "exit 0" last_status 0
check "nothing uploaded" not_logged '/upload/'
check "edit deleted" logged '"deleted": true'
check "reason given" said "Internal testing already has versionCode 1050, not lower than 1042: nothing to publish."

run "the same build again" --mock MOCK_INTERNAL_CODES="1042"
check "exit 0" last_status 0
check "nothing uploaded" not_logged '/upload/'

run "changes in review" --mock MOCK_COMMIT_IN_REVIEW=1
check "exit 1" last_status 1
check "review explained" said "this job never cancels a review"
check "edit deleted" logged '"deleted": true'
check "nothing published said" said "nothing was published; the edit was deleted"
check "commit sent once" requests_to POST :commit 1

# Google Play applies the commit, but its answer never arrives: the commit is not sent again, and
# the log does not claim that nothing was published.
run "commit answer lost" --mock MOCK_COMMIT_LOST=1
check "exit 1" last_status 1
check "commit sent once" requests_to POST :commit 1
check "outcome called unknown" said "whether Google Play applied it is unknown: internal testing may already have versionCode 1042"
check "not said to be unpublished" not_said "nothing was published"
check "edit left alone" not_logged '"deleted": true'

# A 503 answer to the commit: retrying would publish, so the commit is not sent again either.
run "commit unavailable" --mock MOCK_UNAVAILABLE_ONCE=:commit
check "exit 1" last_status 1
check "commit sent once" requests_to POST :commit 1
check "outcome called unknown" said "whether Google Play applied it is unknown"
check "edit left alone" not_logged '"deleted": true'

run "draft app" --mock MOCK_DRAFT_APP=1
check "exit 1" last_status 1
check "draft explained" said "Roll out the first bundle by hand on Internal testing"
check "edit deleted" logged '"deleted": true'

run "app not found" --mock MOCK_NOT_FOUND=1
check "exit 1" last_status 1
check "not found explained" said "Upload the first bundle by hand in Play Console"

run "no permission" --mock MOCK_NO_PERMISSION=1
check "exit 1" last_status 1
check "permission explained" said "Invite it in Play Console (Users and"
check "email address masked" said "<email address>"
check "project number masked" said "<number>"

run "sha256 mismatch" --mock MOCK_BAD_SHA=1
check "exit 1" last_status 1
check "mismatch reported" said "sha256 mismatch"
check "edit deleted" logged '"deleted": true'

run "versionCode mismatch" --mock MOCK_VERSION_CODE=1043
check "exit 1" last_status 1
check "mismatch reported" said "Google Play read versionCode 1043 from the bundle, not 1042"
check "not committed" not_logged '"committed": true'

run "key refused" --mock MOCK_TOKEN_ERROR=1
check "exit 1" last_status 1
check "refusal shown" said "Invalid JWT Signature. Google refused the service account key"

run "no internal track" --mock MOCK_TRACKS="production beta"
check "exit 1" last_status 1
check "tracks named" said "lists no internal testing track for this app (tracks: production beta )"
check "nothing released" not_logged '"track_update"'

run "on GitHub Actions" --env GITHUB_ACTIONS=true
check "exit 0" last_status 0
check "four values masked" test "$(grep -c '^::add-mask::' "$t/out.txt")" -eq 4
check "upload_id masked alone" grep -qxE '::add-mask::MOCK-UPLOAD-ID-[0-9a-f]+' "$t/out.txt"

# Refused before anything is sent.
for case in "production track|--package io.github.purplepalm.custommaps --track production --version-code 1042 $bundle" \
    "qa named directly|--package io.github.purplepalm.custommaps --track qa --version-code 1042 $bundle" \
    "bad package|--package io.github.purplepalm..x --track internal --version-code 1042 $bundle" \
    "bad versionCode|--package io.github.purplepalm.custommaps --track internal --version-code 01042 $bundle" \
    "not a bundle|--package io.github.purplepalm.custommaps --track internal --version-code 1042 $t/sa.json" \
    "no bundle|--package io.github.purplepalm.custommaps --track internal --version-code 1042"; do
    read -r -a arguments <<<"${case#*|}"
    run "refused: ${case%%|*}" -- "${arguments[@]}"
    check "exit 1" last_status 1
    check "nothing sent" test ! -s "$t/log.jsonl"
done
run "refused: no key" --env PLAY_SERVICE_ACCOUNT_JSON_BASE64=
check "exit 1" last_status 1
check "nothing sent" test ! -s "$t/log.jsonl"

end_scenario

# The host check of the real script refuses upload URLs that are not on googleapis.com.
scenario="host check"
pattern="$(sed -n "s/^readonly UPLOAD_URL_PATTERN='\(.*\)'$/\1/p" "$script")"
check "host pattern found" test -n "$pattern"
for u in "https://androidpublisher.googleapis.com/upload/x?upload_id=1" "https://www.googleapis.com/upload/x"; do
    check "accepts $u" bash -c '[[ "$1" =~ $2 ]]' _ "$u" "$pattern"
done
for u in "https://evil.example/upload" "https://googleapis.com.evil.example/x" "http://www.googleapis.com/x" \
    'https://www.googleapis.com/x"y' 'https://www.googleapis.com/x\y' "https://www.googleapis.com/x y" \
    "https://evilgoogleapis.com/x"; do
    check "refuses $u" bash -c '! [[ "$1" =~ $2 ]]' _ "$u" "$pattern"
done

echo "play-upload.sh: passed $passed, failed $failed"
((failed == 0))
