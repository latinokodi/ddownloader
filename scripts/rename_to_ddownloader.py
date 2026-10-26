"""Fix compile errors from the updater wiring, then rename the app to DDownloader.

- package  com.ddownloader.android  ->  com.ddownloader.android
- label / UI / docs                    ->  DDownloader
- update feed                          ->  latinokodi/ddownloader
- removes all "tor" framing copy from the app UI

Run: python scripts/rename_to_ddownloader.py [--apply]
"""
import io
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app")
OLD_PKG = "com.ddownloader.android"
NEW_PKG = "com.ddownloader.android"
OLD_PATH = os.path.join(APP, "src", "main", "java", "pro", "tordownloader", "android")
NEW_PATH = os.path.join(APP, "src", "main", "java", "com", "ddownloader", "android")
OLD_TEST = os.path.join(APP, "src", "test", "java", "pro", "tordownloader", "android")
NEW_TEST = os.path.join(APP, "src", "test", "java", "com", "ddownloader", "android")

TEXT_EXT = (".kt", ".kts", ".java", ".xml", ".md", ".py", ".ps1", ".toml", ".properties", ".jsonl")


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def write(p, t):
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(t)


def fix_compile_errors():
    # 1) UpdateChecker: extension helpers live in the api package
    p = os.path.join(NEW_PATH, "data", "UpdateChecker.kt")
    if not os.path.exists(p):
        p = os.path.join(OLD_PATH, "data", "UpdateChecker.kt")
    t = read(p)
    if "import com.ddownloader.android.api.Web" not in t:
        t = t.replace(
            "import com.ddownloader.android.core.AppLog",
            "import com.ddownloader.android.api.Web\nimport com.ddownloader.android.api.str\n"
            "import com.ddownloader.android.core.AppLog",
        )
    write(p, t)

    # 2) UpdateInstaller: expression-body functions may not use `return`
    p = os.path.join(NEW_PATH, "data", "UpdateInstaller.kt")
    if not os.path.exists(p):
        p = os.path.join(OLD_PATH, "data", "UpdateInstaller.kt")
    t = read(p)
    t = t.replace(
        "    fun install(apk: File): Result<Unit> = try {\n",
        "    fun install(apk: File): Result<Unit> {\n        return try {\n",
    )
    t = t.replace(
        """        AppLog.e(TAG, "could not start the installer: ${t.message}", t)
        Result.failure(t)
    }""",
        """            AppLog.e(TAG, "could not start the installer: ${t.message}", t)
            Result.failure(t)
        }
    }""",
    )
    # re-indent the body of the try block (8 -> 12 spaces) inside install()
    lines = t.split("\n")
    out, inside = [], False
    for ln in lines:
        if "return try {" in ln and "install(apk" not in ln:
            inside = True
            out.append(ln)
            continue
        if inside:
            if ln.strip() == "} catch (t: Throwable) {":
                out.append("        " + ln.strip())
                continue
            if ln.strip() == "}":
                inside = False
                out.append(ln)
                continue
            out.append(("    " + ln) if ln.strip() else ln)
            continue
        out.append(ln)
    write(p, "\n".join(out))

    # 3) AppViewModel: MutableStateFlow already imported
    p = os.path.join(NEW_PATH, "ui", "AppViewModel.kt")
    if not os.path.exists(p):
        p = os.path.join(OLD_PATH, "ui", "AppViewModel.kt")
    t = read(p)
    t = t.replace(
        "import com.ddownloader.android.data.UpdateInstaller\nimport kotlinx.coroutines.flow.MutableStateFlow",
        "import com.ddownloader.android.data.UpdateInstaller",
    )
    write(p, t)
    print("compile fixes applied")


def move_packages():
    for src, dst in ((OLD_PATH, NEW_PATH), (OLD_TEST, NEW_TEST)):
        if os.path.isdir(src) and not os.path.isdir(dst):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.move(src, dst)
            print("moved", src, "->", dst)
    # drop now-empty parents
    for p in (os.path.join(APP, "src", "main", "java", "pro"),
              os.path.join(APP, "src", "test", "java", "pro")):
        if os.path.isdir(p) and not os.listdir(p):
            os.rmdir(p)


def rewrite_text():
    targets = []
    for base in (APP, os.path.join(ROOT, "scripts"), os.path.join(ROOT, "config"),
                 os.path.join(ROOT, "test-fixtures")):
        for dirpath, _dirs, files in os.walk(base):
            for f in files:
                if f.endswith(TEXT_EXT):
                    targets.append(os.path.join(dirpath, f))
    for f in os.listdir(ROOT):
        if f.endswith(".md"):
            targets.append(os.path.join(ROOT, f))
    for name in ("settings.gradle.kts", "build.gradle.kts", "gradle.properties", ".gitignore", "gradle/libs.versions.toml"):
        p = os.path.join(ROOT, name)
        if os.path.exists(p):
            targets.append(p)

    changed = 0
    for p in targets:
        try:
            t = read(p)
        except UnicodeDecodeError:
            continue
        o = t
        t = t.replace(OLD_PKG, NEW_PKG)
        t = t.replace("the Electron app", "the Electron app")
        t = t.replace("DDOWNLOADER", "DDOWNLOADER")
        t = t.replace("DDownloader", "DDownloader")
        t = t.replace("ddownloader", "ddownloader")
        t = t.replace("ddownloaderelectron", "ddownloaderelectron")
        if p.endswith("strings.xml"):
            t = t.replace('name="app_name">DDownloader', 'name="app_name">DDownloader')
        if p.endswith("build.gradle.kts") or p.endswith("ServiceLocator.kt"):
            t = t.replace('latinokodi/ddownloader', 'latinokodi/ddownloader')
        if t != o:
            write(p, t)
            changed += 1
    print("rewrote %d text files" % changed)


def strip_tor_copy():
    """Remove the "tor = TorBox" framing from UI copy; keep the product description."""
    onboarding = os.path.join(NEW_PATH, "ui", "onboarding", "OnboardingScreen.kt")
    if os.path.exists(onboarding):
        t = read(onboarding)
        t = t.replace(
            '''                Text(
                    "\\u201Ctor\\u201D in the name is TorBox. This app does not use the Tor anonymity network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextMid,
                )''',
            '''                Text(
                    "Your tokens stay encrypted on the device, and every request is HTTPS only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColor.TextMid,
                )''',
        )
        t = t.replace(
            '''                "TorBox + Real-Debrid client with resumable downloads, Jellyfin-ready folders and a " +
                "foreground service that keeps transfers alive.",''',
            '''                "TorBox + Real-Debrid client with resumable downloads, Jellyfin-ready folders and a " +
                    "foreground service that keeps transfers alive.",''',
        )
        write(onboarding, t)

    settings = os.path.join(NEW_PATH, "ui", "screens", "SettingsScreen.kt")
    if os.path.exists(settings):
        t = read(settings)
        t = t.replace(
            '''                    "This app talks to TorBox, Real-Debrid and TMDB over HTTPS. " +
                        "\\"tor\\" here is TorBox — no anonymity network is involved.",''',
            '''                    "This app talks to TorBox, Real-Debrid and TMDB over HTTPS only.",''',
        )
        write(settings, t)

    readme = os.path.join(ROOT, "README.md")
    if os.path.exists(readme):
        t = read(readme)
        t = re.sub(
            r"> \*\*\"tor\" = TorBox\.\*\*.*?\n\n", "", t, flags=re.S,
        )
        write(readme, t)
    print("tor framing removed from UI copy")


if __name__ == "__main__":
    fix_compile_errors()
    move_packages()
    rewrite_text()
    strip_tor_copy()
    print("rename complete")
