package com.whyfi.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {
    private fun item(key: String, bytes: Long, at: Long, removable: Boolean) = StoragePolicy.Item(key, bytes, at, removable)

    @Test
    fun underTheLimitNothingGoes() {
        val plan = StoragePolicy.plan(listOf(item("a", 10, 1, true)), 100)
        assertTrue(plan.remove.isEmpty())
        assertEquals(0, plan.overBytes)
    }

    @Test
    fun uploadedGoOldestFirstUntilUnderTheLimit() {
        val plan = StoragePolicy.plan(
            listOf(item("new", 40, 3, true), item("old", 40, 1, true), item("mid", 40, 2, true)),
            limitBytes = 50,
        )
        assertEquals(listOf("old", "mid"), plan.remove)
        assertEquals(0, plan.overBytes)
    }

    @Test
    fun notUploadedIsNeverRemovedAndReportedInstead() {
        val plan = StoragePolicy.plan(
            listOf(item("local", 80, 1, false), item("synced", 30, 2, true)),
            limitBytes = 50,
        )
        assertEquals(listOf("synced"), plan.remove)
        assertEquals(30, plan.overBytes)
    }

    private fun summary(passes: Int, uploaded: Int, rejected: Int = 0, ended: Long? = 5, sync: Boolean = true) =
        RecordingSummary("r", "scan", 1, ended, sync, null, passes, 100, uploaded, rejected)

    @Test
    fun removableOnlyWhenClosedAndNothingWaits() {
        assertTrue(StoragePolicy.item(summary(3, 3)).removable)
        assertTrue(StoragePolicy.item(summary(3, 2, rejected = 1)).removable)
        assertFalse(StoragePolicy.item(summary(3, 2)).removable)
        assertFalse(StoragePolicy.item(summary(3, 3, ended = null)).removable)
        // Standalone: nothing uploaded, so never removed automatically.
        assertFalse(StoragePolicy.item(summary(3, 0, sync = false)).removable)
    }
}
