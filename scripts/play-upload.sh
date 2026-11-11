#!/usr/bin/env bash
# Publishes a signed Android App Bundle on the internal testing track of Google Play, through the
# Google Play Developer API: it opens an edit, uploads the bundle (resumable upload), makes it the
# completed release of the internal testing track and commits the edit without cancelling a review
# in progress. It publishes nowhere else: no other track can be named. Needs only bash, curl, jq,
# openssl and coreutils, which the GitHub-hosted ubuntu-24.04 runner has.
#
# Usage: scripts/play-upload.sh --package NAME --track internal --version-code CODE BUNDLE.aab
# with PLAY_SERVICE_ACCOUNT_JSON_BASE64 set to the service account's JSON key as one line of base64.
#
# Google Play takes uploads through the API only after the app's first release was rolled out by
# hand in Play Console (README, "Release through Google Play"). A bundle whose versionCode is not
# above every versionCode on the internal testing track is not uploaded: when two builds finish out
# of order, the older one stops there, and a re-run of a published build changes nothing.
#
# Secrets: the decoded key, the signed JWT, the access token and the upload session URL are never
# printed and never put on a command line, which other processes can read. They exist only as files
# in a private temporary folder (mode 700, files 600) that is deleted on exit, also when the job is
# cancelled; the key files are deleted as soon as the JWT is signed. On GitHub Actions the JWT, the
# access token, the session URL and its upload_id are also registered with ::add-mask::, so the
# runner hides them should anything print them. Errors show only Google's error text, with email
# addresses and long numbers masked, because the logs of a public repository are public.

set +x # Never trace this script: a trace would print the access token.
set -euo pipefail
umask 077

readonly OAUTH_TOKEN_URL="https://oauth2.googleapis.com/token"
readonly PLAY_API="https://androidpublisher.googleapis.com/androidpublisher/v3"
readonly PLAY_UPLOAD_API="https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
readonly SCOPE="https://www.googleapis.com/auth/androidpublisher"
# The access token goes to the upload session URL too, so that must be a Google API host.
readonly UPLOAD_URL_PATTERN='^https://([a-z0-9-]+\.)*googleapis\.com/[^[:space:]"\\]*$'

fail() {
    printf 'play-upload: %s\n' "$*" >&2
    exit 1
}

usage() {
    fail "usage: play-upload.sh --package NAME --track internal --version-code CODE BUNDLE.aab"
}

package=""
track=""
version_code=""
bundle=""
while (($#)); do
    case "$1" in
        --package | --track | --version-code)
            (($# >= 2)) || usage
            case "$1" in
                --package) package="$2" ;;
                --track) track="$2" ;;
                --version-code) version_code="$2" ;;
            esac
            shift 2
            ;;
        -*) usage ;;
        *)
            [[ -z "$bundle" ]] || usage
            bundle="$1"
            shift
            ;;
    esac
done
[[ -n "$bundle" ]] || usage

# Everything below goes into URLs and JSON, so it is checked first.
[[ "$package" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] || fail "invalid package name: $package"
[[ "$track" == "internal" ]] || fail "only the internal testing track can be published to (--track internal)"
[[ "$version_code" =~ ^[1-9][0-9]{0,9}$ ]] || fail "invalid versionCode: $version_code"
for tool in curl jq openssl base64 sha256sum stat od head sed; do
    command -v "$tool" >/dev/null || fail "$tool is not installed"
done
[[ -f "$bundle" ]] || fail "no bundle at $bundle"
[[ "$(head -c 4 "$bundle" | od -An -tx1 | tr -d ' \n')" == "504b0304" ]] || fail "$bundle is not an app bundle"
[[ -n "${PLAY_SERVICE_ACCOUNT_JSON_BASE64:-}" ]] || fail "PLAY_SERVICE_ACCOUNT_JSON_BASE64 is not set"

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/play-upload.XXXXXXXX")"
edit_id=""
# Set while the commit is sent and Google Play has not refused it: until it answers, nobody can say
# whether the release went out.
committing=""

# An edit that is not committed is deleted, so that it does not linger until it expires.
delete_edit() {
    if [[ -n "$edit_id" && -f "$work/auth-header" ]]; then
        curl -sS --connect-timeout 30 --max-time 60 -o /dev/null -X DELETE -H "@$work/auth-header" \
            "$PLAY_API/applications/$package/edits/$edit_id" 2>/dev/null || true
    fi
    edit_id=""
}

cleanup() {
    local status=$?
    if [[ -n "$committing" ]]; then
        # The answer to the commit was lost, was an error on Google's side, or never came because the
        # job was stopped: the release may be out. The edit is left alone; the next edit replaces it.
        echo "play-upload: the commit was sent, but whether Google Play applied it is unknown:" \
            "internal testing may already have versionCode $version_code. Check Play Console, or use" \
            "Re-run failed jobs: it publishes nothing if internal testing already has it." >&2
    elif [[ -n "$edit_id" ]]; then
        delete_edit
        printf 'play-upload: nothing was published; the edit was deleted\n' >&2
    fi
    rm -rf "$work"
    exit "$status"
}
# A cancelled run on GitHub sends SIGINT and, 7.5 s later, SIGTERM to the step's own process, which
# is this script (the workflow starts it with exec); the SIGTERM at the latest ends it and runs this
# cleanup at once, even during an upload. There is deliberately no INT or TERM trap: bash would hold
# it back until the running curl ends, and the runner kills the script 2.5 s after the SIGTERM.
trap cleanup EXIT

# Registers a value derived from a secret with the GitHub runner, which then hides it in the log.
# printf is a shell builtin, so the value never appears on a command line.
mask() {
    if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
        printf '::add-mask::%s\n' "$1"
    fi
}

base64url() {
    openssl base64 -A | tr '+/' '-_' | tr -d '=\n'
}

# Google's error text from the last response and nothing else from it, with email addresses and long
# numbers (such as a Google Cloud project number) masked.
google_error() {
    local text
    text="$(jq -r '(.error.message? // .error_description? // .error? // empty) | tostring' \
        "$work/response" 2>/dev/null | head -c 1000)" || text=""
    [[ -n "$text" ]] || text="no error text"
    printf '%s' "$text" | tr '\n' ' ' |
        sed -E 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+/<email address>/g; s/[0-9]{10,}/<number>/g'
}

# What to do about an error that only the owner can fix, in plain words.
hint() { # hint HTTP_CODE
    local text
    text="$(jq -r '[.error.message?, .error.status?, .error.details[]?.reason?] | map(strings) | join(" ")' \
        "$work/response" 2>/dev/null | tr '[:upper:]' '[:lower:]')" || text=""
    case "$text" in
        *"draft app"*)
            echo "The app is still a draft in Play Console: its first release has not been rolled out." \
                "Roll out the first bundle by hand on Internal testing, then run this job again." ;;
        *"package not found"*)
            echo "Google Play has no app with this package name that this service account can see." \
                "Upload the first bundle by hand in Play Console and roll it out on Internal testing," \
                "and check that the service account is invited for this app." ;;
        *changes_already_in_review* | *"changes in review"*)
            echo "Changes to the app are in review in Play Console, and this job never cancels a review." \
                "Run it again once the review has ended." ;;
        *"foreground service"*)
            echo "Play Console asks for the foreground service declaration (App content)." \
                "Fill it in, then run this job again." ;;
        *"version code"*)
            echo "Google Play already has this versionCode or a higher one." \
                "Push a new commit or start the workflow by hand: a new run gets a higher versionCode." ;;
        *)
            case "$1" in
                401 | 403)
                    echo "The service account may not do this. Invite it in Play Console (Users and" \
                        "permissions) for this app with the permission to release to testing tracks, and" \
                        "enable the Google Play Android Developer API in its Google Cloud project." ;;
                404) echo "Google Play does not know this app or this edit." ;;
            esac ;;
    esac
}

# call DESCRIPTION RETRIES METHOD URL [CURL_OPTIONS...]
# Calls the API with the access token from a header file and leaves the answer in $work/response.
# Network failures, 429 and 5xx are retried RETRIES times, only for calls that are safe to repeat.
call() {
    local description="$1" retries="$2" method="$3" url="$4" code attempt=0
    shift 4
    while :; do
        rm -f "$work/response" # An error text must never come from an earlier call.
        code="$(curl -sS --connect-timeout 30 --max-time 300 -X "$method" -H "@$work/auth-header" \
            -o "$work/response" -w '%{http_code}' "$@" "$url")" || code="000"
        [[ "$code" == 2?? ]] && return 0
        if [[ "$code" =~ ^(000|429|5[0-9][0-9])$ ]] && ((attempt < retries)); then
            attempt=$((attempt + 1))
            sleep $((attempt * 10))
            continue
        fi
        [[ -f "$work/response" ]] || : >"$work/response"
        [[ "$code" != 4?? ]] || committing="" # Google Play refused the call, so nothing was committed.
        fail "$description failed: HTTP $code: $(google_error) $(hint "$code")"
    done
}

# 1. The service account key signs a JWT, which Google exchanges for an access token (OAuth 2.0 for
#    server to server applications: RS256; claims iss, scope, aud, iat, exp at most an hour after iat).
printf '%s' "$PLAY_SERVICE_ACCOUNT_JSON_BASE64" | base64 --decode --ignore-garbage >"$work/key.json" 2>/dev/null ||
    fail "PLAY_SERVICE_ACCOUNT_JSON_BASE64 is not valid base64"
unset PLAY_SERVICE_ACCOUNT_JSON_BASE64 # Child processes (curl, jq, openssl) do not inherit it.
jq -e '.type == "service_account" and (.client_email | type == "string") and (.private_key | type == "string")' \
    "$work/key.json" >/dev/null 2>&1 || fail "PLAY_SERVICE_ACCOUNT_JSON_BASE64 does not hold a service account JSON key"
jq -r '.private_key' "$work/key.json" >"$work/key.pem"

now="$(date +%s)"
{
    jq -cj '{alg: "RS256", typ: "JWT"} + (if .private_key_id then {kid: .private_key_id} else {} end)' \
        "$work/key.json" | base64url
    printf '.'
    jq -cj --arg scope "$SCOPE" --arg aud "$OAUTH_TOKEN_URL" --argjson iat "$now" \
        '{iss: .client_email, scope: $scope, aud: $aud, iat: $iat, exp: ($iat + 3600)}' \
        "$work/key.json" | base64url
} >"$work/jwt"
openssl dgst -sha256 -sign "$work/key.pem" -binary <"$work/jwt" 2>/dev/null | base64url >"$work/signature" ||
    fail "the private key of the service account key cannot sign"
[[ -s "$work/signature" ]] || fail "the private key of the service account key cannot sign"
printf '.%s' "$(<"$work/signature")" >>"$work/jwt"
rm -f "$work/key.pem" "$work/key.json" "$work/signature"
mask "$(<"$work/jwt")"

code="$(curl -sS --connect-timeout 30 --max-time 60 --retry 3 -o "$work/response" -w '%{http_code}' \
    --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
    --data-urlencode "assertion@$work/jwt" "$OAUTH_TOKEN_URL")" || code="000"
rm -f "$work/jwt"
if [[ "$code" != "200" ]]; then
    [[ -f "$work/response" ]] || : >"$work/response"
    advice=""
    [[ "$code" != 4?? ]] || advice="Google refused the service account key: PLAY_SERVICE_ACCOUNT_JSON_BASE64 holds a deleted or wrong key."
    fail "getting an access token failed: HTTP $code: $(google_error) $advice"
fi
jq -er '.access_token' "$work/response" >"$work/token" 2>/dev/null || fail "Google's answer has no access token"
rm -f "$work/response"
mask "$(<"$work/token")"
printf 'Authorization: Bearer %s\n' "$(<"$work/token")" >"$work/auth-header"
rm -f "$work/token"
echo "Signed in to the Google Play Developer API."

# 2. Open an edit.
call "opening an edit" 3 POST "$PLAY_API/applications/$package/edits" \
    -H 'Content-Type: application/json' --data-binary '{}'
edit_id="$(jq -er '.id' "$work/response" 2>/dev/null)" || fail "Google Play's edit has no id"
[[ "$edit_id" =~ ^[A-Za-z0-9_-]+$ ]] || fail "unexpected edit id"

# 3. Find the internal testing track and the highest versionCode on it. The API documentation names
#    it "qa", the API has long answered "internal"; whichever Google Play lists is used.
call "reading the tracks" 3 GET "$PLAY_API/applications/$package/edits/$edit_id/tracks"
tracks=" $(jq -r '[.tracks[]?.track | strings] | join(" ")' "$work/response") "
if [[ "$tracks" == *" internal "* ]]; then
    play_track="internal"
elif [[ "$tracks" == *" qa "* ]]; then
    play_track="qa"
else
    fail "Google Play lists no internal testing track for this app (tracks:$tracks)"
fi
highest="$(jq -r --arg track "$play_track" \
    '[.tracks[]? | select(.track == $track) | .releases[]?.versionCodes[]? | tonumber] | max // 0' "$work/response")"
[[ "$highest" =~ ^[0-9]+$ ]] || fail "unexpected versionCodes on the internal testing track"
if ((highest >= version_code)); then
    delete_edit
    echo "Internal testing already has versionCode $highest, not lower than $version_code: nothing to publish."
    exit 0
fi

# 4. Upload the bundle with the resumable upload protocol, resuming after an interruption.
size="$(stat -c %s "$bundle")"
sha256="$(sha256sum "$bundle" | cut -d ' ' -f 1)"
echo "Uploading $(basename "$bundle"): versionCode $version_code, $size bytes, sha256 $sha256."
call "starting the upload" 3 POST "$PLAY_UPLOAD_API/applications/$package/edits/$edit_id/bundles?uploadType=resumable" \
    -H 'X-Upload-Content-Type: application/octet-stream' -H "X-Upload-Content-Length: $size" \
    -H 'Content-Type:' --data-binary '' -D "$work/headers"
session="$(sed -n 's/^[Ll]ocation:[[:space:]]*//p' "$work/headers" | tr -d '\r' | head -n 1)"
[[ "$session" =~ $UPLOAD_URL_PATTERN ]] || fail "the upload session URL is not on googleapis.com"
mask "$session"
upload_id="$(sed -n 's/.*[?&]upload_id=\([^&]*\).*/\1/p' <<<"$session")"
[[ -z "$upload_id" ]] || mask "$upload_id"
# The session URL grants the upload by itself, so curl reads it from a config file, not from argv.
printf 'url = "%s"\n' "$session" >"$work/session.curl"
unset session upload_id
: >"$work/empty"

offset=0
attempt=0
while :; do
    rm -f "$work/response"
    if ((offset == 0)); then
        code="$(curl -sS --connect-timeout 30 --max-time 900 -K "$work/session.curl" -H "@$work/auth-header" \
            -H 'Content-Type: application/octet-stream' -T "$bundle" -o "$work/response" -w '%{http_code}')" ||
            code="000"
    else
        # -C sends the rest of the file with "Content-Range: bytes OFFSET-LAST/SIZE".
        code="$(curl -sS --connect-timeout 30 --max-time 900 -K "$work/session.curl" -H "@$work/auth-header" \
            -H 'Content-Type: application/octet-stream' -C "$offset" -T "$bundle" -o "$work/response" \
            -w '%{http_code}')" || code="000"
    fi
    [[ "$code" == "200" || "$code" == "201" ]] && break
    [[ -f "$work/response" ]] || : >"$work/response"
    [[ "$code" =~ ^(000|308|5[0-9][0-9])$ ]] || fail "uploading the bundle failed: HTTP $code: $(google_error) $(hint "$code")"
    attempt=$((attempt + 1))
    ((attempt <= 5)) || fail "uploading the bundle failed after $attempt attempts (last HTTP $code)"
    sleep $((attempt * 5))
    # Ask how much arrived: an empty PUT with "Content-Range: bytes */SIZE".
    rm -f "$work/response" "$work/headers"
    code="$(curl -sS --connect-timeout 30 --max-time 60 -K "$work/session.curl" -H "@$work/auth-header" \
        -H "Content-Range: bytes */$size" -T "$work/empty" -D "$work/headers" -o "$work/response" \
        -w '%{http_code}')" || code="000"
    case "$code" in
        200 | 201) break ;;
        308)
            # "Range: bytes=0-LAST", or "Range: 0-LAST" as in Google's example; none: nothing arrived.
            last="$(sed -n 's/^[Rr]ange:[[:space:]]*\(bytes=\)\{0,1\}0-\([0-9]*\).*/\2/p' "$work/headers" | head -n 1)"
            offset=$((${last:--1} + 1))
            echo "The upload was interrupted; resuming at byte $offset."
            ;;
        404 | 410) fail "the upload session has expired or failed (HTTP $code); run this job again" ;;
        *) offset=0 ;; # Unknown state: send the whole file again in the same session.
    esac
done
uploaded="$(jq -er '.versionCode' "$work/response" 2>/dev/null)" || fail "Google Play's answer to the upload has no versionCode"
[[ "$uploaded" == "$version_code" ]] || fail "Google Play read versionCode $uploaded from the bundle, not $version_code"
[[ "$(jq -r '.sha256 // empty' "$work/response")" == "$sha256" ]] || fail "Google Play received a different file (sha256 mismatch)"
rm -f "$work/session.curl"
echo "Uploaded versionCode $version_code."

# 5. Make it the completed release of the internal testing track: it replaces the release before.
jq -n --arg track "$play_track" --arg code "$version_code" \
    '{track: $track, releases: [{versionCodes: [$code], status: "completed"}]}' >"$work/track.json"
call "releasing on internal testing" 3 PUT "$PLAY_API/applications/$package/edits/$edit_id/tracks/$play_track" \
    -H 'Content-Type: application/json' --data-binary "@$work/track.json"

# 6. Commit, without cancelling a review in progress. Never retried: a commit that reached Google
#    Play but lost its answer must not be sent twice. Until Google Play answers, the outcome is
#    unknown, and the log says so if the script ends without an answer or with a 5xx.
committing=1
call "committing the edit" 0 POST \
    "$PLAY_API/applications/$package/edits/$edit_id:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW" \
    -H 'Content-Type: application/json' --data-binary ''
edit_id="" # First: a stop between these two lines must not delete the committed edit.
committing=""
echo "Published versionCode $version_code on internal testing (track $play_track) as a completed release."
