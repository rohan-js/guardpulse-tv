package com.guardpulse.parentcontrol.tv.activity

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class ActivityStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "activity_history.db", null, 2) {
    private val prefs = context.getSharedPreferences("activity_state", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                package_name TEXT NOT NULL,
                app_label TEXT NOT NULL,
                title TEXT,
                subtitle TEXT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                last_position_ms INTEGER,
                duration_ms INTEGER,
                playback_state TEXT,
                confidence TEXT,
                capture_source TEXT,
                uploaded INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX history_started_at ON history(started_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE history ADD COLUMN capture_source TEXT")
        }
    }

    @Synchronized
    fun saveCurrent(snapshot: ActivitySnapshot) {
        prefs.edit().putString("current", snapshot.toJson().toString()).apply()
    }

    @Synchronized
    fun current(): ActivitySnapshot? {
        val raw = prefs.getString("current", null) ?: return null
        return runCatching { JSONObject(raw).toSnapshot() }.getOrNull()
    }

    @Synchronized
    fun addHistory(record: ActivityHistoryRecord) {
        writableDatabase.insertWithOnConflict(
            "history",
            null,
            ContentValues().apply {
                put("id", record.id)
                put("type", record.type)
                put("package_name", record.packageName)
                put("app_label", record.appLabel)
                put("title", record.title)
                put("subtitle", record.subtitle)
                put("started_at", record.startedAt)
                put("ended_at", record.endedAt)
                put("last_position_ms", record.lastPositionMs)
                put("duration_ms", record.durationMs)
                put("playback_state", record.playbackState)
                put("confidence", record.confidence)
                put("capture_source", record.captureSource)
                put("uploaded", 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun pendingHistory(limit: Int = 50): List<ActivityHistoryRecord> {
        val cursor = readableDatabase.query(
            "history",
            null,
            "uploaded = 0",
            null,
            null,
            null,
            "ended_at ASC",
            limit.toString()
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        ActivityHistoryRecord(
                            id = it.getString(it.getColumnIndexOrThrow("id")),
                            type = it.getString(it.getColumnIndexOrThrow("type")),
                            packageName = it.getString(it.getColumnIndexOrThrow("package_name")),
                            appLabel = it.getString(it.getColumnIndexOrThrow("app_label")),
                            title = it.stringOrNull("title"),
                            subtitle = it.stringOrNull("subtitle"),
                            startedAt = it.getLong(it.getColumnIndexOrThrow("started_at")),
                            endedAt = it.getLong(it.getColumnIndexOrThrow("ended_at")),
                            lastPositionMs = it.longOrNull("last_position_ms"),
                            durationMs = it.longOrNull("duration_ms"),
                            playbackState = it.stringOrNull("playback_state"),
                            confidence = it.stringOrNull("confidence"),
                            captureSource = it.stringOrNull("capture_source")
                                ?: MediaObservation.SOURCE_ACCESSIBILITY
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    fun markUploaded(id: String) {
        writableDatabase.update(
            "history",
            ContentValues().apply { put("uploaded", 1) },
            "id = ?",
            arrayOf(id)
        )
    }

    @Synchronized
    fun pruneBefore(cutoff: Long) {
        writableDatabase.delete("history", "started_at < ?", arrayOf(cutoff.toString()))
    }

    private fun ActivitySnapshot.toJson() = JSONObject()
        .put("runtimePackage", runtimePackage)
        .put("packageName", packageName)
        .put("appLabel", appLabel)
        .put("appStartedAt", appStartedAt)
        .put("overlayState", overlayState)
        .put("mediaTitle", mediaTitle)
        .put("mediaSubtitle", mediaSubtitle)
        .put("playbackState", playbackState)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)
        .put("positionCapturedAt", positionCapturedAt)
        .put("playbackSpeed", playbackSpeed.toDouble())
        .put("mediaStartedAt", mediaStartedAt)
        .put("mediaConfidence", mediaConfidence)
        .put("captureSource", captureSource)
        .put("updatedAt", updatedAt)

    private fun JSONObject.toSnapshot() = ActivitySnapshot(
        runtimePackage = getString("runtimePackage"),
        packageName = getString("packageName"),
        appLabel = getString("appLabel"),
        appStartedAt = getLong("appStartedAt"),
        overlayState = optString("overlayState", ActivitySnapshot.OVERLAY_NONE),
        mediaTitle = optionalString("mediaTitle"),
        mediaSubtitle = optionalString("mediaSubtitle"),
        playbackState = optString("playbackState", MediaObservation.PLAYBACK_UNKNOWN),
        positionMs = optionalLong("positionMs"),
        durationMs = optionalLong("durationMs"),
        positionCapturedAt = optionalLong("positionCapturedAt"),
        playbackSpeed = optDouble("playbackSpeed", 0.0).toFloat(),
        mediaStartedAt = optionalLong("mediaStartedAt"),
        mediaConfidence = optionalString("mediaConfidence"),
        captureSource = optionalString("captureSource") ?: MediaObservation.SOURCE_ACCESSIBILITY,
        updatedAt = getLong("updatedAt")
    )

    private fun JSONObject.optionalString(key: String): String? {
        return if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    }

    private fun JSONObject.optionalLong(key: String): Long? {
        return if (isNull(key) || !has(key)) null else optLong(key)
    }

    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }
}
