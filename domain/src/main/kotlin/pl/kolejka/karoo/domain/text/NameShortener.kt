package pl.kolejka.karoo.domain.text

/** Skróty nazw stacji na kafelek (SPEC 7.1). */
object NameShortener {
    private val replacements = listOf(
        Regex("^Warszawa\\b") to "W-wa",
        Regex("\\bMazowiecki(ego)?\\b") to "Maz.",
        Regex("\\bMazowiecka\\b") to "Maz.",
        Regex("\\bGłówn(y|a)\\b") to "Gł.",
        Regex("\\bZachodni(a)?\\b") to "Zach.",
        Regex("\\bWschodni(a)?\\b") to "Wsch.",
        Regex("\\bŚródmieście\\b") to "Śródm.",
    )

    /** Skraca standardowe człony; gdy dalej za długo — obcina i dodaje „…". */
    fun short(name: String, maxLen: Int): String {
        var s = name
        for ((re, rep) in replacements) {
            if (s.length <= maxLen) break
            s = s.replace(re, rep)
        }
        return ellipsize(s, maxLen)
    }

    /**
     * Wariant „mały" (~5 znaków): pierwszy znaczący człon nazwy, bez „Warszawa"
     * („Warszawa Gdańska" → „Gdań.", „Mińsk Mazowiecki" → „Mińsk").
     */
    fun tiny(name: String, maxLen: Int = 5): String {
        val words = name.split(' ').filter { it.isNotBlank() }
        val core = if (words.size > 1 && words[0] == "Warszawa") words[1] else words.firstOrNull() ?: name
        return if (core.length <= maxLen) core else core.take(maxLen - 1) + "."
    }

    fun ellipsize(s: String, maxLen: Int): String =
        if (s.length <= maxLen) s else s.take((maxLen - 1).coerceAtLeast(1)).trimEnd() + "…"
}
