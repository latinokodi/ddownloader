"""Rewrite Decision A in DECISIONS.md for the owner's correction ("tor" = TorBox).

Run: python scripts/fix_decisions_scope.py
"""
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH = os.path.join(ROOT, "DECISIONS.md")

NEW_A = """## Decision A — **No Tor-network integration** (corrected 2026-10-26 by the project owner)

**Choice: the Android app does not embed Tor, Orbot, Arti or any SOCKS layer.** "tor" in
`TorDownloader`/`TorDownloader PRO` is **TorBox**, the debrid service. This is not a scope
reduction invented here — it is what the original is, and the owner confirmed it explicitly.

### Evidence that fixed the decision

1. **The original contains no onion routing.** `grep -Ei 'socks|onion|torproject|9050|arti'` over
   `electron/` and `src/` returns **zero** hits (`docs/source-analysis.md` §6). Every network call
   in the original is a plain HTTPS request to TorBox, Real-Debrid, TMDB or the Comet add-on.
2. **The owner's correction.** Direct instruction on 2026-10-26: *"this project has nothing to do
   with the onion network, the tor suffix is for torbox."* The brief's Tor requirements
   (embedded-Tor choice, `IsTor` check, "no egress except Tor") were therefore void and were
   removed rather than simulated.
3. **The research still had to be done to be sure.** `docs/research-tor.md` (36.8 KB, primary
   sources) evaluated the three options before the correction, and one finding survived into the
   final build: the Guardian Project **gpmaven mirror is stale** (`latest 0.4.8.16`, 2025-05-16)
   while Maven Central carries `0.4.9.14` (2026-10-09). That finding is why
   `settings.gradle.kts` resolves only from Maven Central — a supply-chain fix retained even
   though the Tor dependency itself was dropped.
4. **A concrete cost that would have mattered.** `tor-android:0.4.9.14` declares
   `minCompileSdk=37.1`, so embedding it forces AGP 9.x + Gradle 9.x + `platforms;android-37`.
   That is a real, measured toolchain jump for a capability the product does not have.

### Options scored *before* the correction (kept for the record)

| criterion (weight) | tor-android (embedded) | Orbot (external app) | Arti (embedded Rust) |
|---|---|---|---|
| Leak control / fail-closed authority (5) | 5 | 2 | 4 |
| Self-contained install (3) | 5 | 1 | 4 |
| Background reliability (4) | 4 | 2 | 4 |
| APK size (2) | 3 (+7.58 MB arm64) | 5 (0 B) | 3 |
| Maintenance (4) | 4 (10 releases/12 mo) | 3 | 1 |
| License (2) | 5 (BSD-3) | 4 | 5 |
| **weighted** | **4.55** | **2.45** | **2.86 (gated)** |

Runner-up **Orbot** lost because it cannot provide a guarantee (separate app, can be disabled by
the user/OEM, `<queries>` visibility, no way to prove our sockets are routed). **Arti** was gated,
not second: its own `doc/Android.md` says you must write the JNI bindings yourself, last touched
2022-04-20, sample project abandoned 2022-08-19.

### What "leak-free" means for the shipped product

With no anonymity layer, the requirement is applied to the data that can actually leak:

- **Credentials**: TorBox/RD tokens + TMDB key are AES-256-GCM encrypted with a non-exportable
  AndroidKeyStore key (`data/CryptoBox.kt`), never rendered back to the UI, and excluded from cloud
  backup *and* device-to-device transfer (`data_extraction_rules.xml`, `allowBackup=false`).
- **Logs**: redacting ring buffer masks `token=`, `api_key=`, `Bearer …`, URL fragments, magnets and
  32+ hex runs (`core/AppLog.kt`), with unit tests. The desktop app leaks its bearer token through
  `JSON.stringify(AxiosError)` — verified in the source.
- **Transport**: HTTPS-only (manifest policy **and** a code-level interceptor that refuses
  cleartext), plus per-redirect-hop allowlist validation for debrid CDNs (`data/Net.kt`).

### Falsifiers

This decision would change only if the product actually acquired an anonymity requirement, or if a
future TorBox feature needed onion access. Neither exists in the source, the API surface or the
owner's description.

---

## Decision A′ (retained) — 16 KB page-size compliance

Independently of Tor, the build is checked against Google's 16 KB page-size requirement:
`zipalign -c -P 16` passes and every packaged `.so` (`libandroidx.graphics.path.so`,
`libdatastore_shared_counter.so`, 4 ABIs) has `PT_LOAD p_align = 0x4000`
(`EVIDENCE/release/artifacts.json`). No 32-bit-only library blocks a 16 KB-page device.

---

"""


def main():
    with io.open(PATH, encoding="utf-8") as f:
        text = f.read()
    start = text.find("## Decision A — Tor integration")
    end = text.find("## Decision B —")
    if start < 0 or end < 0:
        raise SystemExit("could not locate Decision A..B span")
    text = text[:start] + NEW_A + text[end:]

    # D1 was about dropping 32-bit ABIs to avoid the 4 KB libtor; no longer applicable.
    old_d1_start = text.find("### D1 — Ship 64-bit only")
    old_d1_end = text.find("### D2 —")
    if old_d1_start > 0 and old_d1_end > old_d1_start:
        text = text[:old_d1_start] + """### D1 — ABI coverage
The app ships all four ABIs because its only native code is two tiny AndroidX helpers
(`libandroidx.graphics.path.so` 10 KB, `libdatastore_shared_counter.so` 7 KB); both are 16 KB-aligned
on the 64-bit ABIs and `arm64-v8a` is present, which is what Play requires. (An earlier draft
restricted ABIs to drop the non-compliant 32-bit `libtor.so`; that constraint disappeared with
Decision A.)

""" + text[old_d1_end:]

    # Scope paragraph at the top must not claim Tor is additive scope any more.
    text = text.replace(
        """Scope note that governs everything below: **the original app contains no Tor-network code
at all.** In `tordownloaderElectron`, "tor" means *TorBox* (a debrid service). Verified by
grep for `socks|onion|torproject|9050|arti` across `electron/` and `src/`: zero hits
(`docs/source-analysis.md` §6). Tor routing is therefore **additive scope demanded by the
brief**, not a ported feature. That single fact drives Decision A and the size/behaviour deltas in
this document.""",
        """Scope note that governs everything below: **the original app contains no Tor-network code
at all.** In `tordownloaderElectron`, "tor" means *TorBox* (a debrid service). Verified by grep for
`socks|onion|torproject|9050|arti` across `electron/` and `src/`: zero hits
(`docs/source-analysis.md` §6). The brief assumed an onion-routing requirement; the owner corrected
that on 2026-10-26 ("this project has nothing to do with the onion network, the tor suffix is for
torbox"). The port therefore ships **no anonymity layer**, and "leak-free" is applied to credentials,
logs and transport instead — see Decision A.""")

    with io.open(PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("DECISIONS.md updated:", len(text), "bytes")


if __name__ == "__main__":
    main()
