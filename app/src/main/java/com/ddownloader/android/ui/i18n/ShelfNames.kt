package com.ddownloader.android.ui.i18n

/**
 * TMDB row titles come from `TmdbApi.rowSpecs` as stable English keys. Translating them here keeps
 * the API layer free of UI concerns and avoids a per-locale `Strings` field for every genre.
 */
object ShelfNames {

    fun shelfTitle(language: Language, englishTitle: String): String {
        val es = language == Language.ES
        return when (englishTitle) {
            "Trending this week" -> if (es) "Tendencias de la semana" else englishTitle
            "Popular movies" -> if (es) "Películas populares" else englishTitle
            "Popular series" -> if (es) "Series populares" else englishTitle
            "Top rated movies" -> if (es) "Mejor valoradas" else englishTitle
            "Top rated series" -> if (es) "Mejor valoradas" else englishTitle
            "In cinemas" -> if (es) "En cines" else englishTitle
            "Coming soon" -> if (es) "Próximos estrenos" else englishTitle
            "Airing today" -> if (es) "Hoy en emisión" else englishTitle
            else -> genre(language, englishTitle)
        }
    }

    private fun genre(language: Language, english: String): String {
        if (language != Language.ES) return english
        return when (english) {
            "Action" -> "Acción"
            "Adventure" -> "Aventura"
            "Animation" -> "Animación"
            "Comedy" -> "Comedia"
            "Crime" -> "Crimen"
            "Documentary" -> "Documental"
            "Drama" -> "Drama"
            "Family" -> "Familia"
            "Fantasy" -> "Fantasía"
            "History" -> "Historia"
            "Horror" -> "Terror"
            "Music" -> "Música"
            "Mystery" -> "Misterio"
            "Romance" -> "Romance"
            "Science Fiction" -> "Ciencia ficción"
            "Sci-Fi & fantasy" -> "Ciencia ficción y fantasía"
            "Thriller" -> "Suspense"
            "War" -> "Guerra"
            "Western" -> "Western"
            "Reality" -> "Reality"
            else -> english
        }
    }
}
