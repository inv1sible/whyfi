package com.whyfi.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.whyfi.app.data.SettingsRepository
import com.google.gson.Gson
import com.whyfi.app.data.remote.ApiClientFactory
import com.whyfi.app.data.remote.GithubReleaseResponse
import com.whyfi.app.usbmon.MonitorEvidenceFiles
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Android half of the in-app update: ask the configured server and
 * GitHub, fetch the APK into the cache, check it, hand it to Android's
 * installer. Android always asks the user to confirm; nothing installs
 * silently.
 */
object AppUpdater {
    private const val DIR = "update"

    sealed interface Check {
        data class Found(val release: AppRelease) : Check
        /** The server answered, but has no build. */
        data object Nothing : Check
        data class Failed(val reason: String) : Check
    }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    data class Answer(val source: UpdateSource, val check: Check)

    @Volatile private var official: Boolean? = null

    /** Whether this copy is signed with the project's key, i.e. whether a GitHub build can update it. */
    fun officialBuild(context: Context): Boolean = official ?: run {
        val mine = info(context.packageManager, context.packageName, archive = false)?.let(::signers)
        (mine == setOf(AppUpdate.OFFICIAL_SIGNER)).also { official = it }
    }

    /** The sources this phone asks: the server when one is set up, GitHub when switched on and usable. */
    fun sources(context: Context, settings: SettingsRepository): List<UpdateSource> = buildList {
        if (settings.isConfigured) add(UpdateSource.SERVER)
        if (settings.updateFromGithub && officialBuild(context)) add(UpdateSource.GITHUB)
    }

    /** Asks every source at the same time. */
    suspend fun latestAll(context: Context, settings: SettingsRepository): List<Answer> = coroutineScope {
        sources(context, settings).map { source -> async { Answer(source, latest(source, settings)) } }.awaitAll()
    }

    suspend fun latest(source: UpdateSource, settings: SettingsRepository): Check = when (source) {
        UpdateSource.SERVER -> latestOnServer(settings)
        UpdateSource.GITHUB -> latestOnGithub()
    }

    private suspend fun latestOnServer(settings: SettingsRepository): Check {
        val url = settings.backendUrl
        val token = settings.sensorToken
        if (url.isNullOrBlank() || token.isNullOrBlank()) return Check.Failed("No server is set up on this phone.")
        return try {
            val response = withContext(Dispatchers.IO) { ApiClientFactory.create(url).latestRelease("Token $token") }
            when {
                response.code() == 404 -> Check.Nothing
                response.code() == 401 || response.code() == 403 -> Check.Failed("Your server did not accept this phone's token.")
                !response.isSuccessful -> Check.Failed("Your server answered with error ${response.code()}.")
                else -> AppUpdate.release(response.body(), url)?.let { Check.Found(it) }
                    ?: Check.Failed("Your server's answer could not be used.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Check.Failed("Your server did not answer.")
        }
    }

    /** No account and nothing about this phone is sent: one anonymous GET to GitHub's public API. */
    private suspend fun latestOnGithub(): Check = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(AppUpdate.GITHUB_LATEST_URL)
                .header("Accept", "application/vnd.github+json").header("User-Agent", "whyfi-android").build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code() == 404 -> Check.Nothing
                    !response.isSuccessful -> Check.Failed("GitHub answered with error ${response.code()}.")
                    else -> {
                        val text = response.body()?.string().orEmpty()
                        if (text.length > 2_000_000) return@use Check.Failed("GitHub's answer could not be used.")
                        AppUpdate.githubRelease(Gson().fromJson(text, GithubReleaseResponse::class.java))
                            ?.let { Check.Found(it) } ?: Check.Nothing
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Check.Failed("GitHub did not answer.")
        }
    }

    /**
     * Downloads into cache/update/whyfi-<code>.apk, replacing older
     * downloads. A complete earlier download of the same build is reused.
     * Throws IOException with a sentence for the user.
     */
    suspend fun download(context: Context, release: AppRelease, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val dest = File(dir, "whyfi-${release.source.name.lowercase()}-${release.versionCode}.apk")
            dir.listFiles()?.filter { it != dest }?.forEach { it.delete() }
            if (dest.isFile && release.apkBytes > 0 && dest.length() == release.apkBytes &&
                (release.sha256.isEmpty() || sha256(dest) == release.sha256)) return@withContext dest
            dest.delete()
            val part = File(dir, "${dest.name}.part")
            try {
                client.newCall(Request.Builder().url(release.downloadUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("${release.source.label} answered the download with error ${response.code()}.")
                    val body = response.body() ?: throw IOException("${release.source.label} sent an empty download.")
                    val total = release.apkBytes.takeIf { it > 0 } ?: body.contentLength()
                    if (total > AppUpdate.MAX_APK_BYTES) throw IOException("The download is larger than an app should be.")
                    var done = 0L
                    val digest = MessageDigest.getInstance("SHA-256")
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                done += n
                                if (done > AppUpdate.MAX_APK_BYTES) throw IOException("The download is larger than an app should be.")
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                onProgress(done, total)
                            }
                        }
                    }
                    if (release.apkBytes > 0 && done != release.apkBytes)
                        throw IOException("The download was cut off (${AppUpdate.progressLabel(done, release.apkBytes)}).")
                    if (release.sha256.isNotEmpty() && hex(digest.digest()) != release.sha256)
                        throw IOException("The download does not match the checksum ${release.source.label} published.")
                }
                if (!part.renameTo(dest)) throw IOException("The download could not be saved.")
                dest
            } finally {
                part.delete()
            }
        }

    /**
     * Null when [apk] is the announced, newer build of this very app, signed
     * like the installed copy; otherwise the reason, as a sentence. Android
     * refuses a different signer anyway. Checking here gives a clear message
     * instead of the installer's "App not installed", and matters because the
     * download may have come over plain HTTP.
     */
    fun problem(context: Context, apk: File, release: AppRelease): String? {
        val header = ByteArray(4)
        val read = runCatching { apk.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (read < 4 || !AppUpdate.looksLikeApk(header)) return "The download is not an app file."
        val pm = context.packageManager
        val archive = info(pm, apk.absolutePath, archive = true) ?: return "Android could not read the downloaded file."
        val installed = info(pm, context.packageName, archive = false) ?: return "Android could not read the installed app."
        if (archive.packageName != context.packageName) return "The download is a different app (${archive.packageName})."
        if (archive.longVersionCode != release.versionCode.toLong())
            return "The download is build ${archive.longVersionCode}, not the announced ${release.versionCode}."
        if (archive.longVersionCode <= installed.longVersionCode) return "The download is not newer than the installed app."
        val theirs = signers(archive)
        val ours = signers(installed)
        // Some Android versions cannot read an archive's signers; the installer still checks them.
        if (theirs != null && ours != null && theirs != ours)
            return "The download is signed with a different key than the installed app, so Android would refuse it."
        return null
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + MonitorEvidenceFiles.AUTHORITY_SUFFIX, apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun info(pm: PackageManager, name: String, archive: Boolean): PackageInfo? = runCatching {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        if (Build.VERSION.SDK_INT >= 33) {
            val wrapped = PackageManager.PackageInfoFlags.of(flags.toLong())
            if (archive) pm.getPackageArchiveInfo(name, wrapped) else pm.getPackageInfo(name, wrapped)
        } else {
            @Suppress("DEPRECATION")
            if (archive) pm.getPackageArchiveInfo(name, flags) else pm.getPackageInfo(name, flags)
        }
    }.getOrNull()

    /** SHA-256 of the current signing certificate(s); null when Android does not report them. */
    private fun signers(info: PackageInfo): Set<String>? {
        val signing = info.signingInfo ?: return null
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory?.takeIf { it.isNotEmpty() }?.let { arrayOf(it.last()) }
        if (certs.isNullOrEmpty()) return null
        return certs.map { cert -> hex(MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())) }.toSet()
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        hex(digest.digest())
    }.getOrNull()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
