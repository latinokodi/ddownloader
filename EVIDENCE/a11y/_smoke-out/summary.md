# Accessibility audit - TorBox Android client

Generated: 2026-10-26T14:48:33Z  |  tool: `scripts/a11y-audit.py`  |  density: 420.0 dpi (1dp = 2.625px, 48dp = 126.0px)

Screens audited: 2
Total violations: **2** {"R1": 2, "R2": 0, "R3": 0}

## Violations by screen

| screen | nodes | clickable | icon-like | R1 (name) | R2 (<48dp) | R3 (icon desc) |
|---|---|---|---|---|---|---|
| debug-main | 49 | 11 | 0 | 1 | 0 | 0 |
| search | 49 | 11 | 0 | 1 | 0 | 0 |

### Violation detail (exact bounds)

#### debug-main

- **R1** clickable node has no content-desc and no text (own or child)
  - class=`android.view.View` id=`` text=`` desc=``
  - bounds=`[934,283][1060,409]` (126px x 126px)

#### search

- **R1** clickable node has no content-desc and no text (own or child)
  - class=`android.view.View` id=`` text=`` desc=``
  - bounds=`[934,283][1060,409]` (126px x 126px)

## WCAG contrast (TdColor pairs actually used)

| pair | fg | bg | ratio | AA body (>=4.5) | AA large (>=3.0) | verdict |
|---|---|---|---|---|---|---|
| White on Cyan | `#FFFFFF` | `#22D3EE` | **1.81:1** | **NO** | **NO** | FAIL (below AA large) |
| TextLow on Ink2 | `#5F6A7D` | `#10131B` | **3.40:1** | **NO** | yes | LARGE-ONLY (AA large passes, body fails) |
| TextLow on Ink1 | `#5F6A7D` | `#0A0C12` | **3.58:1** | **NO** | yes | LARGE-ONLY (AA large passes, body fails) |
| TextLow on Ink0 | `#5F6A7D` | `#05060A` | **3.71:1** | **NO** | yes | LARGE-ONLY (AA large passes, body fails) |
| White on Violet | `#FFFFFF` | `#7C5CFF` | **4.35:1** | **NO** | yes | LARGE-ONLY (AA large passes, body fails) |
| Rose on Ink2 | `#F47171` | `#10131B` | **6.58:1** | yes | yes | PASS (AA body + large) |
| TextMid on Ink2 | `#9BA5B7` | `#10131B` | **7.48:1** | yes | yes | PASS (AA body + large) |
| TextMid on Ink1 | `#9BA5B7` | `#0A0C12` | **7.87:1** | yes | yes | PASS (AA body + large) |
| TextMid on Ink0 | `#9BA5B7` | `#05060A` | **8.16:1** | yes | yes | PASS (AA body + large) |
| Mint on Ink2 | `#34D399` | `#10131B` | **9.66:1** | yes | yes | PASS (AA body + large) |
| Cyan on Ink2 | `#22D3EE` | `#10131B` | **10.27:1** | yes | yes | PASS (AA body + large) |
| Amber on Ink2 | `#FBBF24` | `#10131B` | **11.12:1** | yes | yes | PASS (AA body + large) |
| TextHi on Ink2 | `#F3F4F8` | `#10131B` | **16.89:1** | yes | yes | PASS (AA body + large) |
| TextHi on Ink1 | `#F3F4F8` | `#0A0C12` | **17.79:1** | yes | yes | PASS (AA body + large) |
| TextHi on Ink0 | `#F3F4F8` | `#05060A` | **18.43:1** | yes | yes | PASS (AA body + large) |

Failures for body text (< 4.5:1): White on Cyan (1.81:1), TextLow on Ink2 (3.40:1), TextLow on Ink1 (3.58:1), TextLow on Ink0 (3.71:1), White on Violet (4.35:1)

Failures even for large text (< 3:1): White on Cyan (1.81:1)

## Method and limitations

- Bounds, clickable and content-desc come from live `uiautomator dump` XML (real rendered tree).
- R3 icon detection is heuristic: Compose often renders icons as plain `android.view.View`
  with no ImageView class, so the count of detected icon nodes is reported and R3 does not
  claim full coverage of decorative vs meaningful icons.
- Contrast uses WCAG 2.1 relative luminance on opaque sRGB values parsed from `Theme.kt`;
  gradients are only checked at their endpoints (the midpoint of the violet->cyan gradient
  is darker/higher-contrast than the cyan end, so the cyan endpoint is the worst case).
- No app code was modified.
