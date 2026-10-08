#!/usr/bin/env python3
"""Exercise installed artifacts, the public library/adapter examples and release ZIP."""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION = "0.1.0"
GROUP = pathlib.Path("io/github/rad1092/serdeproof")


def run(args, expected=0, cwd=ROOT):
    result = subprocess.run([str(x) for x in args], cwd=cwd, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=180)
    if result.returncode != expected:
        raise AssertionError(f"Expected exit {expected}, got {result.returncode}\n{result.stdout[-3000:]}\n{result.stderr[-3000:]}")
    return result


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    evidence_dir = ROOT / "target/verification"
    evidence_dir.mkdir(parents=True, exist_ok=True)
    default_repo = ROOT / ".local-repository"
    if not default_repo.exists():
        default_repo = pathlib.Path.home() / ".m2/repository"
    repo = pathlib.Path(os.environ.get("SERDEPROOF_MAVEN_REPO", str(default_repo))).resolve()
    java_home = os.environ.get("JAVA_HOME")
    suffix = ".exe" if os.name == "nt" else ""
    def tool(name):
        return pathlib.Path(java_home) / "bin" / (name + suffix) if java_home else shutil.which(name)
    java, javac, jar = (tool(x) for x in ("java", "javac", "jar"))
    artifacts = {}
    for name in ("serdeproof-api", "serdeproof-core", "serdeproof-cli", "demo-jackson2", "demo-jackson3"):
        path = repo / GROUP / name / VERSION / f"{name}-{VERSION}.jar"
        if not path.is_file():
            raise AssertionError(f"Installed artifact missing: {name}; run Maven install first")
        artifacts[name] = path
    assert run([java, "-jar", artifacts["serdeproof-cli"], "--version"]).stdout.strip() == f"SerdeProof {VERSION}"
    scenarios = []
    # Keep the *calling* Java launcher on an ordinary path. Some JDK 17 builds cannot
    # bootstrap an application from supplementary-Unicode -cp paths before its code runs.
    # SerdeProof's own adapter launcher must still handle the emoji JAR below.
    with tempfile.TemporaryDirectory(prefix="install smoke ", dir=evidence_dir) as temporary:
        workspace = pathlib.Path(temporary)
        def manifest_for(name):
            source = ROOT / "examples" / name
            data = json.loads(source.read_text(encoding="utf-8"))
            for side in ("baseline", "candidate"):
                adapter = data[side]
                adapter["classpath"] = [str(artifacts["demo-jackson2" if "jackson2" in p else "demo-jackson3"])
                                        for p in adapter["classpath"]]
                if "configuration" in adapter:
                    adapter["configuration"] = str((source.parent / adapter["configuration"]).resolve())
            for fixture in data["fixtures"]:
                fixture["file"] = str((source.parent / fixture["file"]).resolve())
            manifest = workspace / name
            manifest.write_text(json.dumps(data), encoding="utf-8")
            return manifest, data
        for name, expected in (("demo.json", 1), ("compatible.json", 0), ("jackson-config-change.json", 1),
                               ("same-version-loss.json", 1), ("jackson-reviewed.json", 1)):
            manifest, data = manifest_for(name)
            json_report, xml_report = workspace / (name + ".report.json"), workspace / (name + ".xml")
            command = [java, "-jar", artifacts["serdeproof-cli"], "run", "--manifest", manifest,
                       "--json", json_report, "--junit", xml_report, "--as-of", "2026-10-08"]
            run(command, expected)
            first_json, first_xml = json_report.read_bytes(), xml_report.read_bytes()
            report_data = json.loads(first_json)
            assert not report_data["errors"], "Scenario had an infrastructure error"
            assert all(len(f["matrix"]) == 4 for f in report_data["fixtures"]), "Four-way matrix missing"
            if name == "jackson-reviewed.json":
                assert report_data["rules"][0]["status"] == "USED"
                assert report_data["summary"]["expectedDifferences"] == 1
                assert report_data["summary"]["unexpectedDifferences"] > 0
            if name == "same-version-loss.json":
                findings = [f for fixture in report_data["fixtures"] for f in fixture["findings"]]
                assert findings and all(f["kind"].startswith("ROUNDTRIP_") for f in findings)
            for fixture in data["fixtures"]:
                assert fixture["id"].encode() not in first_json, "Fixture identity leaked in default JSON"
                assert fixture["id"].encode() not in first_xml, "Fixture identity leaked in default JUnit"
            run(command + ["--force"], expected)
            assert first_json == json_report.read_bytes(), "JSON report changed across identical runs"
            assert first_xml == xml_report.read_bytes(), "JUnit report changed across identical runs"
            scenarios.append({"manifest": name, "expectedExit": expected, "deterministic": True,
                              "reportSha256": hashlib.sha256(first_json).hexdigest()})
            print(f"PASS {name}: exit {expected}; deterministic JSON/JUnit", flush=True)
        # Compile a separate consumer against the installed public core/API artifacts. The demo JAR
        # supplies Jackson dependencies; API and core appear first so their installed JARs are loaded.
        classes = workspace / "consumer classes"
        classes.mkdir()
        deps = [artifacts["serdeproof-api"], artifacts["serdeproof-core"], artifacts["demo-jackson2"]]
        cp = os.pathsep.join(str(x) for x in deps)
        example = ROOT / "examples/custom-adapter"
        sources = sorted((example / "src").rglob("*.java")) + [example / "LibraryExample.java"]
        run([javac, "-encoding", "UTF-8", "--release", "17", "-cp", cp, "-d", classes, *sources])
        adapter_jar = workspace / "사용자 adapter 😀.jar"
        # Windows JDK jartool may reject an emoji destination while creating its temporary
        # file. Package normally, then retain full Unicode runtime coverage via a rename.
        staged_adapter = workspace / "adapter-build.jar"
        run([jar, "--create", "--file", staged_adapter, "-C", classes, "."])
        staged_adapter.rename(adapter_jar)
        adapter = {"adapterClass": "example.OrderAdapter",
                   "classpath": [str(adapter_jar), str(artifacts["serdeproof-api"]), str(artifacts["demo-jackson2"])]}
        manifest = workspace / "library-manifest.json"
        manifest.write_text(json.dumps({"baseline": adapter, "candidate": adapter,
            "fixtures": [{"id": "installed-order", "type": "order", "file": str(example / "order.json")}]}), encoding="utf-8")
        run([java, "-cp", os.pathsep.join([str(classes), cp]), "LibraryExample", manifest, workspace / "library-report"])
        scenarios.append({"name": "installed-public-library-and-custom-adapter", "passed": True})
        archive = ROOT / "serdeproof-dist/target" / f"serdeproof-{VERSION}.zip"
        with zipfile.ZipFile(archive) as zipped:
            names = zipped.namelist()
            assert not any("/.tools/" in n or "/.local-repository/" in n or "/target/" in n or "/.git/" in n for n in names)
            zipped.extractall(workspace / "배포 release")
        release = workspace / "배포 release" / f"serdeproof-{VERSION}"
        run([java, "-jar", release / "lib" / f"serdeproof-cli-{VERSION}.jar", "run",
             "--manifest", release / "examples/release-demo.json", "--json", workspace / "release.json",
             "--junit", workspace / "release.xml", "--as-of", "2026-10-08"], 1, cwd=workspace)
        scenarios.append({"name": "extracted-release-outside-checkout", "passed": True, "zipSha256": digest(archive)})
    evidence = {"os": sys.platform, "java": run([java, "-version"]).stderr.strip().splitlines()[0],
                "artifacts": {name: digest(path) for name, path in artifacts.items()}, "scenarios": scenarios, "passed": True}
    (evidence_dir / "smoke.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(f"Installed artifact smoke PASS: {len(scenarios)} scenarios, deterministic reports, custom API/library consumer and extracted release.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Smoke failed: {error}", file=sys.stderr)
        sys.exit(1)
