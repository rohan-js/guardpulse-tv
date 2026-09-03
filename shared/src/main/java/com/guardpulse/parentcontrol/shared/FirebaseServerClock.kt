package com.guardpulse.parentcontrol.shared

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class FirebaseServerClock {
    // Tolerant by design: services construct this clock in onCreate BEFORE any
    // configured-check, and on builds where Firebase was never initialized
    // getInstance() throws — which used to crash-loop the sync service, lock
    // screen, boot receiver, and WorkManager recovery together. Degrade to
    // device time instead.
    private val database: FirebaseDatabase? = runCatching { FirebaseDatabase.getInstance() }.getOrNull()

    @Volatile
    private var offsetMs: Long = 0L
    private var listener: ValueEventListener? = null

    fun start() {
        val database = database ?: return
        if (listener != null) return
        val ref = database.getReference(".info/serverTimeOffset")
        val valueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                offsetMs = snapshot.getValue(Long::class.java) ?: 0L
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        listener = valueListener
        ref.addValueEventListener(valueListener)
    }

    fun now(): Long = System.currentTimeMillis() + offsetMs

    fun offsetMillis(): Long = offsetMs

    fun stop() {
        val database = database ?: return
        val valueListener = listener ?: return
        database.getReference(".info/serverTimeOffset").removeEventListener(valueListener)
        listener = null
    }
}
