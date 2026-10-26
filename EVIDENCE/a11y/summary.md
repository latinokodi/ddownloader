# Accessibility audit - TorBox Android client

Generated: 2026-10-26T15:10:04Z  |  tool: `scripts/a11y-audit.py`  |  density: 420.0 dpi (1dp = 2.625px, 48dp = 126.0px)

Screens audited: 5
Total violations: **0** {"R1": 0, "R2": 0, "R3": 0}
Partially-visible (clipped) clickables skipped: 3

## Violations by screen

| screen | nodes | clickable | icon-like | R1 (name) | R2 (<48dp) | R3 (icon desc) | clipped skipped |
|---|---|---|---|---|---|---|---|
| discover | 28 | 6 | 0 | 0 | 0 | 0 | 0 |
| downloads | 53 | 9 | 0 | 0 | 0 | 0 | 0 |
| logs | 58 | 11 | 0 | 0 | 0 | 0 | 0 |
| search | 49 | 11 | 0 | 0 | 0 | 0 | 0 |
| settings | 60 | 14 | 0 | 0 | 0 | 0 | 3 |

### Clipped clickables (excluded from R1/R2)

- settings: bounds=`[89,1886][991,2054]` (reported bounds truncated by an ancestor clip; the composable may be full-size when scrolled into view)
- settings: bounds=`[89,2059][298,2075]` (reported bounds truncated by an ancestor clip; the composable may be full-size when scrolled into view)
- settings: bounds=`[319,2059][535,2075]` (reported bounds truncated by an ancestor clip; the composable may be full-size when scrolled into view)

## WCAG contrast (TdColor pairs actually used)

| pair | fg | bg | ratio | AA body (>=4.5) | AA large (>=3.0) | verdict |
|---|---|---|---|---|---|---|
| White on Cyan (decorative) | `#FFFFFF` | `#22D3EE` | **1.81:1** | **NO** | **NO** | FAIL (below AA large) |
| White on Violet (decorative) | `#FFFFFF` | `#7C5CFF` | **4.35:1** | **NO** | yes | LARGE-ONLY (AA large passes, body fails) |
| White on brandButton1 | `#FFFFFF` | `#6D4AE0` | **5.67:1** | yes | yes | PASS (AA body + large) |
| TextLow on Ink2 | `#8590A3` | `#10131B` | **5.76:1** | yes | yes | PASS (AA body + large) |
| TextLow on Ink1 | `#8590A3` | `#0A0C12` | **6.07:1** | yes | yes | PASS (AA body + large) |
| TextLow on Ink0 | `#8590A3` | `#05060A` | **6.28:1** | yes | yes | PASS (AA body + large) |
| White on brandButton2 | `#FFFFFF` | `#4F46E5` | **6.29:1** | yes | yes | PASS (AA body + large) |
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

Failures for body text (< 4.5:1): White on Cyan (decorative) (1.81:1), White on Violet (decorative) (4.35:1)

Failures even for large text (< 3:1): White on Cyan (decorative) (1.81:1)

## Method and limitations

- Bounds, clickable and content-desc come from live `uiautomator dump` XML (real rendered tree).
- R3 icon detection is heuristic: Compose often renders icons as plain `android.view.View`
  with no ImageView class, so the count of detected icon nodes is reported and R3 does not
  claim full coverage of decorative vs meaningful icons.
- Contrast uses WCAG 2.1 relative luminance on opaque sRGB values parsed from `Theme.kt`;
  gradients are only checked at their endpoints (the midpoint of the violet->cyan gradient
  is darker/higher-contrast than the cyan end, so the cyan endpoint is the worst case).
- No app code was modified.
