package com.guardpulse.parentcontrol.tv.sync

internal class RevisionGenerationTracker {
    private var generation = 0L
    private var revisionId: String? = null

    fun advance(revisionId: String): Long {
        generation += 1
        this.revisionId = revisionId
        return generation
    }

    fun isCurrent(generation: Long, revisionId: String): Boolean =
        this.generation == generation && this.revisionId == revisionId
}

internal fun appendProcessedCommand(
    commands: List<String>,
    commandId: String,
    maximum: Int
): List<String> {
    if (maximum <= 0) return emptyList()
    val updated = commands.filterNot { it == commandId }.toMutableList()
    updated += commandId
    return updated.takeLast(maximum)
}

internal fun shouldApplyControl(
    controlRevisionId: String,
    lastAppliedRevisionId: String?,
    pendingAppliedRevisionId: String?,
    currentSessionId: String?,
    lastAppliedSessionId: String?
): Boolean {
    return currentSessionId != lastAppliedSessionId ||
        pendingAppliedRevisionId != null ||
        lastAppliedRevisionId != controlRevisionId
}
