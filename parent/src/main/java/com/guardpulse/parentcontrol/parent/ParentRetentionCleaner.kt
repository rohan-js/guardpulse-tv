package com.guardpulse.parentcontrol.parent

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.guardpulse.parentcontrol.shared.FirebasePaths
import com.guardpulse.parentcontrol.shared.PolicyConstants

class ParentRetentionCleaner(private val database: DatabaseReference) {
    private val cleanedDevices = mutableSetOf<String>()

    fun cleanup(deviceId: String, now: Long = System.currentTimeMillis()) {
        if (!cleanedDevices.add(deviceId)) return
        cleanupTerminal(
            deviceId,
            FirebasePaths.deviceCommands(deviceId),
            now - TERMINAL_RETENTION_MS,
            setOf(PolicyConstants.COMMAND_DONE, PolicyConstants.COMMAND_FAILED, PolicyConstants.COMMAND_EXPIRED),
            terminalTimestampField = "completedAt"
        )
        cleanupTerminal(
            deviceId,
            FirebasePaths.deviceUnlockRequests(deviceId),
            now - TERMINAL_RETENTION_MS,
            setOf(PolicyConstants.UNLOCK_APPROVED, PolicyConstants.UNLOCK_DENIED, PolicyConstants.UNLOCK_EXPIRED),
            terminalTimestampField = "updatedAt"
        )
        cleanupTamperEvents(deviceId, now)
    }

    /**
     * Retention is "7 days after reaching a terminal state", not after creation:
     * the createdAt-ordered query only bounds the candidate set (it is the
     * declared index), and each row is then filtered on its terminal timestamp —
     * completedAt for commands, updatedAt for unlock requests — falling back to
     * createdAt only for rows old enough to predate that field. Deleting an
     * approved-but-unapplied unlock request because it was CREATED long ago
     * would drop it from the parent UI while the TV still needs it.
     */
    private fun cleanupTerminal(
        deviceId: String,
        path: String,
        cutoff: Long,
        terminalStatuses: Set<String>,
        terminalTimestampField: String
    ) {
        database.child(path)
            .orderByChild("createdAt")
            .endAt(cutoff.toDouble())
            .limitToFirst(CLEANUP_BATCH_SIZE)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val updates = snapshot.children.mapNotNull { child ->
                        val key = child.key ?: return@mapNotNull null
                        val status = child.child("status").getValue(String::class.java)
                        if (status !in terminalStatuses) return@mapNotNull null
                        val createdAt = child.child("createdAt").getValue(Long::class.java) ?: 0L
                        val terminalAt = child.child(terminalTimestampField)
                            .getValue(Long::class.java)
                            ?.takeIf { it > 0L }
                            ?: createdAt
                        if (terminalAt > cutoff) return@mapNotNull null
                        "$path/$key" to null
                    }.toMap()
                    if (updates.isEmpty()) return
                    database.updateChildren(updates)
                        .addOnFailureListener { cleanedDevices.remove(deviceId) }
                }

                override fun onCancelled(error: DatabaseError) {
                    cleanedDevices.remove(deviceId)
                }
            })
    }

    private fun cleanupTamperEvents(deviceId: String, now: Long) {
        val path = FirebasePaths.deviceTamperEvents(deviceId)
        database.child(path)
            .orderByChild("createdAt")
            .limitToLast(TAMPER_QUERY_LIMIT)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val newestFirst = snapshot.children
                        .sortedByDescending { it.child("createdAt").getValue(Long::class.java) ?: 0L }
                    val updates = newestFirst.mapIndexedNotNull { index, child ->
                        val key = child.key ?: return@mapIndexedNotNull null
                        val createdAt = child.child("createdAt").getValue(Long::class.java) ?: 0L
                        if (index < MAX_TAMPER_EVENTS && createdAt >= now - TAMPER_RETENTION_MS) {
                            null
                        } else {
                            "$path/$key" to null
                        }
                    }.toMap()
                    if (updates.isNotEmpty()) database.updateChildren(updates)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            })
    }

    private companion object {
        const val TERMINAL_RETENTION_MS = 7L * 24 * 60 * 60_000
        const val TAMPER_RETENTION_MS = 30L * 24 * 60 * 60_000
        const val MAX_TAMPER_EVENTS = 200
        const val TAMPER_QUERY_LIMIT = 250
        const val CLEANUP_BATCH_SIZE = 100
    }
}
