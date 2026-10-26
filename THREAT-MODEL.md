# Threat model (STRIDE)

Produced with the `threat-modeling` skill. Scope: the Android app as shipped (APK from GitHub
Releases), its data at rest, its network peers, and its **self-update channel** — the newest and
highest-value trust boundary in the app.

## Assets

| asset | why it matters |
|---|---|
| TorBox / Real-Debrid API tokens | full account access (downloads, account deletion) |
| TMDB API key | low value, billing/quota only |
| Download queue + partial files | user activity; content may be sensitive |
| Update channel (release feed + APK) | code execution on the device |
| Signing key (`keystore/release.jks`) | authority to sign updates — **not in the repo** |

## Trust boundaries

1. **Device ↔ providers** (TorBox, Real-Debrid, TMDB, Comet, GitHub) — all HTTPS.
2. **App ↔ system installer** — via FileProvider + `ACTION_VIEW`, user confirms.
3. **App ↔ SAF tree** (user-picked folder) — persisted per-URI grant, no broad storage permission.
4. **App ↔ Android Keystore** — non-exportable AES-256-GCM key.
5. **CI / build machine ↔ release** — the keystore never leaves the local machine.

## STRIDE

| threat | concrete scenario | control (where) | residual risk |
|---|---|---|---|
| **S**poofing | Malicious release published in the repo or a MITM serves a fake APK | Signer-equality check against the installed app's certificate before invoking the installer (`UpdateInstaller.verifierCheck`); Android also refuses mismatched signers | A compromised repo **and** stolen signing key would pass — out of scope |
| **T**ampering | Truncated/corrupted update download | `Content-Length` check + optional `app-release.apk.sha256` asset + full-file SHA-256 computed and shown | If no `.sha256` asset is published, only transport + signer protect it (documented in `RELEASING.md`) |
| **T**ampering | Redirect to an attacker host during download | Per-hop host allowlist in `Net.follow` (github.com / *.githubusercontent.com only) | — |
| **R**epudiation | User claims an update was forced | Installer always requires explicit user confirmation; no silent install path | — |
| **I**nformation disclosure | Tokens in logs / backups / plaintext DB | Redacting logger with tests; Keystore-encrypted values; `allowBackup=false` + extraction rules; no token in UI after save | Rooted device with malware in the same UID |
| **I**nformation disclosure | Cleartext HTTP leaks a token | `usesCleartextTraffic=false` + code interceptor; loopback/emulator-fixture only in debug/verify | — |
| **D**enial of service | Update check spams GitHub or blocks the UI | 6 h throttle, manual override, all failures degrade to “check failed”, never blocks downloads | GitHub rate limit → check fails gracefully |
| **D**enial of service | Attacker-triggered unbounded download/disk fill | Expected size enforced; partial files pruned when orphaned (`Storage.pruneOrphans`) | User-chosen URL can still be large by intent |
| **E**levation of privilege | Malicious `tddl://` deep link enqueues downloads | Filter exists only in debug/verify; `handleLaunchIntent` checks `BuildConfig.DEBUG/VERIFY_HARNESS`; release manifest has no filter (asserted by the artifact script) | — |
| **E**levation of privilege | Path traversal writing outside the chosen folder | `MediaLayout.safePathPart` on every path segment; SAF writes only inside the picked tree | — |
| **E**levation of privilege | SQL injection in the queue DB | All writes via `ContentValues`/bound args; no string-built SQL | — |
| **E**levation of privilege | Update installs a different application | `archive.packageName == context.packageName` check plus signer equality | — |

## Out of scope / explicitly not claimed

- No protection against a rooted device or a hostile accessibility service.
- No Tor/anonymity layer (by product definition — this is a debrid client).
- The debrid providers' own security posture is not assessed.
