package com.ddownloader.android.core

import com.ddownloader.android.ui.i18n.Language

/**
 * Copy for the log stream shown in the Logs screen.
 *
 * Diagnostics are written where the event happens (http, download, service locator), far from the
 * composition, so they cannot read [com.ddownloader.android.ui.i18n.LocalStrings]. This holds the
 * active language instead, updated whenever the setting changes. Pairs are inline because a log
 * template has no reuse outside its call site — a 40-key table would be worse to maintain.
 */
object LogL10n {

    @Volatile
    var language: Language = Language.EN

    fun of(english: String, spanish: String): String =
        if (language == Language.ES) spanish else english
}
