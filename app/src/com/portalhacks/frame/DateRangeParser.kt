package com.portalhacks.frame

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Intelligent parser for date ranges, months, years, and vacation time spans.
 *
 * Used for Date-Range Showcases on shared albums (e.g. Google Photos, iCloud),
 * allowing deterministic filtering based on exact photo capture timestamps (`timeMs`)
 * without relying on cloud-stripped EXIF GPS metadata.
 */
object DateRangeParser {

    data class ParsedRange(
        val startMs: Long,
        val endMs: Long,
        val label: String,
    )

    private val MONTH_NAMES = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"
    )
    private val MONTH_ABBR = listOf(
        "jan", "feb", "mar", "apr", "may", "jun",
        "jul", "aug", "sep", "oct", "nov", "dec"
    )

    /**
     * Parses a date expression from a showcase tag or broadcast text.
     * Returns [ParsedRange] with start/end epoch millis and a display label, or null.
     */
    fun parse(input: String?): ParsedRange? {
        if (input.isNullOrBlank()) return null
        val clean = input.trim().lowercase(Locale.US)

        // 1. Exact Date Span: YYYY-MM-DD..YYYY-MM-DD or YYYY-MM-DD to YYYY-MM-DD or YYYY-MM-DD_YYYY-MM-DD
        val spanRegex = Regex("""\b(\d{4}-\d{2}-\d{2})\s*(?:\.\.|__|_|to|-)\s*(\d{4}-\d{2}-\d{2})\b""")
        val spanMatch = spanRegex.find(clean)
        if (spanMatch != null) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val start = sdf.parse(spanMatch.groupValues[1])?.time ?: return null
                val end = (sdf.parse(spanMatch.groupValues[2])?.time ?: return null) + 86400000L - 1L
                val label = "${spanMatch.groupValues[1]} to ${spanMatch.groupValues[2]}"
                return ParsedRange(start, end, label)
            } catch (_: Exception) {}
        }

        // 2. Month and Year: YYYY-MM or YYYY_MM (e.g. 2024-09, 2023-10)
        val yearMonthRegex = Regex("""\b(\d{4})[-_](\d{1,2})\b""")
        val ymMatch = yearMonthRegex.find(clean)
        if (ymMatch != null) {
            val year = ymMatch.groupValues[1].toIntOrNull() ?: return null
            val month = ymMatch.groupValues[2].toIntOrNull() ?: return null
            if (month in 1..12 && year in 2000..2099) {
                return buildMonthRange(year, month)
            }
        }

        // 3. Natural Month Name + Year: e.g. "September 2024", "Sep 2024", "Jul 2023"
        for (i in 0 until 12) {
            val mName = MONTH_NAMES[i]
            val mAbbr = MONTH_ABBR[i]
            val pattern = Regex("""\b(?:$mName|$mAbbr)\s*(\d{4})\b""")
            val m = pattern.find(clean)
            if (m != null) {
                val year = m.groupValues[1].toIntOrNull() ?: continue
                if (year in 2000..2099) {
                    return buildMonthRange(year, i + 1)
                }
            }
        }

        // 4. Year only: YYYY (e.g. 2024, 2023, 2022)
        val yearOnlyRegex = Regex("""\b(20[12]\d)\b""")
        val yMatch = yearOnlyRegex.find(clean)
        if (yMatch != null) {
            val year = yMatch.groupValues[1].toIntOrNull() ?: return null
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(Calendar.YEAR, year)
            }
            val start = cal.timeInMillis
            cal.set(Calendar.MONTH, Calendar.DECEMBER)
            cal.set(Calendar.DAY_OF_MONTH, 31)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            return ParsedRange(start, end, "$year")
        }

        return null
    }

    private fun buildMonthRange(year: Int, month1Based: Int): ParsedRange {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month1Based - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val start = cal.timeInMillis
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, maxDay)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis
        val monthLabel = MONTH_NAMES[month1Based - 1].replaceFirstChar { it.uppercase() }
        return ParsedRange(start, end, "$monthLabel $year")
    }
}
