# Versioning

Applied from the `semantic-versioning` skill. Two numbers must move together, and the
mapping is deliberately arithmetic so a human or CI can compute one from the other.

## Rules

| field | rule |
|---|---|
| `versionName` | SemVer `MAJOR.MINOR.PATCH`, matching the git tag with a `v` prefix (`v1.0.1`) |
| `versionCode` | `MAJOR * 10000 + MINOR * 100 + PATCH` — monotonic, never reused |
| git tag | `v<versionName>` on the commit that produced the release APK |

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

## Bumping (checklist)

1. Edit `app/build.gradle.kts`: `versionCode` and `versionName`.
2. Add the entry to `CHANGELOG.md` under a new `## [x.y.z] - YYYY-MM-DD`.
3. Tag the release commit `vx.y.z` **after** the APK is verified (`RELEASING.md`).
4. Publish the GitHub Release with `app-release.apk` **and** `app-release.apk.sha256`.
   The in-app updater only offers a build it can verify: the SHA-256 is optional, the
   signer match is mandatory.
