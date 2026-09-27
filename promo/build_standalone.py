#!/usr/bin/env python3
"""Embed every promo/photos image into the cinematic HTML template."""

from __future__ import annotations

import base64
import mimetypes
import re
from pathlib import Path

PROMO_DIR = Path(__file__).resolve().parent
TEMPLATE = PROMO_DIR / "index.html"
PHOTOS_DIR = PROMO_DIR / "photos"
OUTPUT = PROMO_DIR / "EMF-Sentinel-Promo.html"
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".avif"}

SRC_RE = re.compile(
    r"(?P<prefix>\bsrc\s*=\s*)(?P<quote>[\"'])(?P<path>photos/[^\"']+)(?P=quote)",
    re.IGNORECASE,
)
URL_RE = re.compile(
    r"(?P<prefix>url\(\s*)(?P<quote>[\"'])(?P<path>photos/[^\"']+)(?P=quote)(?P<suffix>\s*\))",
    re.IGNORECASE,
)


def load_photo_data() -> dict[str, str]:
    """Read all raster/vector image files and encode them as data URIs."""
    photos: dict[str, str] = {}
    for path in sorted(PHOTOS_DIR.iterdir()):
        if not path.is_file() or path.suffix.lower() not in IMAGE_EXTENSIONS:
            continue
        mime_type, _ = mimetypes.guess_type(path.name)
        if path.suffix.lower() == ".svg":
            mime_type = "image/svg+xml"
        if not mime_type or not mime_type.startswith("image/"):
            raise ValueError(f"Unable to determine an image MIME type for {path.name}")
        relative = f"photos/{path.name}"
        encoded = base64.b64encode(path.read_bytes()).decode("ascii")
        photos[relative] = f"data:{mime_type};base64,{encoded}"

    if not photos:
        raise RuntimeError(f"No promo images found in {PHOTOS_DIR}")
    return photos


def main() -> None:
    template = TEMPLATE.read_text(encoding="utf-8")
    photo_data = load_photo_data()
    usage = {name: 0 for name in photo_data}

    def data_uri_for(name: str) -> str:
        if name not in photo_data:
            raise FileNotFoundError(f"Template references missing promo image: {name}")
        usage[name] += 1
        return photo_data[name]

    def replace_src(match: re.Match[str]) -> str:
        return f"{match.group('prefix')}{match.group('quote')}{data_uri_for(match.group('path'))}{match.group('quote')}"

    def replace_url(match: re.Match[str]) -> str:
        return (
            f"{match.group('prefix')}{match.group('quote')}"
            f"{data_uri_for(match.group('path'))}{match.group('quote')}{match.group('suffix')}"
        )

    standalone = SRC_RE.sub(replace_src, template)
    standalone = URL_RE.sub(replace_url, standalone)

    unused = [name for name, count in usage.items() if count == 0]
    if unused:
        raise RuntimeError("Promo image files are not referenced by the template: " + ", ".join(unused))
    if re.search(r"(?:src\s*=\s*[\"']photos/|url\(\s*[\"']photos/)", standalone, re.IGNORECASE):
        raise RuntimeError("A local photos/ reference was not embedded")

    OUTPUT.write_text(standalone, encoding="utf-8", newline="\n")
    size = OUTPUT.stat().st_size
    print(f"Built {OUTPUT.name}: {size:,} bytes ({size / (1024 * 1024):.2f} MiB)")
    for name, count in usage.items():
        print(f"Embedded {name} × {count}")


if __name__ == "__main__":
    main()
