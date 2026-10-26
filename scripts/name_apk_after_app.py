"""Name build artifacts after the app: DDownloader-<version>-<code>-<variant>.apk

Also makes the verification/CI scripts resolve APKs by pattern instead of the old
fixed `app-release.apk` name.

Run: python scripts/name_apk_after_app.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
VERSION_CODE = "8"
VERSION_NAME = "1.0.7"


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def write(p, t):
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(t)


# ── 1. Gradle: single source of truth for version + output file naming ──────
t = read(GRADLE)
t = re.sub(r"versionCode = \d+", "versionCode = appVersionCode", t, count=1)
t = re.sub(r'versionName = "[^"]+"', 'versionName = appVersionName', t, count=1)

if "val appVersionCode" not in t:
    # define the values right before the android { } block
    anchor = "android {"
    t = t.replace(
        anchor,
        '// Version and artifact naming live in one place.\n'
        'val appVersionCode = %s\n'
        'val appVersionName = "%s"\n\n'
        "android {" % (VERSION_CODE, VERSION_NAME),
        1,
    )

if "outputFileName.set" not in t:
    t = t.rstrip() + '''

/**
 * Name the artifacts after the app instead of Gradle's default `app-release.apk`:
 *   DDownloader-<version>-<code>-release.apk / -debug.apk / -verify.apk
 * The version inside the file name is what a user sees when they download it, so it
 * has to match the release they are installing.
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                "DDownloader-$appVersionName-$appVersionCode-${variant.name}.apk",
            )
        }
    }
}
'''
write(GRADLE, t)
print("gradle: version %s (%s) + artifact naming" % (VERSION_NAME, VERSION_CODE))

# ── 2. Verification script: resolve by pattern ─────────────────────────────
verify = os.path.join(ROOT, "scripts", "verify-release-artifacts.ps1")
if os.path.exists(verify):
    t = read(verify)
    t = t.replace(
        "[string]$ReleaseApk = 'app\\build\\outputs\\apk\\release\\app-release.apk',",
        "[string]$ReleaseApk = '',",
    )
    t = t.replace(
        "[string]$VerifyApk = 'app\\build\\outputs\\apk\\verify\\app-verify.apk'",
        "[string]$VerifyApk = ''",
    )
    if "Resolve-Artifact" not in t:
        helper = '''
# Artifacts are named DDownloader-<version>-<code>-<variant>.apk, so resolve them by
# pattern rather than a hard-coded file name.
function Resolve-Artifact([string]$dir) {
    $hit = Get-ChildItem $dir -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $hit) { throw "no APK found in $dir - build first" }
    return $hit.FullName
}
if (-not $ReleaseApk) { $ReleaseApk = Resolve-Artifact 'app\\build\\outputs\\apk\\release' }
if (-not $VerifyApk)  { $VerifyApk  = Resolve-Artifact 'app\\build\\outputs\\apk\\verify' }
'''
        # insert after the param() block
        idx = t.find(")")
        if idx > 0:
            t = t[:idx + 1] + "\n" + helper + t[idx + 1:]
    t = t.replace(
        "foreach ($p in @('app\\build\\outputs\\apk\\debug\\app-debug.apk', 'app\\build\\outputs\\apk\\release\\app-release.apk',",
        "foreach ($p in @((Get-ChildItem 'app\\build\\outputs\\apk\\debug' -Filter '*.apk' | Select-Object -First 1 -ExpandProperty FullName), $ReleaseApk,",
    )
    write(verify, t)
    print("verify script resolves APKs by pattern")

# ── 3. CI: upload whatever the build produced ──────────────────────────────
ci = os.path.join(ROOT, ".github", "workflows", "android-ci.yml")
if os.path.exists(ci):
    t = read(ci)
    t = t.replace("            app/build/outputs/apk/debug/app-debug.apk\n            app/build/outputs/apk/verify/app-verify.apk",
                  "            app/build/outputs/apk/debug/*.apk\n            app/build/outputs/apk/verify/*.apk")
    write(ci, t)
    print("CI uploads by pattern")

# ── 4. Docs: stop referring to app-release.apk ────────────────────────────
doc_replacements = [
    ("app\\build\\outputs\\apk\\release\\app-release.apk", "app\\build\\outputs\\apk\\release\\DDownloader-<version>-<code>-release.apk"),
    ("app\\build\\outputs\\apk\\debug\\app-debug.apk", "app\\build\\outputs\\apk\\debug\\DDownloader-<version>-<code>-debug.apk"),
    ("app\\build\\outputs\\apk\\verify\\app-verify.apk", "app\\build\\outputs\\apk\\verify\\DDownloader-<version>-<code>-verify.apk"),
    ("(Get-FileHash $apk -Algorithm SHA256).Hash.ToLower() | Set-Content app-release.apk.sha256",
     '(Get-FileHash $apk -Algorithm SHA256).Hash.ToLower() | Set-Content \"$apk.sha256\"'),
    ("gh release create v1.0.1 $apk app-release.apk.sha256 `", "gh release create v1.0.7 $apk \"$apk.sha256\" `"),
    ("| `app-release.apk` (R8 + resource shrink) | **2.84 MB** |",
     "| `DDownloader-1.0.7-8-release.apk` (R8 + resource shrink) | **2.93 MB** |"),
    ("| `app-verify.apk` (same, + test harness) | **3.09 MB** |",
     "| `DDownloader-1.0.7-8-verify.apk` (same, + test harness) | **3.15 MB** |"),
    ("| `app-debug.apk` | 12.97 MB |", "| `DDownloader-1.0.7-8-debug.apk` | ~13 MB |"),
    ("Publish the GitHub Release with `app-release.apk` **and** `app-release.apk.sha256`.",
     "Publish the GitHub Release with `DDownloader-<version>-<code>-release.apk` **and** its `.sha256` sidecar."),
]
for name in ("README.md", "RELEASING.md", "VERSIONING.md", "PERFORMANCE.md", "FINAL_REPORT.md",
             os.path.join("docs", "ENVIRONMENT.md"), "THREAT-MODEL.md"):
    p = os.path.join(ROOT, name)
    if not os.path.exists(p):
        continue
    t = read(p)
    o = t
    for old, new in doc_replacements:
        t = t.replace(old, new)
    if t != o:
        write(p, t)
        print("docs updated:", name)
print("done")
