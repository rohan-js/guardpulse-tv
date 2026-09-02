package com.guardpulse.parentcontrol.tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvStateDiffTest {
    private val appOne = mapOf<String, Any?>(
        "packageName" to "com.example.one",
        "manualBlocked" to false,
        "usageMinutesToday" to 12L
    )
    private val appTwo = mapOf<String, Any?>(
        "packageName" to "com.example.two",
        "manualBlocked" to true,
        "usageMinutesToday" to 0L
    )

    @Test
    fun firstUploadTreatsEveryChildAsChanged() {
        val desired = mapOf<String, Any?>("one" to appOne, "two" to appTwo)
        val changed = TvStateDiff.changed(desired, emptyMap())
        assertEquals(desired, changed)
    }

    @Test
    fun unchangedChildrenAreSkippedIncludingNestedMaps() {
        val changed = TvStateDiff.changed(
            desired = mapOf("one" to appOne, "two" to appTwo),
            last = mapOf("one" to appOne, "two" to appTwo)
        )
        assertTrue(changed.isEmpty())
    }

    @Test
    fun changedChildIsIncludedWithFullPayload() {
        val updatedOne = appOne + mapOf("usageMinutesToday" to 13L)
        val changed = TvStateDiff.changed(
            desired = mapOf("one" to updatedOne, "two" to appTwo),
            last = mapOf("one" to appOne, "two" to appTwo)
        )
        assertEquals(mapOf("one" to updatedOne), changed)
    }

    @Test
    fun nullDeletionIncludedOnlyWhenLastWasNonNull() {
        val desired = mapOf<String, Any?>("legacy" to null, "one" to appOne)
        val last = mapOf<String, Any?>("legacy" to appTwo, "one" to appOne)
        val changed = TvStateDiff.changed(desired, last)
        assertEquals(mapOf<String, Any?>("legacy" to null), changed)
    }

    @Test
    fun nullVersusMissingKeyIsSkipped() {
        val desired = mapOf<String, Any?>("legacy" to null, "one" to appOne)
        val changed = TvStateDiff.changed(desired, mapOf("one" to appOne))
        assertFalse(changed.containsKey("legacy"))
        assertTrue(changed.isEmpty())
    }
}
