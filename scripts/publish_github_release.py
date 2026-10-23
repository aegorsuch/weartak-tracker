#!/usr/bin/env python3

import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


GITHUB_REPOSITORY = "aegorsuch/weartak-tracker"
CANONICAL_PROJECT = "core/weartak-core/weartak-tracker"
RELEASE_TAG = "takgov-pipeline"
RELEASE_TITLE = "TAK.gov Forge CI/CD Pipeline Release"
OWNERSHIP_MARKER = "<!-- tracker-ci-assets: "
PRESENTATION_START = "<!-- tracker-ci-presentation:start -->"
PRESENTATION_END = "<!-- tracker-ci-presentation:end -->"
APK_PATTERN = re.compile(r"^WearTAK-Tracker-[0-9][A-Za-z0-9.+_-]*-[0-9a-f]{8}-release\.apk$")
DISPLAY_APK_PATTERN = re.compile(r"^WearTAK-Tracker-[0-9][A-Za-z0-9._-]*-p[0-9]+\.apk$")
BUILD_APK_PATTERN = re.compile(r"^WearTAK-Tracker-[0-9][A-Za-z0-9.+_-]*-release\.apk$")
EXPECTED_SIGNER = "430f146736b3dafc44157ff0b62836d16202244da17209176cb335ef0e63f2ec"


class PublishError(Exception):
    pass


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        redirected = super().redirect_request(
            request, file_pointer, code, message, headers, new_url
        )
        if redirected is not None:
            old_host = urllib.parse.urlsplit(request.full_url).hostname
            new_host = urllib.parse.urlsplit(new_url).hostname
            if old_host != new_host:
                redirected.remove_header("Authorization")
        return redirected


class GitHubApi:
    def __init__(self, token):
        self.token = token
        self.api_url = "https://api.github.com"
        self.opener = urllib.request.build_opener(SafeRedirectHandler())

    def _request(self, method, url, payload=None, data=None, accept=None):
        headers = {
            "Accept": accept or "application/vnd.github+json",
            "Authorization": "Bearer " + self.token,
            "User-Agent": "tracker-gitlab-release-publisher",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif data is not None:
            headers["Content-Type"] = "application/vnd.android.package-archive"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=120) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()
        except urllib.error.URLError as error:
            raise PublishError("GitHub request failed: " + error.reason.__class__.__name__)

    def request_json(self, method, path, payload=None, allow_not_found=False):
        status, body = self._request(method, self.api_url + path, payload=payload)
        if allow_not_found and status == 404:
            return None
        if status < 200 or status >= 300:
            raise PublishError("GitHub API returned HTTP " + str(status))
        try:
            return json.loads(body.decode("utf-8")) if body else {}
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise PublishError("GitHub API returned invalid JSON")

    def list_assets(self, release_id):
        assets = []
        page = 1
        while True:
            batch = self.request_json(
                "GET",
                f"/repos/{GITHUB_REPOSITORY}/releases/{release_id}/assets"
                f"?per_page=100&page={page}",
            )
            if not isinstance(batch, list):
                raise PublishError("GitHub returned an invalid release-asset list")
            assets.extend(batch)
            if len(batch) < 100:
                return assets
            page += 1

    def upload_asset(self, release, name, asset_path):
        upload_url = release["upload_url"].split("{", 1)[0]
        expected = f"https://uploads.github.com/repos/{GITHUB_REPOSITORY}/releases/"
        if not upload_url.startswith(expected):
            raise PublishError("GitHub returned an unexpected asset upload URL")
        url = upload_url + "?" + urllib.parse.urlencode({"name": name})
        status, body = self._request("POST", url, data=asset_path.read_bytes())
        if status < 200 or status >= 300:
            raise PublishError("GitHub asset upload returned HTTP " + str(status))
        try:
            return json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise PublishError("GitHub returned invalid upload metadata")

    def download_asset(self, asset_id):
        status, body = self._request(
            "GET",
            f"{self.api_url}/repos/{GITHUB_REPOSITORY}/releases/assets/{asset_id}",
            accept="application/octet-stream",
        )
        if status < 200 or status >= 300:
            raise PublishError("GitHub asset verification returned HTTP " + str(status))
        return body

    def delete_asset(self, asset_id):
        status, _ = self._request(
            "DELETE", f"{self.api_url}/repos/{GITHUB_REPOSITORY}/releases/assets/{asset_id}"
        )
        if status != 204:
            raise PublishError("GitHub asset deletion returned HTTP " + str(status))

    def label_asset(self, asset_id, label):
        self.request_json(
            "PATCH", f"/repos/{GITHUB_REPOSITORY}/releases/assets/{asset_id}",
            {"label": label},
        )


def latest_main_sha(repository_url):
    result = subprocess.run(
        ["git", "ls-remote", repository_url, "refs/heads/main"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise PublishError("Could not verify the canonical main branch head")
    fields = result.stdout.strip().split()
    if len(fields) != 2 or fields[1] != "refs/heads/main":
        raise PublishError("Could not read the canonical main branch head")
    return fields[0].lower()


def android_build_tool(name):
    sdk_root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "/opt/android-sdk-linux"
    build_tools = Path(sdk_root) / "build-tools"
    tool = build_tools / "35.0.0" / name
    if not tool.is_file():
        candidates = [
            path / name
            for path in build_tools.iterdir()
            if path.is_dir() and (path / name).is_file()
        ] if build_tools.is_dir() else []
        if not candidates:
            raise PublishError(f"Android {name} is unavailable for APK inspection")
        tool = max(
            candidates,
            key=lambda path: tuple(int(part) for part in re.findall(r"\d+", path.parent.name)),
        )
    return tool


def verify_signed_apk(apk_path):
    apksigner = android_build_tool("apksigner")
    result = subprocess.run(
        [str(apksigner), "verify", "--print-certs", str(apk_path)],
        check=False,
        capture_output=True,
        text=True,
    )
    signers = re.findall(
        r"(?m)^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$",
        result.stdout,
    )
    if result.returncode != 0 or [value.lower() for value in signers] != [EXPECTED_SIGNER]:
        raise PublishError("The release APK failed Tracker signing certificate verification")


def read_apk_version(apk_path):
    result = subprocess.run(
        [str(android_build_tool("aapt")), "dump", "badging", str(apk_path)],
        check=False, capture_output=True, text=True,
    )
    match = re.search(
        r"(?m)^package: name='com\.tak\.weartak_tracker' versionCode='([0-9]+)' "
        r"versionName='([0-9][A-Za-z0-9.+_-]*)'(?: |$)",
        result.stdout,
    )
    if result.returncode != 0 or match is None or int(match[1]) <= 0:
        raise PublishError("Could not read the Tracker APK version and versionCode")
    return match[2], int(match[1])


def release_presentation(env, version, version_code, digest, asset_name):
    pipeline_id = env.get("CI_PIPELINE_ID", "")
    pipeline_iid = env.get("CI_PIPELINE_IID", "")
    if not re.fullmatch(r"[1-9][0-9]*", pipeline_id):
        raise PublishError("The pipeline ID is missing or invalid")
    if pipeline_iid and not re.fullmatch(r"[1-9][0-9]*", pipeline_iid):
        raise PublishError("The project pipeline IID is invalid")
    version_iid = re.search(r"-p([0-9]+)(?:\+|$)", version)
    if pipeline_iid and version_iid and version_iid[1] != pipeline_iid:
        raise PublishError("The APK version does not match the project pipeline IID")
    title = f"{RELEASE_TITLE} ({version.split('+', 1)[0]})"
    pipeline_url = f"https://git.tak.gov/{CANONICAL_PROJECT}/-/pipelines/{pipeline_id}"
    build = f"GitLab pipeline **{pipeline_id}**"
    if pipeline_iid:
        build += f" (project pipeline **{pipeline_iid}**)"
    label = asset_name
    body = (
        "Automatically published stable WearOS Tracker main-channel build.\n\n"
        f"- APK version: **{version}** (versionCode **{version_code}**)\n"
        f"- Build: {build}\n"
        f"- Pipeline: {pipeline_url}\n"
        f"- Source commit: `{env['CI_COMMIT_SHA'].lower()}` on TAK.gov `main`\n"
        f"- SHA256: `{digest}`\n\n"
        "Install the APK above. GitHub's \"Source code\" archives come from the fixed "
        "release anchor tag, not from this build; the source commit above is "
        "authoritative and may not yet be mirrored to GitHub."
    )
    return title, body, label


def release_asset_name(version, pipeline_iid):
    display_version = version.split("+", 1)[0]
    if not re.fullmatch(r"[1-9][0-9]*", pipeline_iid or ""):
        raise PublishError("A valid project pipeline IID is required for the APK filename")
    suffix = re.search(r"-p([0-9]+)$", display_version)
    if suffix and suffix[1] != pipeline_iid:
        raise PublishError("The APK version does not match the project pipeline IID")
    if suffix is None:
        display_version += "-p" + pipeline_iid
    name = f"WearTAK-Tracker-{display_version}.apk"
    if not DISPLAY_APK_PATTERN.fullmatch(name):
        raise PublishError("The APK version cannot form a safe release filename")
    return name


def update_presentation(body, presentation):
    body = body or ""
    starts, ends = body.count(PRESENTATION_START), body.count(PRESENTATION_END)
    if starts != ends or starts > 1:
        raise PublishError("The release presentation markers are invalid")
    block = PRESENTATION_START + "\n" + presentation + "\n" + PRESENTATION_END
    if starts:
        start = body.index(PRESENTATION_START)
        end = body.index(PRESENTATION_END)
        if end < start:
            raise PublishError("The release presentation markers are invalid")
        return body[:start] + block + body[end + len(PRESENTATION_END):]
    return block + ("\n\n" + body if body else "")


def read_owned_names(body):
    matches = re.findall(r"(?m)^<!-- tracker-ci-assets: (.+) -->$", body or "")
    if len(matches) != 1:
        raise PublishError("The rolling release is not marked as publisher-owned")
    try:
        names = json.loads(matches[0])
    except json.JSONDecodeError:
        raise PublishError("The rolling release ownership marker is invalid")
    if not isinstance(names, list) or any(
        not isinstance(name, str)
        or not (APK_PATTERN.fullmatch(name) or DISPLAY_APK_PATTERN.fullmatch(name))
        for name in names
    ):
        raise PublishError("The rolling release ownership marker is invalid")
    return list(dict.fromkeys(names))


def update_marker(body, names):
    marker = OWNERSHIP_MARKER + json.dumps(names, separators=(",", ":")) + " -->"
    pattern = re.compile(r"(?m)^<!-- tracker-ci-assets: .+ -->$")
    if pattern.search(body or ""):
        return pattern.sub(lambda _: marker, body, count=1)
    if body:
        return body.rstrip() + "\n\n" + marker
    return marker


class ReleasePublisher:
    def __init__(
        self, env, api=None, head_reader=latest_main_sha, apk_verifier=verify_signed_apk,
        version_reader=read_apk_version,
    ):
        self.env = env
        self.api = api
        self.head_reader = head_reader
        self.apk_verifier = apk_verifier
        self.version_reader = version_reader

    def assert_current_head(self, commit_sha):
        if self.head_reader(self.env["CI_REPOSITORY_URL"]) != commit_sha:
            raise PublishError("This pipeline is no longer the canonical main head")
        mirror = self.api.request_json("GET", f"/repos/{GITHUB_REPOSITORY}/commits/main")
        if mirror.get("sha", "").lower() != commit_sha:
            raise PublishError("The GitHub main mirror does not match this pipeline")

    def run(self):
        required = (
            "CI_SERVER_HOST", "CI_PROJECT_PATH", "CI_COMMIT_BRANCH", "CI_PIPELINE_SOURCE",
            "CI_COMMIT_REF_PROTECTED", "CI_COMMIT_SHA", "CI_REPOSITORY_URL",
            "GITHUB_RELEASE_TOKEN",
        )
        missing = [name for name in required if not self.env.get(name)]
        if missing:
            raise PublishError("Required CI configuration is missing: " + ", ".join(missing))
        if self.env.get("CI_DEBUG_TRACE", "false").lower() in ("true", "1"):
            raise PublishError("Disable CI_DEBUG_TRACE before publishing")
        if (
            self.env["CI_SERVER_HOST"] != "git.tak.gov"
            or self.env["CI_PROJECT_PATH"] != CANONICAL_PROJECT
            or self.env["CI_COMMIT_BRANCH"] != "main"
            or self.env["CI_PIPELINE_SOURCE"] != "push"
            or self.env["CI_COMMIT_REF_PROTECTED"] != "true"
        ):
            raise PublishError("Only protected canonical main push pipelines may publish")
        commit_sha = self.env["CI_COMMIT_SHA"].lower()
        if not re.fullmatch(r"[0-9a-f]{40}", commit_sha):
            raise PublishError("The pipeline commit SHA is invalid")
        if self.api is None:
            self.api = GitHubApi(self.env["GITHUB_RELEASE_TOKEN"])
        self.assert_current_head(commit_sha)
        anchor = self.api.request_json(
            "GET", f"/repos/{GITHUB_REPOSITORY}/git/ref/tags/{RELEASE_TAG}",
            allow_not_found=True,
        )
        if anchor is None:
            raise PublishError("The approved fixed release anchor tag is missing")

        apk_candidates = list(Path("app/build/outputs/apk/release").glob("*.apk"))
        if len(apk_candidates) != 1:
            raise PublishError("Expected exactly one Tracker release APK")
        apk_path = apk_candidates[0]
        if (
            not apk_path.is_file() or apk_path.is_symlink()
            or not BUILD_APK_PATTERN.fullmatch(apk_path.name) or not apk_path.stat().st_size
        ):
            raise PublishError("The pipeline APK name or content is invalid")
        self.apk_verifier(apk_path)
        version, version_code = self.version_reader(apk_path)
        asset_name = release_asset_name(version, self.env.get("CI_PIPELINE_IID"))
        local_digest = hashlib.sha256(apk_path.read_bytes()).hexdigest()
        title, presentation, label = release_presentation(
            self.env, version, version_code, local_digest, asset_name,
        )

        release = self.api.request_json(
            "GET", f"/repos/{GITHUB_REPOSITORY}/releases/tags/{RELEASE_TAG}",
            allow_not_found=True,
        )
        if release is None:
            release = self.api.request_json(
                "POST", f"/repos/{GITHUB_REPOSITORY}/releases",
                {
                    "tag_name": RELEASE_TAG, "name": title,
                    "body": update_marker("", []), "draft": True,
                    "prerelease": False, "target_commitish": commit_sha,
                },
            )
        owned_names = read_owned_names(release.get("body", ""))
        update_presentation(release.get("body", ""), presentation)
        assets = self.api.list_assets(release["id"])
        existing = next((asset for asset in assets if asset["name"] == asset_name), None)
        if existing is not None and asset_name not in owned_names:
            raise PublishError("A non-publisher release asset uses the APK filename")
        planned_names = list(dict.fromkeys(owned_names + [asset_name]))
        release = self.api.request_json(
            "PATCH", f"/repos/{GITHUB_REPOSITORY}/releases/{release['id']}",
            {
                "body": update_marker(release.get("body", ""), planned_names),
            },
        )
        if existing is not None:
            remote_digest = hashlib.sha256(self.api.download_asset(existing["id"])).hexdigest()
            if remote_digest != local_digest:
                raise PublishError("An asset for this commit already exists with different bytes")
        else:
            new_asset = self.api.upload_asset(release, asset_name, apk_path)
            if new_asset.get("name") != asset_name or "id" not in new_asset:
                raise PublishError("GitHub returned invalid release-asset metadata")
            remote_digest = hashlib.sha256(self.api.download_asset(new_asset["id"])).hexdigest()
            if remote_digest != local_digest:
                self.api.delete_asset(new_asset["id"])
                raise PublishError("The uploaded APK did not match the pipeline artifact")
            existing = new_asset

        self.assert_current_head(commit_sha)
        self.api.label_asset(existing["id"], label)
        for asset in assets:
            if asset["name"] in owned_names and asset["name"] != asset_name:
                self.api.delete_asset(asset["id"])
        self.api.request_json(
            "PATCH", f"/repos/{GITHUB_REPOSITORY}/releases/{release['id']}",
            {
                "name": title,
                "body": update_presentation(
                    update_marker(
                        release.get("body", ""), [asset_name],
                    ), presentation,
                ),
                "draft": False, "prerelease": False, "target_commitish": commit_sha,
            },
        )
        return asset_name

def main():
    try:
        asset_name = ReleasePublisher(os.environ).run()
    except PublishError as error:
        print("ERROR: " + str(error), file=sys.stderr)
        return 1
    print("Published verified signed APK: " + asset_name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
