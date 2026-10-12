#!/usr/bin/env python3
"""Stage the exact verified reactor artifacts for publication, without uploading."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
version = ET.parse(root / "pom.xml").getroot().findtext("{http://maven.apache.org/POM/4.0.0}version")
commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
destination = root / "target/release"
destination.mkdir(parents=True, exist_ok=False)
sources = [root / "serdeproof-dist/target" / f"serdeproof-{version}.zip", root / "target/bom.json"]
for module in ("serdeproof-api", "serdeproof-core", "serdeproof-cli", "demo-jackson2", "demo-jackson3"):
    sources.append(root / module / "target" / f"{module}-{version}.jar")
checks = {}
for source in sources:
    shutil.copy2(source, destination / source.name)
    checks[source.name] = hashlib.sha256(source.read_bytes()).hexdigest()
manifest = destination / "release-manifest.json"
manifest.write_text(json.dumps({"version": version, "commit": commit, "sha256": checks}, indent=2) + "\n", encoding="utf-8")
checks[manifest.name] = hashlib.sha256(manifest.read_bytes()).hexdigest()
(destination / "SHA256SUMS").write_text("".join(f"{digest}  {name}\n" for name, digest in sorted(checks.items())), encoding="utf-8")
print(f"Staged {len(checks)} verified artifacts for {version} at {commit}.")
