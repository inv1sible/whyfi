package com.whyfi.app.data.local

/**
 * What the storage limit removes. Pure, JVM-tested (StoragePolicyTest).
 *
 * Fully uploaded recordings go first, oldest first: the server has them.
 * Anything not uploaded is never removed automatically, whether it's waiting
 * for a server or a standalone recording; if that alone is over the limit,
 * [Plan.overBytes] says by how much so the app can warn instead.
 */
object StoragePolicy {
    data class Item(val key: String, val bytes: Long, val startedAtMs: Long, val removable: Boolean)

    data class Plan(val remove: List<String>, val overBytes: Long)

    fun plan(items: List<Item>, limitBytes: Long): Plan {
        var total = items.sumOf { it.bytes }
        val remove = mutableListOf<String>()
        for (item in items.filter { it.removable }.sortedBy { it.startedAtMs }) {
            if (total <= limitBytes) break
            remove += item.key
            total -= item.bytes
        }
        return Plan(remove, (total - limitBytes).coerceAtLeast(0))
    }

    /** A recording is removable once closed and every pass reached the server (uploaded or refused); an empty one too. */
    fun item(summary: RecordingSummary) = Item(
        summary.id, summary.bytes, summary.startedAtMs,
        removable = !summary.isOpen && summary.waiting == 0,
    )
}
