package com.guardpulse.parentcontrol.shared

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateKeys {
    private const val DAY_FORMAT = "yyyy-MM-dd"

    fun today(): String = SimpleDateFormat(DAY_FORMAT, Locale.US).format(Date())

    /** ISO day key (UTC) for an epoch-millis instant — immune to device timezone shifts. */
    fun dayKeyUtc(epochMs: Long): String =
        SimpleDateFormat(DAY_FORMAT, Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))

    /** ISO day key (UTC) N days before the given instant; ISO strings compare lexicographically. */
    fun utcDaysAgo(days: Int, epochMs: Long): String =
        dayKeyUtc(epochMs - days * 24L * 60L * 60_000L)

    /** ISO day key N days before today; ISO strings compare lexicographically. */
    fun daysAgo(days: Int): String {
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
        return SimpleDateFormat(DAY_FORMAT, Locale.US).format(calendar.time)
    }
}
