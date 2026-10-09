package com.andyyhope.featureflag.json

import java.time.Instant
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Second-precision internet date-time, as `ISO8601DateFormatter` with `.withInternetDateTime`
 * writes and reads it.
 *
 * Formatting writes `yyyy-MM-dd'T'HH:mm:ss'Z'` in UTC on the hybrid Julian/Gregorian calendar
 * (1582-10-15 cutover), floors to the second, writes the era year zero-padded to four digits,
 * and clamps to ICU's supported range exactly as Apple does.
 *
 * Parsing accepts only the canonical subset: `yyyy-MM-ddTHH:mm:ss` (a four- to seven-digit
 * year) followed by `Z`, `z`, `±HH:MM` or `±HHMM`, with every field range-checked (no
 * rollover, no leap second, offsets up to ±23:59). Year `0000` is 1 BC, as Apple reads it.
 * Apple's lenient forms — single-digit fields, `GMT`, surrounding spaces, `24:00:00`,
 * `2026-02-30` and the rest — are a documented deviation.
 */
internal object IsoDate {

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val CANONICAL = Regex("""(\d{4,7})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(Z|z|[+-]\d{2}:?\d{2})""")

    /** ICU's `MIN_MILLIS` and `MAX_MILLIS`, in seconds. Apple clamps every date to these. */
    private const val MIN_SECONDS = -184_303_902_528_000L
    private const val MAX_SECONDS = 183_882_168_921_600L

    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 3600
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59
    private const val MAX_SECOND = 59
    private const val MONTHS = 12

    fun format(instant: Instant): String {
        val seconds = instant.epochSecond.coerceIn(MIN_SECONDS, MAX_SECONDS)
        val calendar = GregorianCalendar(UTC).apply { timeInMillis = seconds * MILLIS_PER_SECOND }
        return buildString {
            append(calendar.get(Calendar.YEAR).toString().padStart(4, '0')).append('-')
            append(two(calendar.get(Calendar.MONTH) + 1)).append('-')
            append(two(calendar.get(Calendar.DAY_OF_MONTH))).append('T')
            append(two(calendar.get(Calendar.HOUR_OF_DAY))).append(':')
            append(two(calendar.get(Calendar.MINUTE))).append(':')
            append(two(calendar.get(Calendar.SECOND))).append('Z')
        }
    }

    fun parse(text: String): Instant? {
        val groups = CANONICAL.matchEntire(text)?.groupValues ?: return null
        val year = groups[1].toInt()
        val month = groups[2].toInt()
        val day = groups[3].toInt()
        val hour = groups[4].toInt()
        val minute = groups[5].toInt()
        val second = groups[6].toInt()
        val offset = offsetSeconds(groups[7]) ?: return null
        val timeIsValid = hour <= MAX_HOUR && minute <= MAX_MINUTE && second <= MAX_SECOND
        if (month !in 1..MONTHS || day < 1 || !timeIsValid) return null

        val calendar = GregorianCalendar(UTC).apply {
            clear()
            set(Calendar.ERA, if (year == 0) GregorianCalendar.BC else GregorianCalendar.AD)
            set(Calendar.YEAR, if (year == 0) 1 else year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        if (day > calendar.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
        calendar.set(Calendar.DAY_OF_MONTH, day)
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, second)
        return Instant.ofEpochSecond(Math.floorDiv(calendar.timeInMillis, MILLIS_PER_SECOND) - offset)
    }

    private fun offsetSeconds(zone: String): Long? {
        if (zone == "Z" || zone == "z") return 0
        val digits = zone.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toInt()
        val minutes = digits.substring(2, 4).toInt()
        if (hours > MAX_HOUR || minutes > MAX_MINUTE) return null
        val magnitude = hours.toLong() * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE
        return if (zone[0] == '-') -magnitude else magnitude
    }

    private fun two(value: Int): String = value.toString().padStart(2, '0')
}
