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
        val isSingleDate: Boolean = false,
        val isRecurring: Boolean = false,
        val recurringMonth: Int = 0,
        val recurringDay: Int = 0,
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
            .removePrefix("#")
            .removePrefix("showcase:")
            .removePrefix("date:")
            .removePrefix("location:")
            .trim()

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

        // 2. Exact Single Date: YYYY-MM-DD or YYYY/MM/DD or YYYY_MM_DD (must check before YYYY-MM)
        val singleDateRegex = Regex("""\b(\d{4})[-/_](\d{1,2})[-/_](\d{1,2})\b""")
        val sdMatch = singleDateRegex.find(clean)
        if (sdMatch != null) {
            val year = sdMatch.groupValues[1].toIntOrNull() ?: 0
            val month = sdMatch.groupValues[2].toIntOrNull() ?: 0
            val day = sdMatch.groupValues[3].toIntOrNull() ?: 0
            if (year in 1970..2099 && month in 1..12 && day in 1..31) {
                return buildDayRange(year, month, day)
            }
        }

        // 3. Natural Single Date with Year: e.g. "June 14, 2018", "14 June 2024", "Sep 5 2023"
        for (i in 0 until 12) {
            val mName = MONTH_NAMES[i]
            val mAbbr = MONTH_ABBR[i]
            val patA = Regex("""\b(?:$mName|$mAbbr)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,)?\s+(\d{4})\b""")
            val mA = patA.find(clean)
            if (mA != null) {
                val day = mA.groupValues[1].toIntOrNull() ?: 0
                val year = mA.groupValues[2].toIntOrNull() ?: 0
                if (year in 1970..2099 && day in 1..31) {
                    return buildDayRange(year, i + 1, day)
                }
            }
            val patB = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:$mName|$mAbbr)(?:,)?\s+(\d{4})\b""")
            val mB = patB.find(clean)
            if (mB != null) {
                val day = mB.groupValues[1].toIntOrNull() ?: 0
                val year = mB.groupValues[2].toIntOrNull() ?: 0
                if (year in 1970..2099 && day in 1..31) {
                    return buildDayRange(year, i + 1, day)
                }
            }
        }

        // 4. Recurring Anniversary Date: e.g. "every 07-04", "07-04" (MM-DD)
        val recurringRegex = Regex("""\b(?:every\s+|anniversary\s+)?(\d{1,2})[-/](\d{1,2})\b""")
        val recMatch = recurringRegex.find(clean)
        if (recMatch != null) {
            val month = recMatch.groupValues[1].toIntOrNull() ?: 0
            val day = recMatch.groupValues[2].toIntOrNull() ?: 0
            if (month in 1..12 && day in 1..31) {
                val monthLabel = MONTH_NAMES[month - 1].replaceFirstChar { it.uppercase() }
                return ParsedRange(0L, Long.MAX_VALUE, "$monthLabel $day (Every Year)", isRecurring = true, recurringMonth = month, recurringDay = day)
            }
        }

        // 5. Month and Year: YYYY-MM or YYYY_MM (e.g. 2024-09, 2023-10)
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

    private fun buildDayRange(year: Int, month1Based: Int, day: Int): ParsedRange {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month1Based - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis
        val monthLabel = MONTH_NAMES[month1Based - 1].replaceFirstChar { it.uppercase() }
        return ParsedRange(start, end, "$monthLabel $day, $year", isSingleDate = true)
    }
}
