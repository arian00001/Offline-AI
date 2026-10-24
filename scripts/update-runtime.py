#!/usr/bin/env python3
"""Regenerate runtime/runtime.json from the bundled chat HTML."""
import hashlib
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
html_path = root / "app/src/main/assets/chat.html"
manifest_path = root / "runtime/runtime.json"
if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 scripts/update-runtime.py 1.0.2")
version = sys.argv[1].strip()
if not version or not all(part.isdigit() for part in version.split(".")) or len(version.split(".")) < 2:
    raise SystemExit("Version must look like 1.0.2")
html = html_path.read_text(encoding="utf-8")
manifest = {
    "version": version,
    "sha256": hashlib.sha256(html.encode("utf-8")).hexdigest(),
    "html": html,
}
manifest_path.parent.mkdir(parents=True, exist_ok=True)
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"Updated {manifest_path.relative_to(root)} to v{version}")
print(f"SHA-256: {manifest['sha256']}")
