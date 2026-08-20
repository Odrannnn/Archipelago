#!/usr/bin/env python3
"""Merge one verified build entry into the published dependency catalog."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--entry", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--existing", type=Path)
    arguments = parser.parse_args()

    entry = json.loads(arguments.entry.read_text(encoding="utf-8"))
    catalog = {"schema": 1, "packages": []}
    if arguments.existing and arguments.existing.is_file():
        catalog = json.loads(arguments.existing.read_text(encoding="utf-8"))
        if catalog.get("schema") != 1 or not isinstance(catalog.get("packages"), list):
            raise ValueError("Existing catalog has an unsupported schema")
    identity = (entry["package"], entry["version"], entry["android_abi"])
    retained = [item for item in catalog["packages"] if
                (item.get("package"), item.get("version"), item.get("android_abi")) != identity]
    retained.append(entry)
    retained.sort(key=lambda item: (item["package"], item["version"], item["android_abi"]))
    catalog["packages"] = retained
    catalog["generated_at"] = datetime.now(timezone.utc).isoformat()
    arguments.output.write_text(json.dumps(catalog, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
