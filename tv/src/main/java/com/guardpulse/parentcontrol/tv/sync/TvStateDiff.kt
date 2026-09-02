package com.guardpulse.parentcontrol.tv.sync

/**
 * Pure diff helper for state uploads: comparing desired children against the last
 * successfully uploaded snapshot lets the sync service skip RTDB writes entirely
 * at idle. Values are compared structurally; timestamp sentinels must stay out of
 * both maps (they are injected into changed children right before the write).
 */
internal object TvStateDiff {
    /** Returns entries of [desired] whose value differs from the matching key in [last]. */
    fun changed(desired: Map<String, Any?>, last: Map<String, Any?>): Map<String, Any?> =
        desired.filter { (key, value) -> last[key] != value }
}
