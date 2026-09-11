package com.guardpulse.parentcontrol.shared

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateKeys {
    private const val DAY_FORMAT = "yyyy-MM-dd"
    private const val MILLIS_PER_DAY = 24L * 60L * 60_000L

    fun today(): String {
        // The hot path computes day keys several times per accessibility event;
        // allocating a SimpleDateFormat every time was measurable GC pressure on
        // the low-RAM TV. Cache by local calendar day (resets at local midnight).
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        localCache?.let { (y, m, d, key) ->
            if (y == year && m == month && d == day) return key
        }
        val key = SimpleDateFormat(DAY_FORMAT, Locale.US).format(Date())
        localCache = Quad(year, month, day, key)
        return key
    }

    /** ISO day key (UTC) for an epoch-millis instant — immune to device timezone shifts. */
    fun dayKeyUtc(epochMs: Long): String {
        val dayNumber = Math.floorDiv(epochMs, MILLIS_PER_DAY)
        utcCache?.let { (cachedDay, key) -> if (cachedDay == dayNumber) return key }
        val key = SimpleDateFormat(DAY_FORMAT, Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date(epochMs))
        utcCache = dayNumber to key
        return key
    }

    /** ISO day key (UTC) N days before the given instant; ISO strings compare lexicographically. */
    fun utcDaysAgo(days: Int, epochMs: Long): String =
        dayKeyUtc(epochMs - days * MILLIS_PER_DAY)

    /** ISO day key N days before today; ISO strings compare lexicographically. */
    fun daysAgo(days: Int): String {
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
        return SimpleDateFormat(DAY_FORMAT, Locale.US).format(calendar.time)
    }

    private data class Quad(val year: Int, val month: Int, val day: Int, val key: String)

    @Volatile
    private var localCache: Quad? = null

    @Volatile
    private var utcCache: Pair<Long, String>? = null
}
