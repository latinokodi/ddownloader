# Versioning

Applied from the `semantic-versioning` skill. Two numbers must move together.

## Rules

| field | rule |
|---|---|
| `versionName` | SemVer `MAJOR.MINOR.PATCH`, matching the git tag with a `v` prefix (`v1.8.7`) |
| `versionCode` | **monotonic integer, +1 per release** — never reused, never decreased |
| git tag | `v<versionName>` on the commit that produced the release APK |

### Why versionCode is sequential, not arithmetic

An earlier revision of this document specified
`MAJOR * 10000 + MINOR * 100 + PATCH`. That formula was never applied: the shipped
sequence went `1.0.0 → 1`, `1.0.1 → 2`, and then +1 per release (44 = `1.8.7`,
45 = `1.8.8`). Switching to the formula now would break the only invariant Android
actually requires — that each release is **greater than the last** — because
`1.8.8` under the formula is `10808`, which is a jump, and any future patch revert
would be lower than a build already installed. The sequential scheme is kept.

`PATCH` = fixes only (behaviour identical, no new settings). `MINOR` = new features,
backwards compatible with existing settings/data. `MAJOR` = settings or data migration
required (the self-updater's in-place install then needs an explicit migration path).

**Never** change `applicationId` or the signing key inside a MAJOR: both are what make an
in-place update preserve data and pass the updater's signature check.

## Current

| versionName | versionCode | note |
|---|---|---|
| 1.0.0 | 1 | first public build (release v1.0.0) |
| 1.0.1 | 2 | token-auth validation, first-run assistant, self-update, DDownloader rename |
| 1.8.7 | 44 | last release produced by hand |
| 1.8.8 | 45 | DNS fallback + retry when linking debrid; first release published by CI |

## Bumping (checklist)

1. Edit `app/build.gradle.kts`: `versionCode` (+1) and `versionName`.
2. Add the entry to `CHANGELOG.md` under a new `## [x.y.z] - YYYY-MM-DD`.
3. Push to `main`. `.github/workflows/release.yml` tags the commit `vx.y.z`, builds,
   verifies the signature and publishes the APK with its `.sha256` sidecar.
   Pushing without a bump fails the workflow on purpose.
4. The in-app updater only offers a build it can verify: the SHA-256 is optional, the
   signer match is mandatory.
