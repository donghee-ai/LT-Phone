#!/usr/bin/env python3
"""Replace local build prefixes in generated native libraries in-place."""

from __future__ import annotations

import argparse
import os
import re
from pathlib import Path


HOME_PATH = re.compile(
    rb"(?i)(?:[A-Z]:[\\/]Users[\\/][^\\/\x00\s]+|/home/[^/\x00\s]+|/Users/[^/\x00\s]+)"
)


def replace_same_size(data: bytes, source: bytes, replacement: bytes) -> tuple[bytes, int]:
    if not source or source == b"/" or len(replacement) > len(source):
        return data, 0
    count = data.count(source)
    if not count:
        return data, 0
    padded = replacement + (b"\x00" * (len(source) - len(replacement)))
    return data.replace(source, padded), count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--prefix", action="append", default=[])
    args = parser.parse_args()

    root = args.directory.resolve()
    if not root.is_dir():
        raise SystemExit(f"Native library directory does not exist: {root}")

    replacements = []
    for index, raw in enumerate(dict.fromkeys(args.prefix)):
        if not raw:
            continue
        normalized = os.path.abspath(raw).encode()
        replacements.append((normalized, f"/build/p{index}".encode()))
    replacements.sort(key=lambda pair: len(pair[0]), reverse=True)

    total = 0
    libraries = list(root.rglob("*.so"))
    for path in libraries:
        data = path.read_bytes()
        for source, replacement in replacements:
            data, count = replace_same_size(data, source, replacement)
            total += count
        path.resolve().write_bytes(data)

    remaining = 0
    for path in libraries:
        if HOME_PATH.search(path.read_bytes()):
            remaining += 1
    if remaining:
        raise SystemExit(f"Native libraries still contain user-home paths: {remaining}")

    print(f"Sanitized {len(libraries)} native libraries; replaced {total} local path prefixes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
