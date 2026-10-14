package io.mo.glassmic.xposed

/** Monotonic-time query pacing. Capture onset bypasses even an unreachable idle cache. */
class SourceQueryPolicy {
    private var lastQueryAt = -1L
    private var lastCaptureAt = -1L
    private var failures = 0

    @Synchronized
    fun shouldQuery(now: Long, captureObserved: Boolean): Boolean {
        val captureStarted = captureObserved &&
            (lastCaptureAt < 0L || now - lastCaptureAt > ACTIVE_WINDOW_MS)
        if (captureObserved) lastCaptureAt = now
        val active = lastCaptureAt >= 0L && now - lastCaptureAt <= ACTIVE_WINDOW_MS
        val ttl = when {
            failures > 0 && active -> 2_000L
            failures > 0 -> (2_000L shl (failures - 1)).coerceAtMost(30_000L)
            active -> 200L
            else -> 5_000L
        }
        return captureStarted || lastQueryAt < 0L || now - lastQueryAt >= ttl
    }

    @Synchronized
    fun onQuery(now: Long, reachable: Boolean) {
        lastQueryAt = now
        failures = if (reachable) 0 else (failures + 1).coerceAtMost(5)
    }

    private companion object {
        const val ACTIVE_WINDOW_MS = 1_500L
    }
}
