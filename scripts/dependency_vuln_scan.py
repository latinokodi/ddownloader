"""Dependency vulnerability scan against the OSV database (Google).

Parses the resolved release runtime classpath (EVIDENCE/deps-release.txt, produced by
`gradlew :app:dependencies --configuration releaseRuntimeClasspath`), queries
https://api.osv.dev/v1/querybatch for every Maven coordinate, then enriches each hit
from /v1/vulns/<id>.

Outputs:
  EVIDENCE/security/osv-report.json   full machine-readable result
  EVIDENCE/security/osv-summary.md    human summary
Exit code: 0 = no advisories for the resolved versions, 2 = advisories found,
           3 = scan could not be completed (network/parse), which is NOT a pass.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEPS = os.path.join(ROOT, "EVIDENCE", "deps-release.txt")
OUT = os.path.join(ROOT, "EVIDENCE", "security")
os.makedirs(OUT, exist_ok=True)

COORD = re.compile(r"[-+\\|]+\s*([\w.\-]+):([\w.\-]+):([\w.\-+]+)(?:\s*->\s*([\w.\-+]+))?")


def collect():
    coords = {}
    if not os.path.exists(DEPS):
        print("missing", DEPS)
        sys.exit(3)
    with open(DEPS, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = COORD.search(line)
            if not m:
                continue
            group, artifact, version, upgraded = m.groups()
            if upgraded:
                version = upgraded
            if group.startswith(("androidx.test", "junit", "org.jetbrains.kotlin:kotlin-test")):
                continue
            if version in ("(c)", "n/a") or version.endswith("-SNAPSHOT"):
                continue
            coords[f"{group}:{artifact}"] = version
    return coords


def query_batch(coords):
    payload = {
        "queries": [
            {"package": {"name": name, "ecosystem": "Maven"}, "version": v}
            for name, v in coords.items()
        ]
    }
    req = urllib.request.Request(
        "https://api.osv.dev/v1/querybatch",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.loads(r.read().decode())


def vuln_detail(vid):
    try:
        with urllib.request.urlopen(f"https://api.osv.dev/v1/vulns/{vid}", timeout=45) as r:
            return json.loads(r.read().decode())
    except Exception:  # noqa: BLE001
        return {}


def severity_of(detail):
    for sev in detail.get("severity", []) or []:
        if sev.get("type", "").startswith("CVSS"):
            return f"{sev.get('type')}:{sev.get('score')}"
    db = detail.get("database_specific", {}) or {}
    if db.get("severity"):
        return str(db["severity"])
    return "unknown"


def main():
    coords = collect()
    print(f"scanned coordinates: {len(coords)}")
    if not coords:
        print("no coordinates parsed")
        sys.exit(3)
    try:
        batch = query_batch(coords)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as exc:
        print("OSV query failed:", exc)
        sys.exit(3)

    names = list(coords.keys())
    findings = []
    for i, res in enumerate(batch.get("results", [])):
        vulns = res.get("vulns") or []
        if not vulns:
            continue
        name = names[i]
        version = coords[name]
        for v in vulns:
            vid = v.get("id")
            detail = vuln_detail(vid) if vid else {}
            findings.append(
                {
                    "package": name,
                    "version": version,
                    "id": vid,
                    "aliases": v.get("aliases", []),
                    "summary": (detail.get("summary") or v.get("summary") or "").strip(),
                    "severity": severity_of(detail),
                    "modified": v.get("modified"),
                }
            )
            print(f"  VULN {name}:{version} {vid} {findings[-1]['severity']}")

    report = {
        "generated_by": "scripts/dependency_vuln_scan.py",
        "source": "https://api.osv.dev (OSV, Google)",
        "ecosystem": "Maven",
        "coordinates_scanned": len(coords),
        "vulnerable_packages": len({f["package"] for f in findings}),
        "findings": findings,
        "coordinates": coords,
    }
    with open(os.path.join(OUT, "osv-report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, sort_keys=True)

    lines = [
        "# Dependency vulnerability scan (OSV)",
        "",
        f"- Coordinates scanned: **{len(coords)}** (release runtime classpath, Maven ecosystem)",
        f"- Advisories found: **{len(findings)}** across **{report['vulnerable_packages']}** packages",
        "",
    ]
    if findings:
        lines += ["| package | version | advisory | severity | summary |", "|---|---|---|---|---|"]
        for f in findings:
            lines.append(f"| {f['package']} | {f['version']} | {f['id']} | {f['severity']} | {f['summary'][:120]} |")
    else:
        lines.append("No advisories were returned for the resolved versions.")
    with open(os.path.join(OUT, "osv-summary.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print(f"\nfindings={len(findings)} report={os.path.join(OUT, 'osv-report.json')}")
    sys.exit(2 if findings else 0)


if __name__ == "__main__":
    main()
