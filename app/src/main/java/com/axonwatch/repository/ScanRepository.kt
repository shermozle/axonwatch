package com.axonwatch.repository

import com.axonwatch.data.model.DetectionEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory store for scan results. Tracks detection timestamps per MAC to:
 *  - Deduplicate alerts (same device within DEDUP_WINDOW doesn't trigger another sound)
 *  - Count unique devices seen in a rolling time window for the notification
 */
class ScanRepository {

    private val detections = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()

    /**
     * Records a detection and returns true if this MAC was not seen within the dedup window.
     * A true return means the caller should trigger an alert and report to the server.
     */
    fun recordDetection(event: DetectionEvent): Boolean {
        val timestamps = detections.getOrPut(event.mac) { CopyOnWriteArrayList() }
        val now = System.currentTimeMillis()
        val isNew = timestamps.none { now - it < DEDUP_WINDOW_MS }
        timestamps.add(now)
        pruneOldEntries(timestamps, now)
        return isNew
    }

    /** Returns the number of distinct MACs detected within [windowMs] milliseconds. */
    fun getUniqueMatchesInWindow(windowMs: Long): Int {
        val cutoff = System.currentTimeMillis() - windowMs
        return detections.count { (_, timestamps) -> timestamps.any { it >= cutoff } }
    }

    fun clear() = detections.clear()

    private fun pruneOldEntries(timestamps: CopyOnWriteArrayList<Long>, now: Long) {
        val cutoff = now - MAX_HISTORY_MS
        timestamps.removeAll { it < cutoff }
    }

    companion object {
        /** Don't re-alert for the same device within this window */
        private const val DEDUP_WINDOW_MS = 30_000L
        /** Max age of entries to keep in memory */
        private const val MAX_HISTORY_MS = 15 * 60 * 1000L
    }
}
