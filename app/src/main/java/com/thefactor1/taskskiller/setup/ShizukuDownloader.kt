package com.thefactor1.taskskiller.setup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Gets Shizuku from its own GitHub releases, for TVs whose store doesn't offer it (Google TV's Play says "not
 * available for this device"). Only Rikka's signed build reaches Android's installer: the downloaded APK's package
 * name and signing certificate are checked first, and anything else is deleted.
 */
object ShizukuDownloader {
    const val RELEASES_API = "https://api.github.com/repos/RikkaApps/Shizuku/releases/latest"

    /** SHA-256 of the certificate Rikka signs Shizuku with (read from 13.6.0's APK, 2026-10-20). */
    const val RIKKA_CERT_SHA256 = "268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30"

    const val APK_NAME = "shizuku.apk"
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val TIMEOUT_MS = 20_000

    sealed class Result {
        data class Ready(val file: File, val version: String) : Result()
        data class Failed(val why: String) : Result()
    }

    /** The first APK asset of a GitHub release: (file name, download URL), or null. */
    fun pickApk(releaseJson: String): Pair<String, String>? {
        val assets = runCatching { JSONObject(releaseJson).getJSONArray("assets") }.getOrNull() ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) return name to url
        }
        return null
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Shizuku's package, signed only by [expected]. A missing or extra signer fails. */
    fun isRikka(packageName: String?, certs: List<ByteArray>, expected: String = RIKKA_CERT_SHA256): Boolean =
        packageName == ShizukuStarter.PACKAGE && certs.isNotEmpty() && certs.all { sha256(it) == expected }

    /** Network and disk work: call off the main thread. */
    fun download(context: Context): Result {
        val file = File(context.cacheDir, APK_NAME)
        file.delete()
        return try {
            val json = open(RELEASES_API, "application/vnd.github+json").use { it.readBytes().toString(Charsets.UTF_8) }
            val (name, url) = pickApk(json) ?: return Result.Failed("no APK in Shizuku's latest release")
            open(url, "application/octet-stream").use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return Result.Failed("download was too large").also { file.delete() }
                        out.write(buf, 0, n)
                    }
                }
            }
            val info = archiveInfo(context, file) ?: return Result.Failed("the download isn't an app").also { file.delete() }
            if (!isRikka(info.packageName, signers(info))) {
                file.delete()
                return Result.Failed("the download isn't signed by Shizuku's developer")
            }
            Result.Ready(file, info.versionName ?: name)
        } catch (e: Exception) {
            file.delete()
            Result.Failed(e.message?.take(80) ?: e.javaClass.simpleName)
        }
    }

    /** Hands the checked APK to Android's installer; the TV asks the viewer to confirm (ShizukuInstallReceiver). */
    fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ShizukuStarter.PACKAGE)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite(APK_NAME, 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, ShizukuInstallReceiver::class.java), flags
            )
            session.commit(status.intentSender)
        }
    }

    private fun open(url: String, accept: String): java.io.InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Refresher")
        conn.setRequestProperty("Accept", accept)
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw java.io.IOException("GitHub answered $code")
        }
        return conn.inputStream
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, file: File): PackageInfo? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(file.path, flags)
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.let { return it.map { s -> s.toByteArray() } }
        }
        return info.signatures?.map { it.toByteArray() }.orEmpty()
    }
}
