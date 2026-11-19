#!/usr/bin/env python3
"""Inventory notices for a platform-selected `go list -deps -json` graph.

This records evidence and changes, not a legal clearance for distribution.
The full upstream sources and their per-file notices must remain available.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re


def read_go_stream(path: Path) -> list[dict]:
    decoder = json.JSONDecoder()
    source = path.read_text()
    result = []
    offset = 0
    while offset < len(source):
        while offset < len(source) and source[offset].isspace():
            offset += 1
        if offset == len(source):
            break
        item, offset = decoder.raw_decode(source, offset)
        if item.get("Error") or item.get("DepsErrors"):
            raise ValueError("Go graph contains dependency errors")
        result.append(item)
    return result


def classification(text: str) -> str:
    lowered = text.lower()
    if "gnu lesser general public license" in lowered:
        if "as a special exception" in lowered and "links statically or dynamically" in lowered:
            return "LGPL-3.0-with-linking-exception"
        return "LGPL-review-required"
    if "mozilla public license" in lowered:
        return "MPL-2.0"
    if "gnu general public license" in lowered:
        return "GPL-review-required"
    if "apache license" in lowered:
        return "Apache-2.0"
    if "permission is hereby granted, free of charge" in lowered:
        return "MIT-style"
    if "redistribution and use in source and binary forms" in lowered:
        return "BSD-style"
    if "bsd-style license" in lowered:
        return "BSD copyright notice"
    return "notice-review-required"


def inventory(packages: list[dict]) -> dict:
    modules = {}
    for package in packages:
        if package.get("Standard") or not package.get("Module"):
            continue
        module = package["Module"]
        if module.get("Replace"):
            raise ValueError("Replaced module requires an explicit provenance review")
        entry = modules.setdefault(module["Path"], {
            "module": module["Path"], "version": module.get("Version"),
            "directory": module["Dir"], "packages": [], "noticeFiles": {}
        })
        entry["packages"].append(package["ImportPath"])
        root = Path(module["Dir"]).resolve()
        directory = Path(package["Dir"]).resolve()
        if not directory.is_relative_to(root):
            raise ValueError("Package escaped module directory")
        for parent in (directory, *directory.parents):
            if not parent.is_relative_to(root):
                break
            for path in sorted(parent.iterdir()):
                if not path.is_file() or not re.match(r"^(licen[cs]e|copying|copyright|notice)(\.|-|$)", path.name, re.I):
                    continue
                relative = str(path.relative_to(root))
                data = path.read_bytes()
                text = data.decode("utf-8", errors="replace")
                entry["noticeFiles"][relative] = {
                    "file": relative, "sha256": hashlib.sha256(data).hexdigest(),
                    "classification": classification(text), "text": text
                }
    for entry in modules.values():
        entry["noticeFiles"] = list(entry["noticeFiles"].values())
        if not entry["noticeFiles"]:
            raise ValueError("Missing notices for " + entry["module"])
    return {
        "scope": "Platform-selected Go source import graph and ancestor notices; not a binary audit or legal clearance.",
        "packageCount": len(packages), "moduleCount": len(modules),
        "modules": sorted(modules.values(), key=lambda item: item["module"]),
        "licenseCounts": dict(Counter(notice["classification"] for item in modules.values() for notice in item["noticeFiles"])),
        "distributionReviewRequired": True
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--graph", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    report = inventory(read_go_stream(args.graph))
    if args.baseline:
        old = {item["module"]: item for item in json.loads(args.baseline.read_text())["modules"]}
        new = {item["module"]: item for item in report["modules"]}
        changes = []
        for module in sorted(set(old) | set(new)):
            before, after = old.get(module), new.get(module)
            old_notices = {item["file"]: item["sha256"] for item in (before or {}).get("noticeFiles", (before or {}).get("licenseFiles", []))}
            new_notices = {item["file"]: item["sha256"] for item in (after or {}).get("noticeFiles", [])}
            if before is None or after is None or before.get("version") != after.get("version") or old_notices != new_notices:
                changes.append({"module": module, "from": (before or {}).get("version"), "to": (after or {}).get("version"),
                                "noticesChanged": old_notices != new_notices})
        report["changesFromBaseline"] = changes
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({key: report[key] for key in ("packageCount", "moduleCount", "licenseCounts")}))
    print(json.dumps(report.get("changesFromBaseline", []), indent=2))


if __name__ == "__main__":
    main()
