"""A local stand-in for oauth2.googleapis.com and androidpublisher.googleapis.com, so that
scripts/play-upload.sh can be tested with no network, no real key and no Google account.

It checks the signature and claims of the JWT (with the openssl command), the bearer token on every
call and the resumable upload protocol, can cut the first upload short, and logs every request as
one JSON line. Python standard library only.

Usage: python3 -I mock_play.py PUBLIC_KEY_PEM LOG_FILE PORT_FILE
It listens on a free port of 127.0.0.1 and writes that port to PORT_FILE, then the access token and
the upload id it hands out to LOG_FILE.secrets (the test makes sure neither is ever printed).
Behaviour (environment): MOCK_TRACKS (track names, space separated), MOCK_INTERNAL_CODES
(versionCodes already on the internal testing track), MOCK_VERSION_CODE (what the upload reports),
MOCK_INTERRUPT, MOCK_RANGE_PLAIN, MOCK_BAD_SHA, MOCK_TOKEN_ERROR, MOCK_NOT_FOUND, MOCK_NO_PERMISSION,
MOCK_DRAFT_APP, MOCK_COMMIT_IN_REVIEW (set to 1 to turn on).
"""
import base64
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PUBLIC_KEY = sys.argv[1]
LOG = sys.argv[2]
PORT_FILE = sys.argv[3]
ACCESS_TOKEN = "ya29.MOCK-ACCESS-TOKEN-" + os.urandom(8).hex()
UPLOAD_ID = "MOCK-UPLOAD-ID-" + os.urandom(8).hex()
PACKAGE = "io.github.purplepalm.custommaps"
EDIT = "mockedit123"
KEY_ID = "0123456789abcdef0123456789abcdef01234567"
CLIENT_EMAIL = "uploader@mock-project.example"
state = {"declared": None, "data": b"", "interrupted": False}


def on(name):
    return os.environ.get(name) == "1"


def b64url_decode(part):
    return base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))


def log(entry):
    with open(LOG, "a") as f:
        f.write(json.dumps(entry) + "\n")


def signature_ok(signing_input, signature):
    """RS256: RSASSA-PKCS1-v1_5 with SHA-256, checked by the openssl command against the public key."""
    with tempfile.TemporaryDirectory() as d:
        data, sig = os.path.join(d, "data"), os.path.join(d, "sig")
        with open(data, "wb") as f:
            f.write(signing_input)
        with open(sig, "wb") as f:
            f.write(signature)
        result = subprocess.run(["openssl", "dgst", "-sha256", "-verify", PUBLIC_KEY, "-signature", sig, data],
                                capture_output=True)
        return result.returncode == 0


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def reply(self, code, body=None, headers=None):
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(code)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def error(self, code, message, status="MOCK", reason=None):
        body = {"error": {"code": code, "message": message, "status": status}}
        if reason:
            body["error"]["details"] = [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": reason}]
        self.reply(code, body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def handle_any(self, method):
        url = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(url.query)
        path = url.path
        log({"method": method, "path": path, "query": {k: v[0] for k, v in query.items()},
             "auth": self.headers.get("Authorization") == f"Bearer {ACCESS_TOKEN}",
             "content_range": self.headers.get("Content-Range"),
             "content_length": self.headers.get("Content-Length"),
             "content_type": self.headers.get("Content-Type"),
             "transfer_encoding": self.headers.get("Transfer-Encoding")})
        edits = f"/androidpublisher/v3/applications/{PACKAGE}/edits"
        upload = f"/upload/androidpublisher/v3/applications/{PACKAGE}/edits/{EDIT}/bundles"

        if method == "POST" and path == "/token":
            form = urllib.parse.parse_qs(self.body().decode())
            if on("MOCK_TOKEN_ERROR"):
                return self.reply(400, {"error": "invalid_grant", "error_description": "Invalid JWT Signature."})
            if form.get("grant_type") != ["urn:ietf:params:oauth:grant-type:jwt-bearer"]:
                return self.reply(400, {"error": "unsupported_grant_type"})
            header, claims, signature = form["assertion"][0].split(".")
            checks = {
                "signature": signature_ok(f"{header}.{claims}".encode(), b64url_decode(signature)),
                "header": json.loads(b64url_decode(header)) == {"alg": "RS256", "typ": "JWT", "kid": KEY_ID},
            }
            claims = json.loads(b64url_decode(claims))
            checks["claims"] = (claims.get("iss") == CLIENT_EMAIL
                                and claims.get("scope") == "https://www.googleapis.com/auth/androidpublisher"
                                and claims.get("aud") == f"http://127.0.0.1:{self.server.server_port}/token"
                                and 0 < claims["exp"] - claims["iat"] <= 3600
                                and abs(claims["iat"] - time.time()) < 120)
            log({"jwt": checks})
            if not all(checks.values()):
                return self.reply(400, {"error": "invalid_grant", "error_description": "Invalid JWT."})
            return self.reply(200, {"access_token": ACCESS_TOKEN, "expires_in": 3599, "token_type": "Bearer"})

        if self.headers.get("Authorization") != f"Bearer {ACCESS_TOKEN}":
            self.body()
            return self.error(401, "Request had invalid authentication credentials.", "UNAUTHENTICATED")

        if method == "POST" and path == edits:
            self.body()
            if on("MOCK_NOT_FOUND"):
                return self.error(404, f"Package not found: {PACKAGE}.", "NOT_FOUND")
            if on("MOCK_NO_PERMISSION"):
                return self.error(403, f"The caller does not have permission: {CLIENT_EMAIL} in project 123456789012.",
                                  "PERMISSION_DENIED")
            return self.reply(200, {"id": EDIT, "expiryTimeSeconds": str(int(time.time()) + 3600)})
        if method == "GET" and path == f"{edits}/{EDIT}/tracks":
            names = os.environ.get("MOCK_TRACKS", "production beta alpha internal").split()
            codes = os.environ.get("MOCK_INTERNAL_CODES", "").split()
            tracks = []
            for name in names:
                releases = []
                if name in ("internal", "qa") and codes:
                    releases = [{"versionCodes": codes, "status": "completed"}]
                if name == "production":
                    releases = [{"versionCodes": ["999999999"], "status": "completed"}]
                tracks.append({"track": name, "releases": releases})
            return self.reply(200, {"kind": "androidpublisher#tracksListResponse", "tracks": tracks})
        if method == "POST" and path == upload and query.get("uploadType") == ["resumable"]:
            self.body()
            state["declared"] = int(self.headers["X-Upload-Content-Length"])
            state["data"] = b""
            if self.headers.get("X-Upload-Content-Type") != "application/octet-stream":
                return self.error(400, "bad X-Upload-Content-Type")
            location = f"http://127.0.0.1:{self.server.server_port}{upload}?uploadType=resumable&upload_id={UPLOAD_ID}"
            return self.reply(200, None, {"Location": location})
        if method == "PUT" and path == upload and query.get("upload_id") == [UPLOAD_ID]:
            total = state["declared"]
            content_range = self.headers.get("Content-Range")
            if content_range == f"bytes */{total}":  # status query
                self.body()
                got = len(state["data"])
                if got >= total:
                    return self.finish_upload()
                if not got:
                    return self.reply(308)
                received = f"0-{got - 1}" if on("MOCK_RANGE_PLAIN") else f"bytes=0-{got - 1}"
                return self.reply(308, None, {"Range": received})
            n = int(self.headers["Content-Length"])
            if content_range:
                span, size = content_range.split(" ")[1].split("/")
                start = int(span.split("-")[0])
                if int(size) != total or start != len(state["data"]):
                    return self.error(400, f"bad Content-Range {content_range}")
            else:
                state["data"] = b""
            if on("MOCK_INTERRUPT") and not state["interrupted"]:
                state["interrupted"] = True
                part = self.rfile.read(n // 3)
                state["data"] += part
                log({"interrupted_after": len(part)})
                self.close_connection = True
                self.connection.shutdown(2)
                return
            state["data"] += self.rfile.read(n)
            if len(state["data"]) < total:
                return self.reply(308, None, {"Range": f"bytes=0-{len(state['data']) - 1}"})
            return self.finish_upload()
        if method == "PUT" and path.startswith(f"{edits}/{EDIT}/tracks/"):
            body = json.loads(self.body())
            log({"track_update": path.rsplit("/", 1)[-1], "track_body": body})
            if on("MOCK_DRAFT_APP"):
                return self.error(400, "Only releases with status draft may be created on draft app.",
                                  "INVALID_ARGUMENT")
            return self.reply(200, body)
        if method == "POST" and path == f"{edits}/{EDIT}:commit":
            self.body()
            if on("MOCK_COMMIT_IN_REVIEW"):
                return self.error(400, "You already have changes in review. Cancel this review or wait for it to "
                                       "complete before you try again.", "FAILED_PRECONDITION",
                                  "CHANGES_ALREADY_IN_REVIEW")
            log({"committed": True})
            return self.reply(200, {"id": EDIT})
        if method == "DELETE" and path == f"{edits}/{EDIT}":
            log({"deleted": True})
            return self.reply(200, {})
        self.body()
        return self.error(404, f"the mock has no route for {method} {path}")

    def finish_upload(self):
        data = state["data"]
        sha = "0" * 64 if on("MOCK_BAD_SHA") else hashlib.sha256(data).hexdigest()
        log({"upload_complete": len(data)})
        return self.reply(201, {"versionCode": int(os.environ.get("MOCK_VERSION_CODE", "1042")),
                                "sha256": sha, "sha1": hashlib.sha1(data).hexdigest()})

    def do_GET(self):
        self.handle_any("GET")

    def do_POST(self):
        self.handle_any("POST")

    def do_PUT(self):
        self.handle_any("PUT")

    def do_DELETE(self):
        self.handle_any("DELETE")


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    with open(LOG + ".secrets", "w") as f:
        f.write(ACCESS_TOKEN + "\n" + UPLOAD_ID + "\n")
    with open(PORT_FILE + ".part", "w") as f:
        f.write(str(server.server_port))
    os.rename(PORT_FILE + ".part", PORT_FILE)
    server.serve_forever()
