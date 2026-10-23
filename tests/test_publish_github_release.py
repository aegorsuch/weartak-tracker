import contextlib
import hashlib
import io
import json
import os
import subprocess
import tempfile
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import Mock, patch

from scripts.publish_github_release import (
    CANONICAL_PROJECT,
    EXPECTED_SIGNER,
    GITHUB_REPOSITORY,
    GitHubApi,
    PublishError,
    RELEASE_TAG,
    RELEASE_TITLE,
    PRESENTATION_START,
    PRESENTATION_END,
    ReleasePublisher,
    SafeRedirectHandler,
    latest_main_sha,
    main,
    read_owned_names,
    read_apk_version,
    release_asset_name,
    update_marker,
    update_presentation,
    verify_signed_apk,
)


COMMIT_SHA = "58fcc745aff1d30493493fdc852d00e64f111419"
BUILD_APK = "WearTAK-Tracker-5.8.3-release.apk"
APK_NAME = "WearTAK-Tracker-5.8.4-p7.apk"
OLD_APK = "WearTAK-Tracker-5.8.4-p6.apk"
ROOT = Path(__file__).resolve().parents[1]


class FakeGitHubApi:
    def __init__(self, branch_sha=COMMIT_SHA, body=None, assets=None, corrupt_upload=False):
        self.branch_sha = branch_sha
        self.release = None
        self.assets = {}
        self.next_asset_id = 1
        self.corrupt_upload = corrupt_upload
        self.writes = []
        self.events = []
        self.labels = {}
        self.tag_ref = None
        if body is not None:
            self.release = {
                "id": 17,
                "upload_url": f"https://uploads.github.com/repos/{GITHUB_REPOSITORY}/releases/17/assets{{?name,label}}",
                "name": "old title",
                "body": body,
                "draft": False,
                "prerelease": True,
            }
        for name, content in (assets or {}).items():
            self.add_asset(name, content)

    def add_asset(self, name, content):
        asset = {"id": self.next_asset_id, "name": name}
        self.next_asset_id += 1
        self.assets[asset["id"]] = (asset, content)
        return asset

    def list_assets(self, release_id):
        return [asset for asset, _ in self.assets.values()]

    def request_json(self, method, path, payload=None, allow_not_found=False):
        prefix = f"/repos/{GITHUB_REPOSITORY}"
        if not path.startswith(prefix):
            raise AssertionError("Unexpected repository: " + path)
        route = path[len(prefix):]
        if route == "/commits/main" and method == "GET":
            return {"sha": self.branch_sha}
        if route == "/releases/tags/" + RELEASE_TAG and method == "GET":
            return self.release
        if route == "/releases" and method == "POST":
            self.writes.append(("create-release", payload))
            self.release = dict(
                payload, id=17,
                upload_url=f"https://uploads.github.com/repos/{GITHUB_REPOSITORY}/releases/17/assets{{?name,label}}",
            )
            return self.release
        if route == "/releases/17" and method == "PATCH":
            self.writes.append(("update-release", payload))
            self.release.update(payload)
            return self.release
        if route == "/git/ref/tags/" + RELEASE_TAG and method == "GET":
            return self.tag_ref
        if route == "/git/refs/tags/" + RELEASE_TAG and method == "PATCH":
            self.writes.append(("move-tag", payload))
            self.tag_ref = {"object": {"sha": payload["sha"]}}
            return self.tag_ref
        if route == "/git/refs" and method == "POST":
            self.writes.append(("create-tag", payload))
            self.tag_ref = {"object": {"sha": payload["sha"]}}
            return self.tag_ref
        raise AssertionError(f"Unexpected API request: {method} {path}")

    def upload_asset(self, release, name, asset_path):
        self.writes.append(("upload", name))
        self.events.append("upload")
        content = asset_path.read_bytes()
        if self.corrupt_upload:
            content += b"corrupt"
        return self.add_asset(name, content)

    def download_asset(self, asset_id):
        self.writes.append(("verify", asset_id))
        return self.assets[asset_id][1]

    def delete_asset(self, asset_id):
        self.writes.append(("delete-asset", asset_id))
        del self.assets[asset_id]

    def label_asset(self, asset_id, label):
        self.writes.append(("label", asset_id))
        self.labels[asset_id] = label


class OfflineTests(unittest.TestCase):
    def setUp(self):
        guard = patch("socket.socket", side_effect=AssertionError("Network forbidden in tests"))
        guard.start()
        self.addCleanup(guard.stop)


class ReleasePublisherTests(OfflineTests):
    def setUp(self):
        super().setUp()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        previous_cwd = Path.cwd()
        os.chdir(self.temp_dir.name)
        self.addCleanup(os.chdir, previous_cwd)
        self.apk_dir = Path("app/build/outputs/apk/release")
        self.apk_dir.mkdir(parents=True)
        self.apk_path = self.apk_dir / BUILD_APK
        self.apk_bytes = b"signed apk test content"
        self.apk_path.write_bytes(self.apk_bytes)
        self.env = {
            "CI_SERVER_HOST": "git.tak.gov",
            "CI_PROJECT_PATH": CANONICAL_PROJECT,
            "CI_COMMIT_BRANCH": "main",
            "CI_COMMIT_REF_PROTECTED": "true",
            "CI_PIPELINE_SOURCE": "push",
            "CI_PIPELINE_ID": "1792575",
            "CI_PIPELINE_IID": "7",
            "CI_COMMIT_SHA": COMMIT_SHA,
            "CI_REPOSITORY_URL": f"https://git.tak.gov/{CANONICAL_PROJECT}.git",
            "GITHUB_RELEASE_TOKEN": "not-a-real-token",
        }

    def publisher(self, api):
        return ReleasePublisher(
            self.env, api=api, head_reader=lambda _: COMMIT_SHA,
            apk_verifier=lambda _: api.events.append("signature"),
            version_reader=lambda _: ("5.8.4-p7+58fcc745", 5860007),
        )

    def test_creates_stable_release_with_exact_title_and_unchanged_signed_bytes(self):
        api = FakeGitHubApi()
        self.assertEqual(self.publisher(api).run(), APK_NAME)
        self.assertEqual(RELEASE_TITLE, "TAK.gov Forge CI/CD Pipeline Release")
        self.assertEqual(api.release["name"], RELEASE_TITLE + " (5.8.4-p7)")
        self.assertEqual(api.release["tag_name"], "takgov-pipeline")
        self.assertFalse(api.release["draft"])
        self.assertFalse(api.release["prerelease"])
        self.assertEqual(api.tag_ref["object"]["sha"], COMMIT_SHA)
        self.assertEqual(list(api.assets.values()), [
            ({"id": 1, "name": APK_NAME}, self.apk_bytes),
        ])
        self.assertLess(api.events.index("signature"), api.events.index("upload"))
        self.assertTrue(api.writes[0][1]["draft"])
        self.assertEqual(api.labels[1], APK_NAME)
        self.assertIn("versionCode **5860007**", api.release["body"])
        self.assertIn("GitLab pipeline **1792575** (project pipeline **7**)", api.release["body"])
        self.assertIn(f"/{CANONICAL_PROJECT}/-/pipelines/1792575", api.release["body"])
        self.assertIn(f"`{COMMIT_SHA}` on TAK.gov `main`", api.release["body"])
        self.assertIn(f"/releases/download/{RELEASE_TAG}/{APK_NAME}", api.release["body"])
        self.assertIn("- SHA256: `", api.release["body"])
        self.assertIn(hashlib.sha256(self.apk_bytes).hexdigest(), api.release["body"])

    def test_replaces_only_owned_apks_after_verification_and_preserves_notes(self):
        api = FakeGitHubApi(
            body=update_marker("Existing release notes", [OLD_APK]),
            assets={OLD_APK: b"old", "notes.txt": b"keep"},
        )
        api.tag_ref = {"object": {"sha": "0" * 40}}
        self.publisher(api).run()
        self.assertEqual({a["name"] for a, _ in api.assets.values()}, {APK_NAME, "notes.txt"})
        self.assertIn("Existing release notes\n\n", api.release["body"])
        self.assertEqual(read_owned_names(api.release["body"]), [APK_NAME])
        verification = next(i for i, entry in enumerate(api.writes) if entry[0] == "verify")
        deletion = next(i for i, entry in enumerate(api.writes) if entry[0] == "delete-asset")
        self.assertLess(verification, deletion)
        self.assertIn(("move-tag", {"sha": COMMIT_SHA, "force": True}), api.writes)
        self.assertEqual(api.release["name"], RELEASE_TITLE + " (5.8.4-p7)")

    def test_rerun_replaces_only_generated_presentation_and_preserves_manual_notes(self):
        manual = "Manual notes before\n\n"
        body = manual + update_presentation(
            update_marker("\nManual notes after\n", [APK_NAME]), "Old presentation",
        )
        api = FakeGitHubApi(body=body, assets={APK_NAME: self.apk_bytes})
        self.publisher(api).run()
        self.publisher(api).run()
        body = api.release["body"]
        self.assertTrue(body.startswith(manual))
        self.assertIn("\nManual notes after\n", body)
        self.assertNotIn("Old presentation", body)
        self.assertEqual(body.count(PRESENTATION_START), 1)
        self.assertEqual(body.count(PRESENTATION_END), 1)
        self.assertEqual(read_owned_names(body), [APK_NAME])

    def test_malformed_presentation_rejected_before_writes(self):
        api = FakeGitHubApi(body=update_marker(PRESENTATION_START, [APK_NAME]))
        with self.assertRaisesRegex(PublishError, "presentation markers"):
            self.publisher(api).run()
        self.assertFalse(api.writes)

    def test_missing_pipeline_id_or_mismatched_iid_rejected_before_writes(self):
        for key, value in (("CI_PIPELINE_ID", ""), ("CI_PIPELINE_ID", "invalid"),
                           ("CI_PIPELINE_IID", "8"), ("CI_PIPELINE_IID", "invalid")):
            with self.subTest(key=key, value=value):
                api = FakeGitHubApi()
                publisher = self.publisher(api)
                publisher.env = dict(self.env, **{key: value})
                with self.assertRaises(PublishError):
                    publisher.run()
                self.assertFalse(api.writes)

    def test_title_uses_apk_version_not_filename_or_pipeline_iid(self):
        api = FakeGitHubApi()
        publisher = self.publisher(api)
        publisher.version_reader = lambda _: ("5.8.3", 585)
        publisher.run()
        self.assertEqual(api.release["name"], RELEASE_TITLE + " (5.8.3)")
        self.assertIn("versionCode **585**", api.release["body"])
        self.assertIn("- APK version: **5.8.3**", api.release["body"])
        self.assertNotIn("- APK version: **5.8.3-p7", api.release["body"])
        self.assertIn("WearTAK-Tracker-5.8.3-p7.apk", api.release["body"])

    def test_optional_iid_is_omitted_when_unavailable(self):
        api = FakeGitHubApi()
        del self.env["CI_PIPELINE_IID"]
        with self.assertRaisesRegex(PublishError, "pipeline IID is required"):
            self.publisher(api).run()
        self.assertFalse(api.writes)

    def test_legacy_asset_urls_and_ownership_survive_naming_migration(self):
        legacy = "WearTAK-Tracker-5.8.4-5860007-6f8b8e4f-release.apk"
        api = FakeGitHubApi(
            body=update_marker("Manual notes", [legacy, OLD_APK]),
            assets={legacy: b"legacy bytes", OLD_APK: b"older new-style bytes"},
        )
        self.publisher(api).run()
        names = {a["name"] for a, _ in api.assets.values()}
        self.assertEqual(names, {legacy, APK_NAME})
        self.assertEqual(read_owned_names(api.release["body"]), [legacy, APK_NAME])
        self.publisher(api).run()
        self.assertEqual({a["name"] for a, _ in api.assets.values()}, names)
        self.assertIn("Manual notes", api.release["body"])

    def test_unreadable_manifest_rejected_before_writes(self):
        api = FakeGitHubApi()
        publisher = self.publisher(api)
        publisher.version_reader = Mock(side_effect=PublishError("invalid APK metadata"))
        with self.assertRaisesRegex(PublishError, "invalid APK metadata"):
            publisher.run()
        self.assertFalse(api.writes)

    def test_corrupt_upload_keeps_old_assets_and_release_state(self):
        api = FakeGitHubApi(
            body=update_marker("", [OLD_APK]), assets={OLD_APK: b"old"},
            corrupt_upload=True,
        )
        with self.assertRaisesRegex(PublishError, "did not match"):
            self.publisher(api).run()
        self.assertEqual({a["name"] for a, _ in api.assets.values()}, {OLD_APK})
        self.assertEqual(api.release["name"], "old title")
        self.assertTrue(api.release["prerelease"])
        self.assertIsNone(api.tag_ref)
        self.assertFalse(api.labels)

    def test_rejects_unowned_release_without_writes(self):
        api = FakeGitHubApi(body="Release created by someone else")
        with self.assertRaisesRegex(PublishError, "not marked as publisher-owned"):
            self.publisher(api).run()
        self.assertFalse(api.writes)

    def test_rejects_unowned_filename_collision(self):
        api = FakeGitHubApi(body=update_marker("", []), assets={APK_NAME: self.apk_bytes})
        with self.assertRaisesRegex(PublishError, "non-publisher"):
            self.publisher(api).run()
        self.assertFalse(api.writes)

    def test_rerun_reuses_identical_asset(self):
        api = FakeGitHubApi()
        publisher = self.publisher(api)
        publisher.run()
        api.writes.clear()
        publisher.run()
        self.assertFalse(any(entry[0] in ("upload", "delete-asset", "move-tag") for entry in api.writes))
        self.assertEqual(len(api.assets), 1)

    def test_rerun_rejects_different_bytes_for_same_commit(self):
        api = FakeGitHubApi(body=update_marker("", [APK_NAME]), assets={APK_NAME: b"different"})
        with self.assertRaisesRegex(PublishError, "different bytes"):
            self.publisher(api).run()
        self.assertFalse(any(entry[0] in ("delete-asset", "upload", "move-tag") for entry in api.writes))

    def test_stale_github_mirror_rejected_before_writes(self):
        api = FakeGitHubApi(branch_sha="0" * 40)
        with self.assertRaisesRegex(PublishError, "mirror does not match"):
            self.publisher(api).run()
        self.assertFalse(api.writes)

    def test_stale_canonical_head_rejected_before_writes(self):
        api = FakeGitHubApi()
        publisher = self.publisher(api)
        publisher.head_reader = lambda _: "0" * 40
        with self.assertRaisesRegex(PublishError, "no longer the canonical"):
            publisher.run()
        self.assertFalse(api.writes)

    def test_head_advance_during_upload_keeps_old_assets_and_tag(self):
        api = FakeGitHubApi(body=update_marker("", [OLD_APK]), assets={OLD_APK: b"old"})
        publisher = self.publisher(api)
        heads = iter((COMMIT_SHA, "0" * 40))
        publisher.head_reader = lambda _: next(heads)
        with self.assertRaisesRegex(PublishError, "no longer the canonical"):
            publisher.run()
        self.assertTrue(any(a["name"] == OLD_APK for a, _ in api.assets.values()))
        self.assertIsNone(api.tag_ref)

    def test_unsigned_apk_rejected_before_writes(self):
        api = FakeGitHubApi()
        publisher = self.publisher(api)
        publisher.apk_verifier = Mock(side_effect=PublishError("invalid signature"))
        with self.assertRaisesRegex(PublishError, "invalid signature"):
            publisher.run()
        self.assertFalse(api.writes)

    def test_missing_token_has_clear_error_before_client_creation(self):
        del self.env["GITHUB_RELEASE_TOKEN"]
        with patch("scripts.publish_github_release.GitHubApi") as client:
            with self.assertRaisesRegex(PublishError, "missing: GITHUB_RELEASE_TOKEN"):
                ReleasePublisher(self.env).run()
            client.assert_not_called()

    def test_rejects_noncanonical_or_unprotected_pipelines(self):
        for key, value in (
            ("CI_SERVER_HOST", "example.com"), ("CI_PROJECT_PATH", GITHUB_REPOSITORY),
            ("CI_COMMIT_BRANCH", "feature"), ("CI_PIPELINE_SOURCE", "web"),
            ("CI_PIPELINE_SOURCE", "merge_request_event"), ("CI_COMMIT_REF_PROTECTED", "false"),
        ):
            with self.subTest(key=key, value=value):
                env = dict(self.env, **{key: value})
                api = FakeGitHubApi()
                with self.assertRaisesRegex(PublishError, "Only protected canonical"):
                    ReleasePublisher(env, api=api).run()
                self.assertFalse(api.writes)

    def test_debug_trace_rejected(self):
        self.env["CI_DEBUG_TRACE"] = "TRUE"
        with self.assertRaisesRegex(PublishError, "Disable CI_DEBUG_TRACE"):
            self.publisher(FakeGitHubApi()).run()

    def test_invalid_commit_rejected(self):
        self.env["CI_COMMIT_SHA"] = "not-a-sha"
        with self.assertRaisesRegex(PublishError, "commit SHA is invalid"):
            self.publisher(FakeGitHubApi()).run()

    def test_missing_empty_multiple_or_misnamed_apks_rejected(self):
        self.apk_path.unlink()
        for mode in ("missing", "empty", "multiple", "misnamed"):
            with self.subTest(mode=mode):
                for path in self.apk_dir.glob("*"):
                    path.unlink()
                if mode == "empty":
                    self.apk_path.touch()
                elif mode == "multiple":
                    self.apk_path.write_bytes(self.apk_bytes)
                    (self.apk_dir / "other.apk").write_bytes(self.apk_bytes)
                elif mode == "misnamed":
                    (self.apk_dir / "debug.apk").write_bytes(self.apk_bytes)
                api = FakeGitHubApi()
                with self.assertRaises(PublishError):
                    self.publisher(api).run()
                self.assertFalse(api.writes)


class PublisherHelperTests(OfflineTests):
    def test_watch_style_filename_uses_version_once_and_excludes_sha(self):
        self.assertEqual(release_asset_name("5.8.4-p7+6f8b8e4f", "7"), APK_NAME)
        self.assertEqual(release_asset_name("5.8.3", "2"), "WearTAK-Tracker-5.8.3-p2.apk")
        for version, iid in (("5.8.4-p7+sha", "8"), ("../bad", "7"), ("5.8.4", "")):
            with self.subTest(version=version, iid=iid), self.assertRaises(PublishError):
                release_asset_name(version, iid)
    def test_reads_actual_manifest_version(self):
        output = (
            "package: name='com.tak.weartak_tracker' versionCode='5860007' "
            "versionName='5.8.4-p7+6f8b8e4f' platformBuildVersionName='16'\n"
        )
        result = subprocess.CompletedProcess([], 0, stdout=output, stderr="")
        with patch("scripts.publish_github_release.android_build_tool", return_value=Path("aapt")), patch(
            "scripts.publish_github_release.subprocess.run", return_value=result,
        ) as run:
            self.assertEqual(read_apk_version(Path("build.apk")), ("5.8.4-p7+6f8b8e4f", 5860007))
            self.assertEqual(run.call_args.args[0], ["aapt", "dump", "badging", "build.apk"])

    def test_manifest_missing_wrong_package_or_failed_inspection_is_rejected(self):
        for code, output in (
            (0, ""), (1, "failure"),
            (0, "package: name='other.app' versionCode='1' versionName='5.8.4'\n"),
            (0, "package: name='com.tak.weartak_tracker' versionCode='0' versionName='5.8.4'\n"),
        ):
            with self.subTest(code=code, output=output), patch(
                "scripts.publish_github_release.android_build_tool", return_value=Path("aapt"),
            ), patch("scripts.publish_github_release.subprocess.run", return_value=subprocess.CompletedProcess(
                [], code, stdout=output, stderr="",
            )), self.assertRaisesRegex(PublishError, "APK version"):
                read_apk_version(Path("build.apk"))

    def test_reversed_or_duplicate_presentation_markers_rejected(self):
        for body in (
            PRESENTATION_END + PRESENTATION_START,
            PRESENTATION_START + PRESENTATION_END + PRESENTATION_START + PRESENTATION_END,
        ):
            with self.subTest(body=body), self.assertRaisesRegex(PublishError, "presentation markers"):
                update_presentation(body, "new")

    def test_rejects_invalid_ownership_markers(self):
        for body in ("", "<!-- tracker-ci-assets: invalid -->", update_marker("", ["notes.txt"]),
                     update_marker("", []) + "\n" + update_marker("", [])):
            with self.subTest(body=body), self.assertRaises(PublishError):
                read_owned_names(body)

    def test_checks_exact_tracker_certificate(self):
        for code, signer in ((0, EXPECTED_SIGNER), (0, "a" * 64), (1, EXPECTED_SIGNER)):
            result = subprocess.CompletedProcess(
                [], code, stdout=f"Signer #1 certificate SHA-256 digest: {signer}\n", stderr="",
            )
            with self.subTest(code=code, signer=signer), patch(
                "scripts.publish_github_release.Path.is_file", return_value=True
            ), patch("scripts.publish_github_release.subprocess.run", return_value=result):
                if code == 0 and signer == EXPECTED_SIGNER:
                    verify_signed_apk(Path("test.apk"))
                else:
                    with self.assertRaisesRegex(PublishError, "signing certificate"):
                        verify_signed_apk(Path("test.apk"))

    def test_missing_apksigner_fails_clearly(self):
        with patch("scripts.publish_github_release.Path.is_file", return_value=False):
            with self.assertRaisesRegex(PublishError, "apksigner is unavailable"):
                verify_signed_apk(Path("test.apk"))

    def test_canonical_head_uses_read_only_ls_remote_and_sanitizes_failure(self):
        result = subprocess.CompletedProcess(
            [], 0, stdout=COMMIT_SHA + "\trefs/heads/main\n", stderr="",
        )
        with patch("scripts.publish_github_release.subprocess.run", return_value=result) as run:
            self.assertEqual(latest_main_sha("https://example.invalid/repo.git"), COMMIT_SHA)
            self.assertEqual(run.call_args.args[0], [
                "git", "ls-remote", "https://example.invalid/repo.git", "refs/heads/main",
            ])
        result.returncode = 1
        result.stderr = "secret"
        with patch("scripts.publish_github_release.subprocess.run", return_value=result):
            with self.assertRaisesRegex(PublishError, "Could not verify"):
                latest_main_sha("https://example.invalid/repo.git")

    def test_main_missing_configuration_does_not_expose_secrets(self):
        stderr = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), contextlib.redirect_stderr(stderr):
            self.assertEqual(main(), 1)
        self.assertIn("GITHUB_RELEASE_TOKEN", stderr.getvalue())

    def test_ci_consumes_only_successful_develop_artifacts_with_serialization(self):
        text = (ROOT / ".gitlab-ci.yml").read_text()
        job = text.split("\npublish_github_pipeline_release:\n", 1)[1]
        self.assertIn("stage: .post", job)
        self.assertIn("- job: main_release\n      artifacts: true", job)
        self.assertIn("resource_group: tracker-github-rolling-release", job)
        self.assertIn('CI_PIPELINE_SOURCE == "push"', job)
        self.assertIn('CI_COMMIT_REF_PROTECTED == "true"', job)
        self.assertNotIn("when: always", job)
        self.assertNotIn("allow_failure", job)


class GitHubApiTests(OfflineTests):
    def test_http_errors_are_sanitized(self):
        api = GitHubApi("secret-token")
        api.opener = Mock()
        api.opener.open.side_effect = urllib.error.HTTPError(
            "https://api.github.com", 403, "secret-token", {}, io.BytesIO(b"secret-token"),
        )
        with self.assertRaisesRegex(PublishError, "^GitHub API returned HTTP 403$"):
            api.request_json("POST", "/repos/test/releases", {})

    def test_redirect_strips_authorization_when_host_changes(self):
        request = urllib.request.Request(
            "https://api.github.com/assets/1", headers={"Authorization": "Bearer secret"},
        )
        redirected = SafeRedirectHandler().redirect_request(
            request, None, 302, "Found", {}, "https://release-assets.githubusercontent.com/test",
        )
        self.assertIsNone(redirected.get_header("Authorization"))

    def test_unexpected_upload_host_rejected(self):
        api = GitHubApi("secret-token")
        with self.assertRaisesRegex(PublishError, "unexpected asset upload URL"):
            api.upload_asset({"upload_url": "https://example.invalid/assets"}, APK_NAME, Path("unused"))

    def test_asset_pagination(self):
        api = GitHubApi("secret-token")
        first_page = [{"id": value} for value in range(100)]
        with patch.object(api, "request_json", side_effect=[first_page, [{"id": 100}]]) as request:
            self.assertEqual(len(api.list_assets(17)), 101)
        self.assertTrue(request.call_args.args[1].endswith("per_page=100&page=2"))

    def test_readable_asset_label_update_does_not_rename_asset(self):
        api = GitHubApi("secret-token")
        with patch.object(api, "request_json") as request:
            api.label_asset(17, "WearTAK Tracker 5.8.4-p7 - signed APK")
        request.assert_called_once_with(
            "PATCH", f"/repos/{GITHUB_REPOSITORY}/releases/assets/17",
            {"label": "WearTAK Tracker 5.8.4-p7 - signed APK"},
        )

    def test_invalid_json_fails_clearly(self):
        api = GitHubApi("secret-token")
        with patch.object(api, "_request", return_value=(200, b"invalid")):
            with self.assertRaisesRegex(PublishError, "invalid JSON"):
                api.request_json("GET", "/test")


if __name__ == "__main__":
    unittest.main()
