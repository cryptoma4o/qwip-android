from pathlib import Path
import base64
import copy
import importlib.util
import hashlib
import json
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_upstream", ROOT / "tools/check_upstream.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class UpdateCheckTests(unittest.TestCase):
    def setUp(self):
        self.lock = json.loads((ROOT / "Config/Engine.lock.json").read_text())
        self.sha = self.lock["libXray"]["commit"]
        self.api = 3
        self.license_changed = False
        self.tag = self.lock["xrayCore"]["release"]
        self.core_version = self.lock["xrayCore"]["moduleVersion"]
        self.lock["libXray"]["licenseSHA256"] = hashlib.sha256(b"MIT License").hexdigest()
        self.lock["xrayCore"]["licenseSHA256"] = hashlib.sha256(b"Mozilla Public License Version 2.0").hexdigest()
        self.requests = []

    def fetch(self, endpoint):
        self.requests.append(endpoint)
        if endpoint == "repos/XTLS/libXray/commits/main":
            return {"sha": self.sha}
        if endpoint == "repos/XTLS/Xray-core/releases/latest":
            return {"tag_name": self.tag, "draft": False, "prerelease": False}
        if "/contents/go.mod?" in endpoint:
            version = self.lock['xrayCore']['moduleVersion'] if endpoint.endswith(self.lock['libXray']['commit']) else self.core_version
            text = f"module github.com/xtls/libxray\ngo {self.lock['goVersion']}\nrequire github.com/xtls/xray-core {version}\n"
        elif "/contents/invoke_model.go?" in endpoint:
            text = f"const LibXrayAPIVersion = {self.api}\n"
        elif "/contents/LICENSE?" in endpoint:
            text = "MIT License" if "XTLS/libXray" in endpoint else "Mozilla Public License Version 2.0"
            if self.license_changed and endpoint.endswith(self.sha):
                text = "New license requiring review"
        else:
            self.fail("Unexpected metadata request: " + endpoint)
        return {"encoding": "base64", "content": base64.b64encode(text.encode()).decode()}

    def test_current_pair_leaves_pins_unchanged(self):
        baseline = copy.deepcopy(self.lock)
        result = checker.inspect_updates(self.lock, self.fetch)
        self.assertEqual(result["status"], "up-to-date")
        self.assertFalse(result["updatesAvailable"])
        self.assertEqual(self.lock, baseline)

    def test_new_library_is_candidate_and_is_not_installed(self):
        self.sha = "a" * 40
        result = checker.inspect_updates(self.lock, self.fetch)
        self.assertTrue(result["updates"]["libXray"])
        self.assertEqual(result["status"], "candidate-needs-build-license-audit-and-tests")
        self.assertFalse(result["pinsChanged"])
        self.assertFalse(result["codeInstalled"])

    def test_api_or_license_change_blocks_automatic_adoption(self):
        self.sha = "b" * 40
        self.api = 4
        result = checker.inspect_updates(self.lock, self.fetch)
        self.assertEqual(result["status"], "blocked-api-or-license-change")
        self.api = 3
        self.license_changed = True
        result = checker.inspect_updates(self.lock, self.fetch)
        self.assertEqual(result["status"], "blocked-api-or-license-change")

    def test_unexpected_release_metadata_fails_closed(self):
        self.tag = "untrusted\n::command::value"
        with self.assertRaises(checker.CheckError):
            checker.inspect_updates(self.lock, self.fetch)

    def test_dependency_change_is_reported_for_review(self):
        self.sha = "c" * 40
        self.core_version = "v1.260327.1-0.20261025120000-abcdefabcdef"
        result = checker.inspect_updates(self.lock, self.fetch)
        self.assertEqual(result["libraryDependencyChanges"], [{
            "module": "github.com/xtls/xray-core",
            "from": self.lock["xrayCore"]["moduleVersion"], "to": self.core_version
        }])
        self.assertTrue(result["updates"]["compatibleCoreModule"])
        self.assertIn("repos/XTLS/Xray-core/contents/LICENSE?ref=abcdefabcdef", self.requests)

    def test_malformed_base64_is_rejected(self):
        with self.assertRaises(checker.CheckError):
            checker.source_file(lambda _: {"encoding": "base64", "content": "!!!!"}, "x", "y", "z")


if __name__ == "__main__":
    unittest.main()
