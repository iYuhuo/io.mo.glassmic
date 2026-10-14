package io.mo.glassmic.data.runtime

import io.mo.glassmic.core.model.SourceType
import java.util.ArrayDeque

data class DecisionRecord(
    val timestamp: Long,
    val callerPackage: String,
    val result: SourceType,
    val reasonCode: String,
    val reasonDescription: String,
    val lastSeen: Long = timestamp,
    val occurrences: Long = 1L,
    val backend: String = "LSPOSED"
)

/** Bounded per-caller transitions; repeated polling cannot evict another caller's evidence. */
class DecisionHistory(
    private val maxPackages: Int = 64,
    private val maxTransitionsPerPackage: Int = 8
) {
    init {
        require(maxPackages > 0 && maxTransitionsPerPackage > 0)
    }

    private val packages = LinkedHashMap<Pair<String, String>, ArrayDeque<DecisionRecord>>()

    @Synchronized
    fun record(record: DecisionRecord) {
        val key = record.callerPackage to record.backend
        val history = packages.remove(key) ?: ArrayDeque()
        val last = history.peekLast()
        if (last != null && last.result == record.result && last.reasonCode == record.reasonCode) {
            history.removeLast()
            history.addLast(last.copy(lastSeen = record.lastSeen, occurrences = last.occurrences + 1L))
        } else {
            if (history.size >= maxTransitionsPerPackage) history.removeFirst()
            history.addLast(record)
        }
        packages[key] = history
        if (packages.size > maxPackages) packages.remove(packages.keys.first())
    }

    @Synchronized
    fun recent(): List<DecisionRecord> = packages.values.flatMap { it }.sortedByDescending { it.lastSeen }
}
