package com.whyfi.app.update

import com.whyfi.app.data.remote.AppReleaseResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    private val backend = "https://whyfi.example:8443"

    private fun dto(
        code: Int? = 118, name: String? = "2026.11.11-2000", notes: String? = " In-app update ",
        url: String? = "http://internal:8000/media/releases/whyfi-2026.11.11-2000.apk?t=abc:def", size: Long? = 25_000_000,
    ) = AppReleaseResponse(code, name, notes, url, size)

    private fun release(code: Int = 118) = AppUpdate.release(dto(code = code), backend)!!

    @Test
    fun aUsableAnswerBecomesAReleasePinnedToTheConfiguredServer() {
        val r = AppUpdate.release(dto(), backend)!!
        assertEquals(118, r.versionCode)
        assertEquals("2026.11.11-2000", r.versionName)
        assertEquals("In-app update", r.notes)
        assertEquals("https://whyfi.example:8443/media/releases/whyfi-2026.11.11-2000.apk?t=abc:def", r.downloadUrl)
        assertEquals(25_000_000L, r.apkBytes)
    }

    @Test
    fun unusableAnswersAreRefused() {
        assertNull(AppUpdate.release(null, backend))
        assertNull(AppUpdate.release(dto(code = null), backend))
        assertNull(AppUpdate.release(dto(code = 0), backend))
        assertNull(AppUpdate.release(dto(url = null), backend))
        assertNull(AppUpdate.release(dto(size = AppUpdate.MAX_APK_BYTES + 1), backend))
        assertNull(AppUpdate.release(dto(), "not a url"))
        // Missing name, notes and size are fine.
        val bare = AppUpdate.release(dto(name = null, notes = null, size = null), backend)!!
        assertEquals(listOf("118", "", "0"), listOf(bare.versionName, bare.notes, bare.apkBytes.toString()))
    }

    @Test
    fun theDownloadNeverLeavesTheConfiguredServer() {
        assertEquals("https://whyfi.example:8443/media/a.apk?t=1",
            AppUpdate.pinnedUrl(backend, "https://evil.example/media/a.apk?t=1"))
        // A relative address from the server resolves against the backend.
        assertEquals("http://192.168.1.5:8000/media/a.apk?t=1",
            AppUpdate.pinnedUrl("http://192.168.1.5:8000/", "/media/a.apk?t=1"))
        assertNull(AppUpdate.pinnedUrl("", "/media/a.apk"))
    }

    @Test
    fun onlyNewerVersionsAreOfferedAndLaterSilencesTheStartCheck() {
        assertTrue(AppUpdate.shouldOffer(117, release(118), skippedCode = 0, manual = false))
        assertFalse(AppUpdate.shouldOffer(118, release(118), skippedCode = 0, manual = false))
        assertFalse(AppUpdate.shouldOffer(119, release(118), skippedCode = 0, manual = true))
        // "Later" on 118: quiet on start, still offered from Settings, and 119 asks again.
        assertFalse(AppUpdate.shouldOffer(117, release(118), skippedCode = 118, manual = false))
        assertTrue(AppUpdate.shouldOffer(117, release(118), skippedCode = 118, manual = true))
        assertTrue(AppUpdate.shouldOffer(117, release(119), skippedCode = 118, manual = false))
    }

    @Test
    fun apkHeaderAndProgress() {
        assertTrue(AppUpdate.looksLikeApk(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)))
        assertFalse(AppUpdate.looksLikeApk("<htm".toByteArray()))
        assertFalse(AppUpdate.looksLikeApk(byteArrayOf(0x50, 0x4B)))
        assertEquals(0.5f, AppUpdate.fraction(50, 100)!!, 1e-6f)
        assertNull(AppUpdate.fraction(50, 0))
        assertEquals("12.0 of 24.0 MB", AppUpdate.progressLabel(12 * 1_048_576L, 24 * 1_048_576L))
        assertEquals("1.5 MB", AppUpdate.progressLabel(1_572_864, 0))
    }
}
