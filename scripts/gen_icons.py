"""Generate Compose ImageVectors from Material Icons (outlined, 24 dp set).

Why classic 24 px icons instead of Material Symbols: the Symbols SVGs use a
960x960 viewBox with origin (0,-960). Drawing those in a 24x24 Compose viewport put
every glyph outside the visible box, so icons rendered invisible on device
(confirmed on the emulator). These SVGs are authored at "0 0 24 24", so the path
data maps 1:1 into the viewport with no transform group needed.

Source: https://github.com/google/material-design-icons (Apache-2.0).
Output:
  app/src/main/java/pro/tordownloader/android/ui/icons/AppIcons.kt
  EVIDENCE/icons/material-icons-paths.json   (provenance: URL + viewBox + paths)
"""
import json
import os
import re
import urllib.error
import urllib.request

ICONS = [
    "search", "download", "file_download", "settings", "explore", "terminal",
    "shield", "speed", "timer", "link", "folder_open", "check_circle", "error",
    "pause", "delete", "close", "arrow_back", "refresh", "public", "bolt",
    "cloud_download", "movie", "live_tv", "verified_user", "key", "tune",
    "more_vert", "play_arrow", "history", "cancel", "science", "sort", "filter_alt",
]

CATEGORIES = [
    "action", "alert", "av", "communication", "content", "device", "editor",
    "file", "hardware", "home", "image", "maps", "navigation", "notification",
    "places", "search", "social", "toggle",
]
STYLES = ["materialiconsoutlined", "materialicons"]
BASE = ("https://raw.githubusercontent.com/google/material-design-icons/master/"
        "src/{cat}/{name}/{style}/24px.svg")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_KT = os.path.join(ROOT, "app", "src", "main", "java", "pro", "tordownloader",
                      "android", "ui", "icons", "AppIcons.kt")
OUT_JSON = os.path.join(ROOT, "EVIDENCE", "icons", "material-icons-paths.json")


def fetch(name):
    for cat in CATEGORIES:
        for style in STYLES:
            url = BASE.format(cat=cat, name=name, style=style)
            try:
                with urllib.request.urlopen(url, timeout=20) as r:
                    svg = r.read().decode("utf-8")
            except Exception:  # noqa: BLE001 - 404s are expected while probing
                continue
            vb_match = re.search(r'viewBox="([^"]+)"', svg)
            vb = vb_match.group(1) if vb_match else "0 0 24 24"
            # Material Icons SVGs often start with a full-bleed frame path
            # (<path d="M0 0h24v24H0V0z" fill="none"/>). Filling that black draws a
            # solid square over the glyph, so any fill="none" path must be skipped.
            paths = []
            for tag in re.findall(r'<path\b[^>]*>', svg):
                if re.search(r'fill\s*=\s*"none"', tag):
                    continue
                d = re.search(r'\sd="([^"]+)"', tag)
                if d:
                    paths.append(re.sub(r"\s+", " ", d.group(1)).strip())
            return url, vb, paths
    raise RuntimeError("no SVG found for %s" % name)


def prop(n):
    return "".join(p[:1].upper() + p[1:] for p in n.split("_") if p)


def main():
    data = {}
    for n in ICONS:
        try:
            url, vb, paths = fetch(n)
            data[n] = {"url": url, "viewBox": vb, "paths": paths}
            print("OK   %-16s vb=%-10s paths=%d  %s" % (n, vb, len(paths), url.split("/src/")[-1]))
        except Exception as exc:  # noqa: BLE001
            print("FAIL %-16s %s" % (n, exc))

    os.makedirs(os.path.dirname(OUT_JSON), exist_ok=True)
    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, sort_keys=True)

    lines = [
        "package pro.tordownloader.android.ui.icons",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.graphics.SolidColor",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        "import androidx.compose.ui.graphics.vector.addPathNodes",
        "import androidx.compose.ui.unit.dp",
        "",
        "/**",
        " * Icon set: Material Icons (outlined, 24 dp set). Path data verbatim from",
        " * https://github.com/google/material-design-icons (Apache-2.0); the source URL",
        " * of every glyph is recorded in EVIDENCE/icons/material-icons-paths.json.",
        " *",
        " * Built as ImageVectors so the APK carries only these ~33 glyphs instead of",
        " * the ~20 MB material-icons-extended artifact.",
        " */",
        "object AppIcons {",
        "",
    ]
    for n, meta in data.items():
        lines.append("    val %s: ImageVector by lazy {" % prop(n))
        lines.append('        icon("%s") {' % n)
        for p in meta["paths"]:
            lines.append('            add("%s")' % p)
        lines.append("        }")
        lines.append("    }")
        lines.append("")
    lines += [
        "    private class IconPaths(private val name: String) {",
        "        private val paths =",
        "            mutableListOf<List<androidx.compose.ui.graphics.vector.PathNode>>()",
        "",
        "        fun add(pathData: String) {",
        "            paths += addPathNodes(pathData)",
        "        }",
        "",
        "        fun build(): ImageVector = ImageVector.Builder(",
        "            name = name,",
        "            defaultWidth = 24.dp,",
        "            defaultHeight = 24.dp,",
        "            viewportWidth = 24f,",
        "            viewportHeight = 24f,",
        "        ).apply {",
        "            paths.forEach { nodes -> addPath(nodes, fill = SolidColor(Color.Black)) }",
        "        }.build()",
        "    }",
        "",
        "    private inline fun icon(name: String, block: IconPaths.() -> Unit): ImageVector =",
        "        IconPaths(name).apply(block).build()",
        "}",
        "",
    ]
    with open(OUT_KT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    print("\nwrote %s (%d icons)" % (OUT_KT, len(data)))


if __name__ == "__main__":
    main()
