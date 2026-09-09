package com.portalhacks.frame

import java.util.Locale

/**
 * Intelligent location parser for Broadcast Banner Messages.
 *
 * Extracts city/country/region clues from messages like "Our trip to Portugal! 🇵🇹",
 * "Vacation in Lisbon", or "#showcase:portugal" and expands them into a rich set of
 * location terms (e.g. "portugal" -> Lisbon, Porto, Sintra, Faro, Algarve, Madeira, etc.)
 * for matching against photo EXIF locations, captions, and album names.
 */
object LocationExtractor {

    data class ExtractedLocation(
        val primary: String,
        val country: String? = null,
        val terms: Set<String> = emptySet(),
    )

    // Country -> set of famous cities/regions (all lowercase for fast matching)
    private val COUNTRY_DESTINATIONS = mapOf(
        "portugal" to setOf(
            "portugal", "lisbon", "lisboa", "porto", "sintra", "cascais", "faro",
            "algarve", "madeira", "funchal", "azores", "coimbra", "braga", "aveiro",
            "evora", "lagos", "albufeira", "tavira", "belem", "estoril"
        ),
        "spain" to setOf(
            "spain", "espana", "madrid", "barcelona", "seville", "sevilla", "valencia",
            "granada", "malaga", "mallorca", "ibiza", "tenerife", "bilbao", "toledo",
            "cordoba", "marbella", "san sebastian", "andalucia", "catalonia"
        ),
        "france" to setOf(
            "france", "paris", "nice", "lyon", "marseille", "cannes", "bordeaux",
            "provence", "toulouse", "strasbourg", "monaco", "normandy", "chamonix",
            "versailles", "loire", "french riviera", "cote d'azur"
        ),
        "italy" to setOf(
            "italy", "italia", "rome", "roma", "florence", "firenze", "venice",
            "venezia", "milan", "milano", "naples", "napoli", "amalfi", "positano",
            "capri", "tuscany", "toscana", "pisa", "sicily", "bologna", "verona",
            "como", "lake como", "cinque terre", "dolomites"
        ),
        "greece" to setOf(
            "greece", "athens", "santorini", "mykonos", "crete", "rhodes", "corfu",
            "thessaloniki", "zakynthos", "paros", "naxos", "cyclades"
        ),
        "germany" to setOf(
            "germany", "deutschland", "berlin", "munich", "munchen", "frankfurt",
            "hamburg", "cologne", "koln", "bavaria", "heidelberg", "dresden"
        ),
        "united kingdom" to setOf(
            "united kingdom", "uk", "england", "scotland", "wales", "london",
            "edinburgh", "glasgow", "manchester", "liverpool", "oxford", "cambridge",
            "bath", "highlands", "cotswolds", "cornwall"
        ),
        "switzerland" to setOf(
            "switzerland", "zurich", "geneva", "lucerne", "interlaken", "zermatt",
            "bern", "basel", "matterhorn", "lauterbrunnen", "grindelwald", "alps"
        ),
        "austria" to setOf(
            "austria", "vienna", "wien", "salzburg", "innsbruck", "hallstatt", "tyrol"
        ),
        "netherlands" to setOf(
            "netherlands", "holland", "amsterdam", "rotterdam", "the hague", "utrecht"
        ),
        "belgium" to setOf(
            "belgium", "brussels", "bruges", "brugge", "ghent", "antwerp"
        ),
        "ireland" to setOf(
            "ireland", "dublin", "galway", "cork", "killarney", "dingle", "kerry"
        ),
        "croatia" to setOf(
            "croatia", "dubrovnik", "split", "zagreb", "hvar", "zadar", "rovinj"
        ),
        "iceland" to setOf(
            "iceland", "reykjavik", "vik", "golden circle"
        ),
        "norway" to setOf(
            "norway", "oslo", "bergen", "tromso", "tromsø", "lofoten", "geiranger"
        ),
        "sweden" to setOf(
            "sweden", "stockholm", "gothenburg", "malmo"
        ),
        "denmark" to setOf(
            "denmark", "copenhagen"
        ),
        "turkey" to setOf(
            "turkey", "turkiye", "istanbul", "cappadocia", "antalya", "bodrum", "izmir"
        ),
        "japan" to setOf(
            "japan", "tokyo", "kyoto", "osaka", "hiroshima", "sapporo", "fuji",
            "mount fuji", "nara", "shibuya", "shinjuku", "hakone"
        ),
        "india" to setOf(
            "india", "delhi", "new delhi", "mumbai", "goa", "jaipur", "agra",
            "taj mahal", "kerala", "bengaluru", "bangalore", "varanasi", "rajasthan",
            "manali", "ladakh", "shimla", "udaipur", "rishikesh"
        ),
        "thailand" to setOf(
            "thailand", "bangkok", "phuket", "chiang mai", "koh samui", "krabi", "pattaya"
        ),
        "indonesia" to setOf(
            "indonesia", "bali", "ubud", "jakarta", "seminyak", "canggu", "nusa penida", "kuta"
        ),
        "vietnam" to setOf(
            "vietnam", "hanoi", "da nang", "ho chi minh", "saigon", "hoi an", "ha long"
        ),
        "singapore" to setOf(
            "singapore"
        ),
        "australia" to setOf(
            "australia", "sydney", "melbourne", "brisbane", "perth", "gold coast", "cairns"
        ),
        "new zealand" to setOf(
            "new zealand", "auckland", "queenstown", "wellington", "christchurch"
        ),
        "united states" to setOf(
            "united states", "usa", "us", "america", "new york", "nyc", "los angeles",
            "san francisco", "las vegas", "hawaii", "honolulu", "maui", "oahu", "miami",
            "orlando", "chicago", "seattle", "boston", "washington dc", "san diego",
            "yosemite", "grand canyon", "yellowstone", "austin"
        ),
        "canada" to setOf(
            "canada", "toronto", "vancouver", "montreal", "quebec", "banff", "calgary"
        ),
        "mexico" to setOf(
            "mexico", "cancun", "mexico city", "tulum", "cabo", "puerto vallarta", "oaxaca"
        ),
        "brazil" to setOf(
            "brazil", "brasil", "rio de janeiro", "rio", "sao paulo", "salvador"
        ),
        "argentina" to setOf(
            "argentina", "buenos aires", "patagonia", "bariloche", "mendoza"
        ),
        "egypt" to setOf(
            "egypt", "cairo", "giza", "luxor", "sharm el sheikh", "hurghada"
        ),
        "morocco" to setOf(
            "morocco", "marrakech", "marrakesh", "casablanca", "fez", "chefchaouen"
        ),
        "uae" to setOf(
            "united arab emirates", "uae", "dubai", "abu dhabi"
        )
    )

    // Reverse mapping: city/term -> country name
    private val TERM_TO_COUNTRY: Map<String, String> = buildMap {
        for ((country, terms) in COUNTRY_DESTINATIONS) {
            for (term in terms) {
                put(term, country)
            }
        }
    }

    // Sorted by length descending so longer phrases ("rio de janeiro", "san francisco") match first
    private val ALL_TERMS_SORTED_BY_LENGTH: List<String> =
        TERM_TO_COUNTRY.keys.sortedByDescending { it.length }

    /**
     * Extracts a location clue from banner message text.
     * Returns an [ExtractedLocation] with expanded terms for matching, or null if no location found.
     */
    fun extractLocation(text: String?): ExtractedLocation? {
        if (text.isNullOrBlank()) return null

        // 1. Explicit tag syntax e.g. #showcase:portugal or #portugal or #location:lisbon
        val hashMatch = Regex("""#(?:showcase:|location:)?([A-Za-z_-]+)""").find(text)
        if (hashMatch != null) {
            val tag = hashMatch.groupValues[1].replace("_", " ").trim().lowercase(Locale.US)
            if (tag.isNotEmpty() && tag != "all" && tag != "recent" && tag != "trip") {
                return buildLocation(tag)
            }
        }

        // 2. Clean message: lowercase, convert emojis/symbols to spaces
        val clean = text.lowercase(Locale.US)
            .replace(Regex("""[^\p{Alnum}\s']"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (clean.isEmpty()) return null

        // 3. Look for known country/city terms (longest match first)
        for (term in ALL_TERMS_SORTED_BY_LENGTH) {
            val pattern = Regex("""\b${Regex.escape(term)}\b""")
            if (pattern.containsMatchIn(clean)) {
                return buildLocation(term)
            }
        }

        val phraseRegex = Regex("""\b(?:trip to|visiting|visit to|vacation in|holiday in|welcome to|hello from|exploring|touring|in)\s+([A-Za-z]{3,25})""", RegexOption.IGNORE_CASE)
        val phraseMatch = phraseRegex.find(text)
        if (phraseMatch != null) {
            val candidate = phraseMatch.groupValues[1].trim().lowercase(Locale.US)
            val stopWords = setOf("the", "our", "my", "this", "a", "an", "here", "love", "memory", "family", "today")
            if (candidate !in stopWords) {
                return buildLocation(candidate)
            }
        }

        return null
    }

    private fun buildLocation(term: String): ExtractedLocation {
        val country = TERM_TO_COUNTRY[term]
        val terms = mutableSetOf<String>()
        terms.add(term)

        if (country != null) {
            COUNTRY_DESTINATIONS[country]?.let { terms.addAll(it) }
        }

        val primaryName = term.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        val countryName = country?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

        return ExtractedLocation(
            primary = primaryName,
            country = countryName,
            terms = terms,
        )
    }

    /**
     * Checks if a [Slide] matches the extracted location clue.
     * Checks slide's location metadata, caption, and file path/id.
     */
    fun matches(slide: Slide, location: ExtractedLocation): Boolean {
        val locText = slide.location?.lowercase(Locale.US) ?: ""
        val capText = slide.caption?.lowercase(Locale.US) ?: ""
        val idText = slide.id.lowercase(Locale.US)

        for (term in location.terms) {
            if (term.length <= 2) {
                // Short tokens (e.g. "uk", "us") require word boundaries to avoid false positives
                val boundary = Regex("""\b${Regex.escape(term)}\b""")
                if (locText.isNotEmpty() && boundary.containsMatchIn(locText)) return true
                if (capText.isNotEmpty() && boundary.containsMatchIn(capText)) return true
                if (idText.isNotEmpty() && boundary.containsMatchIn(idText)) return true
            } else {
                if (locText.isNotEmpty() && locText.contains(term)) return true
                if (capText.isNotEmpty() && capText.contains(term)) return true
                if (idText.isNotEmpty() && idText.contains(term)) return true
            }
        }
        return false
    }
}
