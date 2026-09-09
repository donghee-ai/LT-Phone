#!/usr/bin/env python3
"""Remove build-machine metadata from the Web UI production bundle."""

from __future__ import annotations

import argparse
import re
import struct
from pathlib import Path


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PRIVATE_PNG_CHUNKS = {b"tEXt", b"zTXt", b"iTXt", b"tIME", b"eXIf"}
HOME_PATH = re.compile(
    rb"(?i)(?:[A-Z]:[\\/]Users[\\/][^\\/\x00\s]+|/home/[^/\x00\s]+|/Users/[^/\x00\s]+)"
)


def strip_png_metadata(path: Path) -> int:
    data = path.read_bytes()
    if not data.startswith(PNG_SIGNATURE):
        return 0
    output = bytearray(PNG_SIGNATURE)
    offset = len(PNG_SIGNATURE)
    removed = 0
    while offset + 12 <= len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        end = offset + 12 + length
        if end > len(data):
            raise ValueError(f"Malformed PNG: {path}")
        chunk_type = data[offset + 4:offset + 8]
        if chunk_type in PRIVATE_PNG_CHUNKS:
            removed += 1
        else:
            output.extend(data[offset:end])
        offset = end
        if chunk_type == b"IEND":
            break
    if removed:
        path.write_bytes(output)
    return removed


def strip_source_map_reference(path: Path) -> int:
    data = path.read_bytes()
    cleaned, count_line = re.subn(rb"(?:\r?\n)?//[#@]\s*sourceMappingURL=[^\r\n]*", b"", data)
    cleaned, count_block = re.subn(rb"/\*#\s*sourceMappingURL=.*?\*/", b"", cleaned, flags=re.DOTALL)
    if cleaned != data:
        path.write_bytes(cleaned)
    return count_line + count_block


def strip_svg_editor_metadata(path: Path) -> int:
    data = path.read_bytes()
    cleaned, count = re.subn(rb'\s+inkscape:export-filename="[^"]*"', b"", data)
    if cleaned != data:
        path.write_bytes(cleaned)
    return count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("dist", type=Path)
    args = parser.parse_args()
    root = args.dist.resolve()
    if not root.is_dir():
        raise SystemExit(f"Web UI dist directory does not exist: {root}")

    map_files = list(root.rglob("*.map"))
    for path in map_files:
        path.unlink()

    references = 0
    png_chunks = 0
    svg_attributes = 0
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() in {".js", ".css"}:
            references += strip_source_map_reference(path)
        elif path.suffix.lower() == ".png":
            png_chunks += strip_png_metadata(path)
        elif path.suffix.lower() == ".svg":
            svg_attributes += strip_svg_editor_metadata(path)

    path_hits = []
    for path in root.rglob("*"):
        if path.is_file() and HOME_PATH.search(path.read_bytes()):
            path_hits.append(path.relative_to(root).as_posix())
    if path_hits:
        joined = ", ".join(sorted(path_hits))
        raise SystemExit(
            f"Web UI bundle still contains user-home paths in {len(path_hits)} files: {joined}"
        )

    print(
        f"Sanitized Web UI: removed {len(map_files)} source maps, "
        f"{references} source-map references, {png_chunks} PNG metadata chunks, "
        f"and {svg_attributes} SVG editor attributes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
