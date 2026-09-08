"""Publish the existing v1.1 tag and verified APK using Git Credential Manager."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
REPO = "tiem-alpha/youtube"
TAG = "v1.1"
COMMIT = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
NAME = "Video-Companion-v1.1-debug.apk"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
BODY = (ROOT / "releases/v1.1.md").read_text(encoding="utf-8")
assert subprocess.check_output(["git", "rev-parse", TAG], cwd=ROOT, text=True).strip() == COMMIT
assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() == COMMIT
assert not subprocess.check_output(["git", "diff", "HEAD", "--", "app", "gradle", "build.gradle.kts", "settings.gradle.kts"], cwd=ROOT)
payload = APK.read_bytes()
digest = hashlib.sha256(payload).hexdigest()
BODY += f"\n\nSHA-256 (`{NAME}`):\n```text\n{digest}\n```\n"
result = subprocess.run(["git", "credential", "fill"], cwd=ROOT,
    input="protocol=https\nhost=github.com\n\n", text=True, capture_output=True,
    env=dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never"), timeout=60)
credential = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or credential.get("password")
if not token:
    raise SystemExit("No GitHub API credential available; nothing published.")

def request(path, method="GET", data=None, binary=False):
    url = path if path.startswith("https://") else f"https://api.github.com/repos/{REPO}/{path}"
    assert urllib.parse.urlparse(url).hostname in {"api.github.com", "uploads.github.com"}
    headers = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
               "X-GitHub-Api-Version": "2022-11-28"}
    if data is not None:
        headers["Content-Type"] = "application/vnd.android.package-archive" if binary else "application/json"
        if not binary:
            data = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    print(method, urllib.parse.urlparse(url).path, flush=True)
    with urllib.request.urlopen(req, timeout=180) as response:
        return json.load(response)

try:
    remote = request("git/ref/tags/" + TAG)
    assert remote["object"]["sha"] == COMMIT
    releases = request("releases?per_page=100")
    existing = next((r for r in releases if r["tag_name"] == TAG), None)
    if existing and not existing["draft"]:
        raise SystemExit("Release already published; refusing to change it.")
    release = existing or request("releases", "POST", {
        "tag_name": TAG, "target_commitish": COMMIT, "name": "Video Companion v1.1 — Sửa lỗi tự phát queue",
        "body": BODY, "draft": True, "prerelease": False})
    asset = next((a for a in release["assets"] if a["name"] == NAME), None)
    if asset is None:
        upload = release["upload_url"].split("{")[0] + "?name=" + urllib.parse.quote(NAME)
        asset = request(upload, "POST", payload, binary=True)
    assert asset["state"] == "uploaded" and asset["size"] == len(payload)
    assert asset.get("digest") == "sha256:" + digest, "Uploaded asset checksum mismatch"
    release = request(f"releases/{release['id']}", "PATCH", {"body": BODY, "draft": False, "make_latest": "true"})
    verified = request("releases/tags/" + TAG)
    assert not verified["draft"]
    assert any(a["name"] == NAME and a["size"] == len(payload) for a in verified["assets"])
    print(json.dumps({"release": verified["html_url"], "apk": asset["browser_download_url"],
                      "bytes": len(payload), "sha256": digest}, ensure_ascii=True))
except urllib.error.HTTPError as error:
    raise SystemExit(f"GitHub API returned HTTP {error.code}; inspect the release before retrying.") from None

