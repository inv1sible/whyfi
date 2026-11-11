package com.whyfi.app.update

import com.whyfi.app.data.remote.AppReleaseResponse
import com.whyfi.app.data.remote.GithubReleaseResponse
import java.util.Locale
import okhttp3.HttpUrl

/** Where a build was found. The order breaks ties: the user's own server first. */
enum class UpdateSource(val where: String, val label: String) {
    SERVER("on your server", "Your server"),
    GITHUB("on GitHub", "GitHub"),
}

/** The newest build of one source: the configured server (`GET app/latest/`) or the GitHub releases. */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    /** Checked already: pinned to the configured server ([AppUpdate.pinnedUrl]) or to this project's GitHub downloads ([AppUpdate.githubAssetUrl]). */
    val downloadUrl: String,
    /** 0 when the source did not say. */
    val apkBytes: Long,
    val source: UpdateSource = UpdateSource.SERVER,
    /** Lower-case hex SHA-256 of the APK when the source publishes one (GitHub does); else "". */
    val sha256: String = "",
)

/**
 * In-app update (user, 2026-11-11, after seeing Fieldwatch's). The decisions live here, free of Android, so they are unit
 * tested; AppUpdater.kt does the network and the installer hand-off.
 *
 * Two sources (user, 2026-11-11: "updates from both local and GitHub"):
 * the configured server, asked with the phone's sensor token, and the
 * project's GitHub releases. The highest version code wins; the server on a
 * tie. Both are signed with the same key ([OFFICIAL_SIGNER]), so a phone can
 * move between them. A copy signed with another key (someone's own build)
 * never asks GitHub: Android would refuse that update.
 */
object AppUpdate {
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val MAX_NOTES_CHARS = 6_000

    const val GITHUB_REPO = "inv1sible/whyfi"
    const val GITHUB_LATEST_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    /** SHA-256 of the certificate that signs the builds of the maintainer's server and the GitHub releases. */
    const val OFFICIAL_SIGNER = "9f4c75418dc3dbbb0212c142f117a39946f06eb0659033a7d5bb6cfc0c8931f4"

    private val titleCode = Regex("""\((\d{1,9})\)""")
    private val hex64 = Regex("[0-9a-f]{64}")

    /**
     * A GitHub release as an [AppRelease], or null. The version code is the
     * number in brackets in the release title ("whyfi 2026.11.11-0758 (114)"):
     * that title format is what makes a release visible to the app. Drafts
     * and pre-releases are ignored, and the APK must sit under this
     * repository's release downloads on github.com.
     */
    fun githubRelease(dto: GithubReleaseResponse?): AppRelease? {
        if (dto == null || dto.draft == true || dto.prerelease == true) return null
        val code = titleCode.findAll(dto.name.orEmpty()).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (code <= 0) return null
        val asset = dto.assets.orEmpty().firstOrNull { it.name.orEmpty().endsWith(".apk", ignoreCase = true) } ?: return null
        val url = githubAssetUrl(asset.downloadUrl ?: return null) ?: return null
        val size = asset.size ?: 0L
        if (size < 0 || size > MAX_APK_BYTES) return null
        val digest = asset.digest.orEmpty().lowercase().removePrefix("sha256:").takeIf { hex64.matches(it) }.orEmpty()
        return AppRelease(
            versionCode = code,
            versionName = dto.tagName?.trim().orEmpty().removePrefix("v").ifEmpty { code.toString() }.take(64),
            notes = dto.body?.trim().orEmpty().take(MAX_NOTES_CHARS),
            downloadUrl = url,
            apkBytes = size,
            source = UpdateSource.GITHUB,
            sha256 = digest,
        )
    }

    /** Only https://github.com/<repo>/releases/download/…; GitHub redirects from there to its file store. */
    fun githubAssetUrl(url: String): String? {
        val parsed = HttpUrl.parse(url.trim()) ?: return null
        val ok = parsed.isHttps && parsed.host() == "github.com" &&
            parsed.encodedPath().startsWith("/$GITHUB_REPO/releases/download/")
        return if (ok) parsed.toString() else null
    }

    /** The build to offer out of all sources: highest version code, the user's server on a tie. */
    fun best(releases: List<AppRelease>): AppRelease? =
        releases.sortedWith(compareByDescending<AppRelease> { it.versionCode }.thenBy { it.source.ordinal }).firstOrNull()

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
