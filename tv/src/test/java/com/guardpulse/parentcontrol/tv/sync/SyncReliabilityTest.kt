package com.guardpulse.parentcontrol.tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncReliabilityTest {
    @Test
    fun olderRevisionCompletionCannotBecomeCurrent() {
        val tracker = RevisionGenerationTracker()
        val firstGeneration = tracker.advance("revision-1")
        val secondGeneration = tracker.advance("revision-2")

        assertFalse(tracker.isCurrent(firstGeneration, "revision-1"))
        assertTrue(tracker.isCurrent(secondGeneration, "revision-2"))
        assertFalse(tracker.isCurrent(secondGeneration, "revision-1"))
    }

    @Test
    fun processedCommandHistoryDeduplicatesAndStaysBounded() {
        val result = appendProcessedCommand(
            commands = listOf("one", "two", "three"),
            commandId = "two",
            maximum = 3
        )
        assertEquals(listOf("one", "three", "two"), result)
        assertEquals(
            listOf("three", "two", "four"),
            appendProcessedCommand(result, "four", 3)
        )
    }

    @Test
    fun unchangedRevisionIsReappliedForANewSessionAcknowledgement() {
        assertFalse(
            shouldApplyControl(
                controlRevisionId = "revision-1",
                lastAppliedRevisionId = "revision-1",
                pendingAppliedRevisionId = null,
                currentSessionId = "session-1",
                lastAppliedSessionId = "session-1"
            )
        )
        assertTrue(
            shouldApplyControl(
                controlRevisionId = "revision-1",
                lastAppliedRevisionId = "revision-1",
                pendingAppliedRevisionId = null,
                currentSessionId = "session-2",
                lastAppliedSessionId = "session-1"
            )
        )
    }
}
