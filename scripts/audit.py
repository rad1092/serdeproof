#!/usr/bin/env python3
"""Check resolved Maven artifacts against Central metadata and OSV; scan source secrets.

Uses only Python's standard library. This is evidence of the checks performed, not
a warranty that dependencies have no unknown vulnerabilities or licensing issues.
"""
import argparse
import datetime as dt
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
CENTRAL = "https://repo.maven.apache.org/maven2/"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def request(url, data=None):
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(url, body, {"User-Agent": "SerdeProof-dependency-audit/0.1", "Content-Type": "application/json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=40) as response:
                return response.read(16 * 1024 * 1024)
        except (urllib.error.URLError, TimeoutError):
            if attempt == 3:
                raise
            time.sleep(attempt + 1)


def pom_licenses(group, name, version, seen=None):
    seen = set() if seen is None else seen
    coordinate = (group, name, version)
    if coordinate in seen or len(seen) >= 8:
        return []
    seen.add(coordinate)
    suffix = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}.pom"
    xml = ET.fromstring(request(CENTRAL + suffix))
    licenses = [node.text.strip() for node in xml.findall("m:licenses/m:license/m:name", NS) if node.text]
    if not licenses:
        parent = xml.find("m:parent", NS)
        if parent is not None:
            vals = [parent.findtext("m:" + field, namespaces=NS) for field in ("groupId", "artifactId", "version")]
            if all(vals) and not any("${" in x for x in vals):
                return pom_licenses(*vals, seen)
    return licenses


def scan_secrets():
    patterns = [
        re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
        re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
        re.compile(rb"\bgithub_pat_[A-Za-z0-9_]{60,}\b"),
        re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
        re.compile(rb"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
    ]
    listing = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT)
    hits, scanned = [], 0
    for raw in sorted(set(listing.split(b"\0"))):
        if not raw:
            continue
        relative = raw.decode("utf-8")
        path = ROOT / relative
        if not path.is_file() or path.is_symlink():
            continue
        content = path.read_bytes()
        scanned += 1
        if any(pattern.search(content) for pattern in patterns):
            hits.append(relative)  # Never print the matching content.
    return {"method": "five credential/private-key patterns; complements manual review, not exhaustive", "filesScanned": scanned, "findingFiles": hits}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bom", type=pathlib.Path, default=ROOT / "target/bom.json")
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "docs/evidence/dependency-audit.json")
    args = parser.parse_args()
    bom = json.loads(args.bom.read_text())
    resolved_hashes = {}
    for component in bom.get("components", []):
        if not component.get("group") or component["group"].startswith("io.github.rad1092.serdeproof"):
            continue
        coordinate = (component["group"], component["name"], component["version"])
        hashes = {h["content"].lower() for h in component.get("hashes", []) if h["alg"] == "SHA-256"}
        if len(hashes) != 1:
            raise ValueError("Resolved artifact SHA-256 missing or ambiguous")
        if coordinate in resolved_hashes and resolved_hashes[coordinate] != hashes:
            raise ValueError("Conflicting resolved artifact hashes")
        resolved_hashes[coordinate] = hashes
    components = sorted(resolved_hashes)
    if not components:
        raise ValueError("Resolved dependency inventory is empty")
    queries = [{"package": {"ecosystem": "Maven", "name": f"{g}:{n}"}, "version": v} for g, n, v in components]
    osv = json.loads(request("https://api.osv.dev/v1/querybatch", {"queries": queries}))["results"]
    records = []
    for (group, name, version), result in zip(components, osv, strict=True):
        if set(result) - {"vulns", "next_page_token"} or result.get("next_page_token"):
            raise ValueError("OSV response incomplete or unrecognized")
        suffix = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}"
        jar = request(CENTRAL + suffix + ".jar")
        # Central SHA-1 is a transfer-integrity check, not a publisher signature.
        expected = request(CENTRAL + suffix + ".jar.sha1").decode().split()[0].lower()
        if hashlib.sha1(jar).hexdigest() != expected:
            raise ValueError("Central checksum mismatch")
        sha256 = hashlib.sha256(jar).hexdigest()
        if sha256 not in resolved_hashes[(group, name, version)]:
            raise ValueError("Resolved build artifact differs from official Central bytes")
        records.append({"coordinate": f"{group}:{name}:{version}", "source": CENTRAL + suffix + ".pom",
                        "sha256": sha256, "centralSha1Matched": True, "resolvedBomSha256Matched": True,
                        "licenses": pom_licenses(group, name, version),
                        "osvVulnerabilities": sorted(v["id"] for v in result.get("vulns", []))})
    secrets = scan_secrets()
    failed = bool(secrets["findingFiles"] or any(r["osvVulnerabilities"] or not r["licenses"] for r in records))
    evidence = {"checkedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
                "scope": "Resolved reactor compile/runtime/test dependencies in aggregate CycloneDX BOM; build plugins and JDK excluded",
                "limitations": "OSV covers known indexed advisories only; license names are upstream declarations, not legal advice; checksums are unsigned",
                "bomSha256": hashlib.sha256(args.bom.read_bytes()).hexdigest(), "dependencies": records,
                "secretScan": secrets, "passed": not failed}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + "\n")
    print(f"Dependency audit: {len(records)} artifacts; source scan: {secrets['filesScanned']} files; {'FAIL' if failed else 'PASS'}")
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"Audit incomplete: {type(error).__name__}. No success result was issued.", file=sys.stderr)
        sys.exit(2)
