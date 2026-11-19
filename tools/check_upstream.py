#!/usr/bin/env python3
"""Read-only upstream version check; never changes pins or installs app code."""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
from typing import Callable
from urllib.parse import quote


class CheckError(Exception):
    pass


def github_json(endpoint: str) -> dict:
    try:
        result = subprocess.run(["gh", "api", endpoint], check=True,
                                capture_output=True, text=True, timeout=30)
        value = json.loads(result.stdout)
    except subprocess.CalledProcessError as error:
        raise CheckError(f"GitHub metadata request failed (exit {error.returncode}): {endpoint}") from error
    except (subprocess.SubprocessError, json.JSONDecodeError) as error:
        raise CheckError(f"GitHub metadata request failed: {endpoint}") from error
    if not isinstance(value, dict):
        raise CheckError("Unexpected GitHub response")
    return value


def source_file(fetch: Callable[[str], dict], repository: str, path: str, ref: str) -> str:
    response = fetch(f"repos/{repository}/contents/{path}?ref={quote(ref, safe='')}")
    if response.get("encoding") != "base64":
        raise CheckError("Unexpected source encoding")
    try:
        encoded = "".join(response["content"].split())
        if len(encoded) > 352 * 1024:
            raise CheckError("Source metadata exceeds size limit")
        decoded = base64.b64decode(encoded, validate=True)
        if len(decoded) > 256 * 1024:
            raise CheckError("Source metadata exceeds size limit")
        return decoded.decode("utf-8")
    except (KeyError, ValueError, UnicodeError) as error:
        raise CheckError("Invalid source metadata") from error


def module_requirements(go_mod: str) -> dict[str, str]:
    """Read require declarations, including a single-line declaration."""
    result = {}
    in_require = False
    for raw_line in go_mod.splitlines():
        line = raw_line.split("//", 1)[0].strip()
        if line == "require (":
            in_require = True
            continue
        if in_require and line == ")":
            in_require = False
            continue
        if not in_require and not line.startswith("require "):
            continue
        if line.startswith("require "):
            line = line[len("require "):]
        parts = line.split()
        if len(parts) == 2:
            result[parts[0]] = parts[1]
    return result


def inspect_updates(lock: dict, fetch: Callable[[str], dict]) -> dict:
    if lock.get("schema") != 1:
        raise CheckError("Unsupported engine lock schema")
    library = lock["libXray"]
    core = lock["xrayCore"]
    if library["repository"] != "XTLS/libXray" or core["repository"] != "XTLS/Xray-core":
        raise CheckError("Unexpected upstream repositories")
    if not all(re.fullmatch(r"[0-9a-f]{40}", item["commit"]) for item in (library, core)):
        raise CheckError("Engine lock must pin full commits")

    raw_fetch = fetch
    cached = {}
    def cached_fetch(endpoint):
        if endpoint not in cached:
            cached[endpoint] = raw_fetch(endpoint)
        return cached[endpoint]
    fetch = cached_fetch
    head = fetch("repos/XTLS/libXray/commits/main")
    sha = head.get("sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise CheckError("Invalid upstream commit")
    go_mod = source_file(fetch, "XTLS/libXray", "go.mod", sha)
    locked_go_mod = source_file(fetch, "XTLS/libXray", "go.mod", library["commit"])
    model = source_file(fetch, "XTLS/libXray", "invoke_model.go", sha)
    license_text = source_file(fetch, "XTLS/libXray", "LICENSE", sha)
    locked_license = source_file(fetch, "XTLS/libXray", "LICENSE", library["commit"])

    go_version = re.search(r"^go\s+(\d+\.\d+(?:\.\d+)?)\s*$", go_mod, re.MULTILINE)
    dependencies = module_requirements(go_mod)
    locked_dependencies = module_requirements(locked_go_mod)
    core_version = dependencies.get("github.com/xtls/xray-core", "")
    api_version = re.search(r"const\s+LibXrayAPIVersion\s*=\s*(\d+)", model)
    if not (go_version and core_version and api_version):
        raise CheckError("Upstream API or dependency declaration changed")
    if not re.fullmatch(r"v[0-9A-Za-z.+-]+", core_version):
        raise CheckError("Unexpected Xray-core module version")

    release = fetch("repos/XTLS/Xray-core/releases/latest")
    tag = release.get("tag_name", "")
    if release.get("draft") or release.get("prerelease") or not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise CheckError("Expected a stable Xray-core release")
    core_license = source_file(fetch, "XTLS/Xray-core", "LICENSE", tag)
    locked_core_license = source_file(fetch, "XTLS/Xray-core", "LICENSE", core["commit"])
    pseudo_commit = re.search(r"(?:-|\.)\d{14}-([0-9a-f]{12,40})$", core_version)
    compatible_core_ref = pseudo_commit.group(1) if pseudo_commit else core_version
    compatible_core_license = source_file(fetch, "XTLS/Xray-core", "LICENSE", compatible_core_ref)
    digest = lambda value: hashlib.sha256(value.encode()).hexdigest()

    updates = {
        "libXray": sha != library["commit"],
        "xrayCoreStableRelease": tag != core["release"],
        "compatibleCoreModule": core_version != core["moduleVersion"],
        "goToolchain": go_version.group(1) != lock["goVersion"],
    }
    checks = {
        "libXrayAPIUnchanged": int(api_version.group(1)) == library["apiVersion"],
        "libXrayLicenseUnchanged": license_text == locked_license,
        "xrayCoreLicenseUnchanged": core_license == locked_core_license,
        "compatibleCoreLicenseUnchanged": compatible_core_license == locked_core_license,
    }
    for key, entry, text in (("libXray", library, locked_license), ("xrayCore", core, locked_core_license)):
        if "licenseSHA256" in entry:
            checks[key + "PinnedLicenseMatchesLock"] = digest(text) == entry["licenseSHA256"]
    dependency_changes = [
        {"module": module, "from": locked_dependencies.get(module), "to": dependencies.get(module)}
        for module in sorted(set(dependencies) | set(locked_dependencies))
        if dependencies.get(module) != locked_dependencies.get(module)
    ]
    has_updates = any(updates.values())
    status = "blocked-api-or-license-change" if not all(checks.values()) else (
        "candidate-needs-build-license-audit-and-tests" if has_updates else "up-to-date")
    return {
        "checkedUTC": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "updatesAvailable": has_updates,
        "updates": updates,
        "checks": checks,
        "locked": {"libXrayCommit": library["commit"], "xrayCoreRelease": core["release"],
                   "xrayCoreModule": core["moduleVersion"], "goVersion": lock["goVersion"]},
        "latest": {"libXrayCommit": sha, "libXrayAPIVersion": int(api_version.group(1)),
                   "goVersion": go_version.group(1), "compatibleCoreModule": core_version,
                   "xrayCoreStableRelease": tag,
                   "libXrayLicenseSHA256": digest(license_text),
                   "xrayCoreLicenseSHA256": digest(core_license)},
        "libraryDependencyChanges": dependency_changes,
        "pinsChanged": False,
        "codeInstalled": False,
        "scope": "Metadata and root-license checks only; transitive license audit and runtime compatibility remain required.",
    }


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=root / "Config/Engine.lock.json")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    try:
        result = inspect_updates(json.loads(args.lock.read_text()), github_json)
    except (CheckError, OSError, KeyError, json.JSONDecodeError) as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        return 1
    rendered = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered)
    if args.summary:
        with args.summary.open("a") as summary:
            summary.write("## QWIP engine check\n\n```json\n" + rendered + "```\n")
    print(rendered, end="")
    return 2 if result["status"] == "blocked-api-or-license-change" else 0


if __name__ == "__main__":
    raise SystemExit(main())
