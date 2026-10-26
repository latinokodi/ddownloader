package com.ddownloader.android.storage

/**
 * 1:1 port of `electron/media-layout.ts`.
 *
 * This file deliberately mirrors the original algorithm — same tag list, same
 * regexes, same edge cases (leading numeric titles like "1923", "T2E1" latino
 * notation, trailing ALL-CAPS release groups). It is covered by a differential
 * unit test that feeds an identical corpus through the original TypeScript
 * implementation and this port and asserts equal output
 * (`app/src/test/java/.../MediaLayoutParityTest.kt` + `scripts/parity-media-layout.js`).
 */
object MediaLayout {

    data class ParsedRelease(val title: String, val year: Int?, val season: Int?)

    // Exactly the electron/media-layout.ts TAG_RE list, order preserved.
    private val TAGS = listOf(
        "1080p", "720p", "2160p", "4k", "480p", "576p", "hdtv", "webrip", "web-dl",
        "webdl", "web", "bluray", "blu-ray", "brrip", "h264", "h265", "x264", "x265",
        "hevc", "avc", "aac", "ac3", "dts", "ddp5", "ddp5.1", "dd5", "dd5.1",
        "atmos", "truehd", "amzn", "atvp", "nf", "hbo", "amazon", "itunes", "vff",
        "vostfr", "multi", "dual", "lat", "latino", "spanish", "english", "dl",
        "extreme", "weeds", "cakes", "megusta", "ethel", "grace", "ntb", "tgx",
        "rarbg", "eztv", "rartv", "ion10", "glhf", "rmteam", "playweb", "ditr",
        "skst", "dkv", "dirt", "msd", "xvid", "proper", "repack", "remux", "internal",
        "imax", "muxed", "opus", "flac", "aac2", "2.0", "5.1", "7.1",
    )

    // NOTE: no 'g' flag semantics needed in Kotlin — Regex.test() is stateless per call.
    private val TAG_REGEX = Regex(TAGS.joinToString("|"), RegexOption.IGNORE_CASE)
    private val SEASON_EP_REGEX = Regex("\\bS(\\d{1,2})[Ee](\\d{1,3})\\b")
    private val TEMP_EP_REGEX = Regex("\\bT(\\d{1,2})[Ee](\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    private val YEAR_REGEX = Regex("\\b(19\\d{2}|20\\d{2})\\b")
    private val PAREN_YEAR_REGEX = Regex("[\\[\\ (]\\s*(19\\d{2}|20\\d{2})\\s*[\\]\\)]")
    private val GROUP_REGEX = Regex("^[A-Z][A-Z0-9]{3,7}$")

    private val STOP_WORDS = emptySet<String>() // kept for clarity: original has none here

    private fun cleanToken(token: String): String =
        token.replace(Regex("[._\\-\\[\\](){}]"), " ").replace(Regex("\\s+"), " ").trim()

    fun parseReleaseName(name: String): ParsedRelease {
        val raw = name

        var season: Int? = null
        var year: Int? = null

        val seMatch = SEASON_EP_REGEX.find(raw) ?: TEMP_EP_REGEX.find(raw)
        if (seMatch != null) season = seMatch.groupValues[1].toInt()

        val yearMatches = YEAR_REGEX.findAll(raw).toList()
        if (yearMatches.isNotEmpty()) {
            val afterEp = if (seMatch != null) yearMatches.filter { it.range.first > seMatch.range.first } else yearMatches
            val pool = if (afterEp.isNotEmpty()) afterEp else yearMatches
            year = pool.last().groupValues[1].toInt()
        }

        var cutIdx = -1
        if (seMatch != null) {
            cutIdx = seMatch.range.first
        } else {
            val firstYearNotLeading = yearMatches.firstOrNull { it.range.first > 0 }
            if (firstYearNotLeading != null) cutIdx = firstYearNotLeading.range.first
        }
        var titlePart = if (cutIdx > 0) raw.substring(0, cutIdx) else raw
        titlePart = PAREN_YEAR_REGEX.replace(titlePart, " ")

        val tokens = titlePart
            .split(Regex("[._\\-\\[\\](){} ]+"))
            .map { cleanToken(it) }
            .filter { it.isNotEmpty() }

        val startsWithNumericTitle = Regex("^\\d{4}").containsMatchIn(raw.trim())

        val titleTokens = mutableListOf<String>()
        tokens.forEachIndexed { i, t ->
            if (TAG_REGEX.containsMatchIn(t)) return@forEachIndexed
            if (i == 0 && startsWithNumericTitle && Regex("^\\d{4}$").matches(t)) {
                titleTokens += t
                return@forEachIndexed
            }
            if (t.replace(Regex("\\d+$"), "").length <= 1) return@forEachIndexed
            titleTokens += t
        }

        while (titleTokens.size > 1 && GROUP_REGEX.matches(titleTokens.last())) {
            titleTokens.removeAt(titleTokens.lastIndex)
        }

        val title = titleTokens.joinToString(" ").ifBlank { cleanToken(raw).take(60) }.ifBlank { "Unknown" }
        return ParsedRelease(title = title, year = year, season = season)
    }

    /**
     * movie  -> <movies_root>/<Title (Year)>
     * series -> <series_root>/<Title (Year)>/Season NN
     *
     * `tmdb` is the optional pre-resolved canonical title/year (the Electron app
     * calls TMDB here; the port passes the already-resolved value in, so this
     * function stays pure and testable).
     */
    fun computeDestination(
        kind: Kind,
        torrentName: String,
        tmdb: Pair<String, Int?>? = null,
        seasonOverride: Int? = null,
    ): Destination {
        val parsed = parseReleaseName(torrentName)
        val title = tmdb?.first?.takeIf { it.isNotBlank() } ?: parsed.title
        val year = tmdb?.second ?: parsed.year
        val titleFolder = if (year != null) "$title ($year)" else title

        return when (kind) {
            Kind.MOVIE -> Destination(titleFolder, listOf(titleFolder), null)
            Kind.SERIES -> {
                val seasonOnly = Regex("\\bS(\\d{1,2})\\b").find(torrentName)?.groupValues?.get(1)?.toInt()
                val season = seasonOverride ?: parsed.season ?: seasonOnly ?: 1
                val folder = "$titleFolder/Season ${season.toString().padStart(2, '0')}"
                Destination(folder, folder.split("/"), season)
            }
        }
    }

    enum class Kind { MOVIE, SERIES }

    data class Destination(val relativePath: String, val segments: List<String>, val season: Int?)

    /** Sanitize a path segment for filesystem use (parity: safeSegment). */
    fun safeSegment(value: String): String {
        val cleaned = value
            .replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_")
            .trim()
            .replace(Regex("^\\.+"), "")
            .replace(Regex("\\.+$"), "")
        return cleaned.ifEmpty { "Unknown" }
    }

    /** Parity with worker.ts getSafePathPart. */
    fun safePathPart(value: String): String {
        val cleaned = value
            .replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_")
            .trim()
            .replace(Regex("^\\.+|\\.+$"), "")
        return cleaned.ifEmpty { "Unknown" }
    }
}
