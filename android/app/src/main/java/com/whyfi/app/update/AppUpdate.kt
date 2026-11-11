package com.whyfi.app.update

import com.whyfi.app.data.remote.AppReleaseResponse
import java.util.Locale
import okhttp3.HttpUrl

/** The newest build on the configured server (`GET app/latest/`). */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    /** Already pinned to the configured server, see [AppUpdate.pinnedUrl]. */
    val downloadUrl: String,
    /** 0 when the server did not say. */
    val apkBytes: Long,
)

/**
 * In-app update from the user's own server (user, 2026-11-11, after seeing
 * Fieldwatch's). The decisions live here, free of Android, so they are unit
 * tested; AppUpdater.kt does the network and the installer hand-off.
 *
 * Only the configured server is asked, with the phone's sensor token. A
 * standalone install (no server) never checks anywhere.
 */
object AppUpdate {
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val MAX_NOTES_CHARS = 6_000

    /** Null when the answer is unusable: no version, no file, or a download address that cannot be pinned. */
    fun release(dto: AppReleaseResponse?, backendUrl: String): AppRelease? {
        val code = dto?.versionCode ?: return null
        if (code <= 0) return null
        val url = pinnedUrl(backendUrl, dto.downloadUrl ?: return null) ?: return null
        val size = dto.apkSize ?: 0L
        if (size < 0 || size > MAX_APK_BYTES) return null
        return AppRelease(
            versionCode = code,
            versionName = dto.versionName?.trim().orEmpty().ifEmpty { code.toString() }.take(64),
            notes = dto.releaseNotes?.trim().orEmpty().take(MAX_NOTES_CHARS),
            downloadUrl = url,
            apkBytes = size,
        )
    }

    /**
     * The APK is fetched from the configured server and nowhere else: the
     * server's answer supplies the path and the signed token, the scheme,
     * host and port come from Settings. This also repairs an address the
     * server built with the wrong scheme behind a proxy.
     */
    fun pinnedUrl(backendUrl: String, releaseUrl: String): String? {
        val backend = HttpUrl.parse(backendUrl.trim()) ?: return null
        val release = HttpUrl.parse(releaseUrl.trim()) ?: backend.resolve(releaseUrl.trim()) ?: return null
        return release.newBuilder().scheme(backend.scheme()).host(backend.host()).port(backend.port()).build().toString()
    }

    /**
     * A higher version code is offered. [skippedCode] is the version the user
     * answered "Later" to; the check on start stays quiet about it, the
     * button in Settings ([manual]) offers it again.
     */
    fun shouldOffer(installedCode: Int, release: AppRelease, skippedCode: Int, manual: Boolean): Boolean =
        release.versionCode > installedCode && (manual || release.versionCode != skippedCode)

    /** An APK is a zip: "PK\u0003\u0004". Catches an HTML error page saved as the download. */
    fun looksLikeApk(header: ByteArray): Boolean =
        header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()

    /** 0–1 for the progress bar; null (indeterminate) when the size is unknown. */
    fun fraction(done: Long, total: Long): Float? =
        if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null

    /** "12.3 of 25.1 MB", or "12.3 MB" when the size is unknown. */
    fun progressLabel(done: Long, total: Long): String {
        fun mb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)
        return if (total > 0) "${mb(done)} of ${mb(total)} MB" else "${mb(done)} MB"
    }
}
