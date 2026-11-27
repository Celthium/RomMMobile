package com.rommmobile.app.core.util

/**
 * Region and language names as RomM stores them (No-Intro style: "USA", "Europe", "Japan",
 * "World", "En", "Fr"...) turned into the flag emoji the v2 web cards show. Anything unknown
 * comes back null and the caller prints the short code instead.
 */
object Flags {
    private val REGIONS = mapOf(
        "usa" to "🇺🇸", "us" to "🇺🇸", "u" to "🇺🇸", "america" to "🇺🇸", "north america" to "🇺🇸",
        "europe" to "🇪🇺", "eu" to "🇪🇺", "eur" to "🇪🇺", "e" to "🇪🇺",
        "japan" to "🇯🇵", "jp" to "🇯🇵", "jpn" to "🇯🇵", "j" to "🇯🇵",
        "world" to "🌍", "w" to "🌍",
        "france" to "🇫🇷", "fr" to "🇫🇷", "germany" to "🇩🇪", "de" to "🇩🇪", "spain" to "🇪🇸", "es" to "🇪🇸",
        "italy" to "🇮🇹", "it" to "🇮🇹", "uk" to "🇬🇧", "united kingdom" to "🇬🇧", "gb" to "🇬🇧", "en" to "🇬🇧",
        "korea" to "🇰🇷", "kr" to "🇰🇷", "ko" to "🇰🇷", "china" to "🇨🇳", "cn" to "🇨🇳", "zh" to "🇨🇳",
        "taiwan" to "🇹🇼", "tw" to "🇹🇼", "hong kong" to "🇭🇰", "hk" to "🇭🇰",
        "brazil" to "🇧🇷", "br" to "🇧🇷", "pt" to "🇵🇹", "portugal" to "🇵🇹",
        "australia" to "🇦🇺", "au" to "🇦🇺", "canada" to "🇨🇦", "ca" to "🇨🇦",
        "netherlands" to "🇳🇱", "nl" to "🇳🇱", "sweden" to "🇸🇪", "sv" to "🇸🇪", "se" to "🇸🇪",
        "russia" to "🇷🇺", "ru" to "🇷🇺", "asia" to "🌏", "scandinavia" to "🇸🇪",
        "denmark" to "🇩🇰", "da" to "🇩🇰", "norway" to "🇳🇴", "no" to "🇳🇴", "finland" to "🇫🇮", "fi" to "🇫🇮",
        "poland" to "🇵🇱", "pl" to "🇵🇱", "greece" to "🇬🇷", "el" to "🇬🇷",
    )

    fun of(value: String): String? = REGIONS[value.trim().lowercase()]

    /** Flags for [values], unknown ones as their first three letters, at most [max] of them. */
    fun chips(values: List<String>, max: Int = 3): List<String> =
        values.filter { it.isNotBlank() }.map { of(it) ?: it.uppercase().take(3) }.distinct().take(max)
}
