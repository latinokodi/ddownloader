#!/usr/bin/env python3
"""
a11y-audit.py -- mechanical accessibility audit for the TorBox Android client.

Owner: env-runner. Python 3.9+ standard library only (xml.etree, re, json).

INPUTS
  --golden-dir   directory with <screen>.xml uiautomator dumps (from capture-goldens.ps1)
  --theme        app/src/main/java/pro/tordownloader/android/ui/theme/Theme.kt
  --out-dir      where a11y-report.json + summary.md are written
  --density      screen density in dpi (default 420 -> 1dp = 2.625px)

CHECKS (per screen, from the live hierarchy XML)
  R1  every node with clickable="true" must have a non-empty content-desc OR a child
      with non-empty text (touch controls need an accessible name).
  R2  every clickable node's bounds must be >= 48dp in both dimensions
      (Android accessibility minimum touch target; 48dp * 420/160 = 126px).
  R3  every image/icon-like node must have a non-empty content-desc.
      Detection is class containing ImageView/Image/Icon OR resource-id containing
      icon/image. Compose may not expose icon nodes as ImageView, so the report
      states the detected-icon coverage rather than claiming completeness.

CONTRAST (WCAG 2.1 relative luminance)
  Parses TdColor from Theme.kt and computes the pairs the app actually uses:
    TextHi/TextMid/TextLow on Ink0/Ink1/Ink2
    Cyan/Mint/Amber/Rose on Ink2
    white on the violet->cyan gradient endpoints
  Flags < 4.5:1 (AA body text) and < 3:1 (AA large text / non-text).

USAGE
  python scripts/a11y-audit.py --golden-dir EVIDENCE/screenshots/golden \
      --theme app/src/main/java/pro/tordownloader/android/ui/theme/Theme.kt \
      --out-dir EVIDENCE/a11y
"""

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

CHECK_NAMES = {
    "R1": "clickable node has an accessible name (content-desc or child text)",
    "R2": "clickable touch target >= 48dp in both dimensions",
    "R3": "image/icon node has a content-desc",
}


def utcnow():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_bounds(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return {"x1": x1, "y1": y1, "x2": x2, "y2": y2,
            "w": x2 - x1, "h": y2 - y1,
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2}


def node_label(node):
    return {
        "class": node.get("class", ""),
        "resource_id": node.get("resource-id", ""),
        "text": node.get("text", ""),
        "content_desc": node.get("content-desc", ""),
        "bounds": node.get("bounds", ""),
        "checkable": node.get("checkable", ""),
        "enabled": node.get("enabled", ""),
        "pkg": node.get("package", ""),
    }


def subtree_text(node):
    """All labels the node exposes to a screen reader: own/descendant text AND
    descendant content-desc (a Compose icon button often carries its label on a child)."""
    parts = []
    for child in node.iter():
        t = (child.get("text") or "").strip()
        d = (child.get("content-desc") or "").strip()
        if t:
            parts.append(t)
        if d:
            parts.append(d)
    return " ".join(parts).strip()


def is_icon_like(node):
    cls = (node.get("class", "") or "")
    rid = (node.get("resource-id", "") or "")
    return ("ImageView" in cls or "Image" in cls or "Icon" in cls
            or re.search(r"(icon|image|avatar|logo|drawable)", rid, re.I) is not None)


# ---------------------------------------------------------------------------
# WCAG contrast
# ---------------------------------------------------------------------------

def srgb_to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def rel_luminance(rgb):
    r, g, b = rgb
    return 0.2126 * srgb_to_lin(r) + 0.7152 * srgb_to_lin(g) + 0.0722 * srgb_to_lin(b)


def contrast_ratio(fg, bg):
    l1, l2 = rel_luminance(fg), rel_luminance(bg)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def hex_to_rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def parse_theme(path):
    """Return {name: (hex, rgb, line_number)} for TdColor entries."""
    colors = {}
    if not os.path.exists(path):
        return colors
    with open(path, "r", encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            m = re.search(r"val\s+(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)", line)
            if m:
                name, argb = m.group(1), m.group(2)
                rgb_hex = argb[2:]
                colors[name] = {"hex": "#" + rgb_hex.upper(), "rgb": hex_to_rgb(rgb_hex),
                                "line": lineno, "alpha": int(argb[:2], 16)}
    return colors


def build_contrast_table(colors, theme_path):
    white = (255, 255, 255)
    pairs = []
    for fg in ("TextHi", "TextMid", "TextLow"):
        for bg in ("Ink0", "Ink1", "Ink2"):
            if fg in colors and bg in colors:
                pairs.append((fg, bg, colors[fg]["rgb"], colors[bg]["rgb"]))
    for fg in ("Cyan", "Mint", "Amber", "Rose"):
        if fg in colors and "Ink2" in colors:
            pairs.append((fg, "Ink2", colors[fg]["rgb"], colors["Ink2"]["rgb"]))

    # Text-bearing gradient buttons use TdGradients.brandButton (see Theme.kt); the
    # pure brand (Violet->Cyan) gradient is decorative (rings/tints) and is measured
    # separately because the brief asked for its endpoints.
    brand_button = []
    try:
        with open(theme_path, "r", encoding="utf-8") as fh:
            text = fh.read()
        m = re.search(
            r"brandButton\s*:\s*Brush\s*=\s*Brush\.linearGradient\(\s*listOf\(\s*Color\(0x([0-9A-Fa-f]{8})\)\s*,\s*Color\(0x([0-9A-Fa-f]{8})\)",
            text)
        if m:
            brand_button = [hex_to_rgb(m.group(1)[2:]), hex_to_rgb(m.group(2)[2:])]
    except OSError:
        pass
    for i, rgb in enumerate(brand_button):
        pairs.append(("White", "brandButton%d" % (i + 1), white, rgb))

    for bg in ("Violet", "Cyan"):
        if bg in colors:
            pairs.append(("White", bg + " (decorative)", white, colors[bg]["rgb"]))

    table = []
    for fg_name, bg_name, fg_rgb, bg_rgb in pairs:
        ratio = contrast_ratio(fg_rgb, bg_rgb)
        if ratio >= 4.5:
            level = "PASS (AA body + large)"
            body_ok, large_ok = True, True
        elif ratio >= 3.0:
            level = "LARGE-ONLY (AA large passes, body fails)"
            body_ok, large_ok = False, True
        else:
            level = "FAIL (below AA large)"
            body_ok, large_ok = False, False
        table.append({
            "fg": fg_name,
            "bg": bg_name,
            "fg_hex": "#" + "".join("%02X" % c for c in fg_rgb),
            "bg_hex": "#" + "".join("%02X" % c for c in bg_rgb),
            "ratio": round(ratio, 2),
            "aa_body_4_5": body_ok,
            "aa_large_3_0": large_ok,
            "level": level,
            "fg_line": colors.get(fg_name, {}).get("line"),
            "bg_line": colors.get(bg_name, {}).get("line"),
        })
    table.sort(key=lambda r: r["ratio"])
    return table


# ---------------------------------------------------------------------------
# Screens
# ---------------------------------------------------------------------------

def audit_screen(xml_path, min_px):
    violations = []
    screen = os.path.splitext(os.path.basename(xml_path))[0]
    try:
        tree = ET.parse(xml_path)
        root = tree.getroot()
    except (ET.ParseError, OSError) as exc:
        return {"screen": screen, "xml": xml_path, "error": "cannot parse XML: %s" % exc,
                "node_count": 0, "clickable_count": 0, "violations": []}

    parent = {child: p for p in root.iter() for child in p}

    def is_clipped(node, own):
        """True when the node's bounds extend outside an ancestor's clip rect.

        uiautomator reports CLIPPED bounds for partially-visible nodes (e.g. the
        lower half of a scrollable card). Those nodes are not defects: the real
        composable may be a full-size button whose remainder is off-screen.
        """
        cur = node
        while cur in parent:
            cur = parent[cur]
            pb = parse_bounds(cur.get("bounds", ""))
            if not pb:
                continue
            if (own["x1"] < pb["x1"] - 2 or own["y1"] < pb["y1"] - 2
                    or own["x2"] > pb["x2"] + 2 or own["y2"] > pb["y2"] + 2):
                return True
        return False

    nodes = list(root.iter("node"))
    clickable = [n for n in nodes if (n.get("clickable") == "true")]
    icon_nodes = [n for n in nodes if is_icon_like(n)]
    clipped_clickables = []

    for node in clickable:
        label = node_label(node)
        b = parse_bounds(node.get("bounds", ""))
        if b and is_clipped(node, b):
            clipped_clickables.append({"node": label, "bounds_px": b,
                                       "note": "partially visible (clipped by an ancestor); "
                                               "R1/R2 skipped because reported bounds are truncated"})
            continue
        desc = (node.get("content-desc") or "").strip()
        own_text = (node.get("text") or "").strip()
        child_text = subtree_text(node)
        if not desc and not own_text and not child_text:
            violations.append({
                "rule": "R1",
                "message": "clickable node has no content-desc and no text (own or child)",
                "node": label,
                "bounds_px": b,
            })
        if b:
            if b["w"] < min_px or b["h"] < min_px:
                violations.append({
                    "rule": "R2",
                    "message": "touch target smaller than 48dp (%.1fdp x %.1fdp, needs >= 48dp)" % (
                        b["w"] / (min_px / 48.0), b["h"] / (min_px / 48.0)),
                    "node": label,
                    "bounds_px": b,
                    "width_dp": round(b["w"] / (min_px / 48.0), 1),
                    "height_dp": round(b["h"] / (min_px / 48.0), 1),
                })

    for node in icon_nodes:
        b = parse_bounds(node.get("bounds", ""))
        if b and is_clipped(node, b):
            continue
        desc = (node.get("content-desc") or "").strip()
        text = (node.get("text") or "").strip()
        if not desc:
            violations.append({
                "rule": "R3",
                "message": "image/icon-like node without content-desc" + ("" if not text else " (has text instead)"),
                "node": node_label(node),
                "bounds_px": b,
            })

    return {
        "screen": screen,
        "xml": xml_path,
        "node_count": len(nodes),
        "clickable_count": len(clickable),
        "icon_like_count": len(icon_nodes),
        "clipped_clickables": clipped_clickables,
        "violations": violations,
    }


def render_summary(report):
    L = []
    L.append("# Accessibility audit - TorBox Android client")
    L.append("")
    L.append("Generated: %s  |  tool: `scripts/a11y-audit.py`  |  density: %s dpi (1dp = %.3fpx, 48dp = %.1fpx)"
             % (report["generated_at"], report["density_dpi"],
                report["px_per_dp"], report["min_touch_px"]))
    L.append("")
    L.append("Screens audited: %d" % len(report["screens"]))
    L.append("Total violations: **%d** %s" % (
        report["summary"]["total_violations"], json.dumps(report["summary"]["by_rule"], sort_keys=True)))
    L.append("Partially-visible (clipped) clickables skipped: %d" % report["summary"].get("clipped_clickables_skipped", 0))
    L.append("")
    L.append("## Violations by screen")
    L.append("")
    L.append("| screen | nodes | clickable | icon-like | R1 (name) | R2 (<48dp) | R3 (icon desc) | clipped skipped |")
    L.append("|---|---|---|---|---|---|---|---|")
    for s in report["screens"]:
        by = {"R1": 0, "R2": 0, "R3": 0}
        for v in s.get("violations", []):
            by[v["rule"]] = by.get(v["rule"], 0) + 1
        L.append("| %s | %d | %d | %d | %d | %d | %d | %d |" % (
            s["screen"], s.get("node_count", 0), s.get("clickable_count", 0),
            s.get("icon_like_count", 0), by["R1"], by["R2"], by["R3"],
            len(s.get("clipped_clickables", []))))
    L.append("")
    if report["summary"].get("clipped_clickables_skipped"):
        L.append("### Clipped clickables (excluded from R1/R2)")
        L.append("")
        for s in report["screens"]:
            for c in s.get("clipped_clickables", []):
                L.append("- %s: bounds=`%s` (reported bounds truncated by an ancestor clip; "
                         "the composable may be full-size when scrolled into view)" % (
                             s["screen"], c["node"]["bounds"]))
        L.append("")
    if report["summary"]["total_violations"]:
        L.append("### Violation detail (exact bounds)")
        L.append("")
        for s in report["screens"]:
            if not s.get("violations"):
                continue
            L.append("#### %s" % s["screen"])
            L.append("")
            for v in s["violations"]:
                n = v["node"]
                b = v.get("bounds_px") or {}
                L.append("- **%s** %s" % (v["rule"], v["message"]))
                L.append("  - class=`%s` id=`%s` text=`%s` desc=`%s`" % (
                    n["class"], n["resource_id"], n["text"], n["content_desc"]))
                L.append("  - bounds=`%s` (%spx x %spx)" % (n["bounds"], b.get("w"), b.get("h")))
            L.append("")
    L.append("## WCAG contrast (TdColor pairs actually used)")
    L.append("")
    L.append("| pair | fg | bg | ratio | AA body (>=4.5) | AA large (>=3.0) | verdict |")
    L.append("|---|---|---|---|---|---|---|")
    for row in report["contrast"]:
        L.append("| %s on %s | `%s` | `%s` | **%.2f:1** | %s | %s | %s |" % (
            row["fg"], row["bg"], row["fg_hex"], row["bg_hex"], row["ratio"],
            "yes" if row["aa_body_4_5"] else "**NO**",
            "yes" if row["aa_large_3_0"] else "**NO**", row["level"]))
    L.append("")
    L.append("Failures for body text (< 4.5:1): %s" % (
        ", ".join("%s on %s (%.2f:1)" % (r["fg"], r["bg"], r["ratio"])
                  for r in report["contrast"] if not r["aa_body_4_5"]) or "none"))
    L.append("")
    L.append("Failures even for large text (< 3:1): %s" % (
        ", ".join("%s on %s (%.2f:1)" % (r["fg"], r["bg"], r["ratio"])
                  for r in report["contrast"] if not r["aa_large_3_0"]) or "none"))
    L.append("")
    L.append("## Method and limitations")
    L.append("")
    L.append("- Bounds, clickable and content-desc come from live `uiautomator dump` XML (real rendered tree).")
    L.append("- R3 icon detection is heuristic: Compose often renders icons as plain `android.view.View`")
    L.append("  with no ImageView class, so the count of detected icon nodes is reported and R3 does not")
    L.append("  claim full coverage of decorative vs meaningful icons.")
    L.append("- Contrast uses WCAG 2.1 relative luminance on opaque sRGB values parsed from `Theme.kt`;")
    L.append("  gradients are only checked at their endpoints (the midpoint of the violet->cyan gradient")
    L.append("  is darker/higher-contrast than the cyan end, so the cyan endpoint is the worst case).")
    L.append("- No app code was modified.")
    L.append("")
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser(description="Mechanical a11y audit + WCAG contrast for the Android client")
    ap.add_argument("--golden-dir", required=True)
    ap.add_argument("--theme", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--density", type=float, default=420.0)
    args = ap.parse_args()

    px_per_dp = args.density / 160.0
    min_px = 48.0 * px_per_dp
    os.makedirs(args.out_dir, exist_ok=True)

    xml_files = sorted(
        os.path.join(args.golden_dir, f) for f in os.listdir(args.golden_dir)
        if f.lower().endswith(".xml")
    )
    screens = [audit_screen(p, min_px) for p in xml_files]
    by_rule = {"R1": 0, "R2": 0, "R3": 0}
    total = 0
    clipped_total = 0
    for s in screens:
        for v in s.get("violations", []):
            by_rule[v["rule"]] = by_rule.get(v["rule"], 0) + 1
            total += 1
        clipped_total += len(s.get("clipped_clickables", []))

    colors = parse_theme(args.theme)
    contrast = build_contrast_table(colors, args.theme)

    report = {
        "generated_at": utcnow(),
        "tool": "scripts/a11y-audit.py",
        "golden_dir": os.path.abspath(args.golden_dir),
        "theme_file": os.path.abspath(args.theme),
        "theme_colors": {k: {"hex": v["hex"], "line": v["line"]} for k, v in colors.items()},
        "density_dpi": args.density,
        "px_per_dp": round(px_per_dp, 4),
        "min_touch_px": round(min_px, 2),
        "checks": CHECK_NAMES,
        "screens": screens,
        "summary": {
            "screens": len(screens),
            "total_violations": total,
            "by_rule": by_rule,
            "clipped_clickables_skipped": clipped_total,
            "contrast_body_failures": [r for r in contrast if not r["aa_body_4_5"]],
            "contrast_large_failures": [r for r in contrast if not r["aa_large_3_0"]],
        },
        "contrast": contrast,
    }
    json_path = os.path.join(args.out_dir, "a11y-report.json")
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
    md_path = os.path.join(args.out_dir, "summary.md")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write(render_summary(report))

    print("screens            : %d" % len(screens))
    print("total violations   : %d  %s" % (total, json.dumps(by_rule, sort_keys=True)))
    print("contrast body <4.5 : %d" % len(report["summary"]["contrast_body_failures"]))
    print("contrast <3.0      : %d" % len(report["summary"]["contrast_large_failures"]))
    print("wrote              : %s" % os.path.abspath(json_path))
    print("wrote              : %s" % os.path.abspath(md_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
