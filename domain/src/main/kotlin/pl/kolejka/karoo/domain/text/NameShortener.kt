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

    private val warsaw = mapOf(
        "Gdańska" to "Gd.",
        "Zachodnia" to "Zach.",
        "Śródmieście" to "Śr.",
        "Śródmieście WKD" to "Śr. WKD",
        "Centralna" to "Centr.",
        "Wileńska" to "Wil.",
        "Wschodnia" to "Wsch.",
        "Główna" to "Gł.",
    )

    /** Stacja warszawska w najkrótszej formie: „Warszawa Gdańska" → „W-wa Gd."; inne bez zmian. */
    fun warsawShort(name: String): String {
        if (!name.startsWith("Warszawa ")) return name
        val core = name.removePrefix("Warszawa ")
        return "W-wa " + (warsaw[core] ?: ellipsize(core, 8))
    }

    /**
     * „Skąd → dokąd" od najdłuższej formy do najkrótszej — renderer bierze pierwszą, która mieści się
     * bez zmniejszania czcionki (uzgodnione 5.11). Najpierw skracamy Warszawę („W-wa Gdańska" →
     * „W-wa Gd."), dopiero potem stację startową („Mińsk Mazowiecki" → „Mińsk Maz." → „Mińsk M…").
     */
    fun fromToVariants(from: String, to: String?): List<String> {
        if (to == null) return listOf(short(from, 99), short(from, 18), short(from, 12)).distinct()
        val toLong = short(to, 12)
        val toShort = short(warsawShort(to), 12)
        val froms = listOf(short(from, 99), short(from, 12), short(from, 9))
        return (listOf("${froms[0]} → $toLong") + froms.map { "$it → $toShort" }).distinct()
    }

    fun ellipsize(s: String, maxLen: Int): String =
        if (s.length <= maxLen) s else s.take((maxLen - 1).coerceAtLeast(1)).trimEnd() + "…"
}
