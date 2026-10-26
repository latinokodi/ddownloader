"""Compile-error fixes for the first full build.

Run: python scripts/fix_compile_errors.py
"""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "pro", "tordownloader", "android")


def edit(rel, pairs, required=True):
    path = os.path.join(JAVA, rel)
    with io.open(path, encoding="utf-8") as f:
        text = f.read()
    for old, new in pairs:
        if old not in text:
            if required:
                print("  MISS %s :: %r" % (rel, old[:60]))
            continue
        text = text.replace(old, new)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("  edited", rel)


# 1) CryptoBox: encrypt returns non-null String so it can go straight into DataStore
edit("data/CryptoBox.kt", [
    ("    /** Encrypts [plaintext]; returns null for blank input so \"unset\" stays unset. */\n"
     "    fun encrypt(plaintext: String?): String? {\n"
     "        if (plaintext.isNullOrEmpty()) return null",
     "    /** Encrypts [plaintext]; empty string means \"no value\" (blank input stays blank). */\n"
     "    fun encrypt(plaintext: String?): String {\n"
     "        if (plaintext.isNullOrEmpty()) return \"\""),
    ("        } catch (t: Throwable) {\n            null\n        }\n    }\n\n    /** Decrypts a value produced by [encrypt]; returns null when absent or corrupt. */",
     "        } catch (t: Throwable) {\n            \"\"\n        }\n    }\n\n    /** Decrypts a value produced by [encrypt]; returns null when absent or corrupt. */"),
    ("        val enc = encrypt(probe) ?: return false", "        val enc = encrypt(probe)"),
])

# 2) Debrid APIs: token reads are not suspend; drop the suspend modifier
for api, tok in (("api/TorBoxApi.kt", "torboxToken"), ("api/RealDebridApi.kt", "realdebridToken")):
    edit(api, [
        ("    private suspend fun token(): String = settings.current().%s" % tok,
         "    private fun token(): String = settings.settings.value.%s" % tok),
        ("    private suspend fun auth(): Map<String, String> = mapOf(\"Authorization\" to \"Bearer ${token()}\")",
         "    private fun auth(): Map<String, String> = mapOf(\"Authorization\" to \"Bearer ${token()}\")"),
    ], required=False)

# 3) DownloadManager: subfolder must be a String, not a List
edit("download/DownloadManager.kt", [
    ("""        val subfolder: String = if (kind == null) {
            "" // non-media keeps the flat root
        } else {
            val tmdb = try {
                titleResolver?.resolve(rec.name, kind)
            } catch (t: Throwable) {
                null
            }
            val dest = MediaLayout.computeDestination(kind, rec.name, tmdb, rec.season)
            val base = when (kind) {
                MediaLayout.Kind.MOVIE -> cfg.moviesSubfolder
                MediaLayout.Kind.SERIES -> cfg.seriesSubfolder
            }
            listOf(base) + dest.segments
        }""",
     """        val subfolder: String = if (kind == null) {
            "" // non-media keeps the flat root
        } else {
            val tmdb = try {
                titleResolver?.resolve(rec.name, kind)
            } catch (t: Throwable) {
                null
            }
            val dest = MediaLayout.computeDestination(kind, rec.name, tmdb, rec.season)
            val base = when (kind) {
                MediaLayout.Kind.MOVIE -> cfg.moviesSubfolder
                MediaLayout.Kind.SERIES -> cfg.seriesSubfolder
            }
            (listOf(base) + dest.segments).joinToString("/")
        }"""),
])

# 4) DownloadService: refreshNotification is not a suspending function
edit("download/DownloadService.kt", [
    ("    private suspend fun refreshNotification() {", "    private fun refreshNotification() {"),
])

# 5) Components: no Lock glyph in the curated icon set
edit("ui/components/Components.kt", [
    ('StatusPill("HTTPS only", TdColor.Cyan, AppIcons.Lock)',
     'StatusPill("HTTPS only", TdColor.Cyan, AppIcons.Shield)'),
])

# 6) Theme: an extension property needs a *type* receiver
edit("ui/theme/Theme.kt", [
    ("/** Metric text (speeds, percentages) — mono so digits don't dance. */\n"
     "val TdTypography.monoNumber: TextStyle\n"
     "    get() = TextStyle(fontFamily = JBMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp)",
     "/** Metric text (speeds, percentages) — mono so digits don't dance. */\n"
     "val Typography.monoNumber: TextStyle\n"
     "    get() = TextStyle(fontFamily = JBMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp)"),
])
print("done")
