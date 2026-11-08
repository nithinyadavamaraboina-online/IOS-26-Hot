package com.realme.samurai.parts

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PixelModsStatus(
    val modPhotos: String = "unlimited_active",
    val modCromite: String = "not_installed",
    val progressMessage: String = "",
)

object SamuraiAdblockModManager {
    private const val QUEUE_DIR = "/data/ota_package/hw/queue"
    private const val MODS_PROP = "/data/ota_package/hw/mods.prop"
    private const val STAGE_DIR = "/data/ota_package/mods_stage"
    private const val RELEASE_MODS_JSON = "/system/etc/samurai-mods/release-mods.json"

    private val seq = AtomicLong(1L)
    private val worker = Executors.newSingleThreadExecutor()
    private val syncBusy = AtomicBoolean(false)

    @Volatile
    var liveProgress: String = ""
        private set

    fun readStatus(): PixelModsStatus {
        val f = File(MODS_PROP)
        if (!f.isFile) {
            return PixelModsStatus(progressMessage = liveProgress)
        }
        return try {
            val map = HashMap<String, String>()
            f.forEachLine { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            }
            PixelModsStatus(
                modPhotos = map["mod_photos"] ?: "unlimited_active",
                modCromite = map["mod_cromite"] ?: "not_installed",
                progressMessage = liveProgress,
            )
        } catch (_: Throwable) {
            PixelModsStatus(progressMessage = liveProgress)
        }
    }

    private fun writeQueueFile(prefix: String, lines: List<String>) {
        if (lines.isEmpty()) return
        try {
            val qDir = File(QUEUE_DIR)
            if (!qDir.exists()) qDir.mkdirs()
            val id = "${System.nanoTime()}_${seq.getAndIncrement()}"
            val tmp = File(qDir, ".${prefix}_$id")
            val req = File(qDir, "${prefix}_$id.req")
            tmp.writeText(lines.joinToString("\n") + "\n")
            tmp.setReadable(true, false)
            if (!tmp.renameTo(req)) {
                req.writeText(lines.joinToString("\n") + "\n")
                req.setReadable(true, false)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    fun requestImmediateModSync() {
        writeQueueFile("mods", listOf("MODS_SYNC_NOW=1"))
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun installOrOpenCromite(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage("org.cromite.cromite")
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launch)
            } catch (_: Throwable) {
            }
            return
        }
        if (!isOnline(context.applicationContext) || !syncBusy.compareAndSet(false, true)) return
        worker.execute {
            try {
                val manifestFile = File(RELEASE_MODS_JSON)
                if (manifestFile.isFile) {
                    val item = JSONObject(manifestFile.readText()).optJSONObject("cromite")
                    if (item != null) {
                        val fn = item.optString("filename")
                        val url = item.optString("url")
                        val sha = item.optString("sha256")
                        if (fn.isNotEmpty() && url.isNotEmpty() && sha.isNotEmpty()) {
                            downloadVerifiedAsset(url, File(STAGE_DIR, fn), sha, "Cromite Browser")
                            liveProgress = "Installing Cromite Browser…"
                            requestImmediateModSync()
                            Thread.sleep(4000L)
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                liveProgress = ""
                syncBusy.set(false)
            }
        }
    }

    private fun openRedirectingConnection(startUrl: String): HttpURLConnection {
        var curUrl = startUrl
        for (hop in 0..5) {
            val conn = (URL(curUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "RMX1931-PixelOS-SamuraiParts/17.0")
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) throw IllegalStateException("Redirect missing Location")
                curUrl = URL(URL(curUrl), loc).toString()
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code for $curUrl")
            }
            return conn
        }
        throw IllegalStateException("Too many redirects for $startUrl")
    }

    private fun downloadVerifiedAsset(url: String, destFile: File, expectedSha256: String, label: String) {
        val dir = destFile.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val part = File(destFile.absolutePath + ".part")
        if (part.exists()) part.delete()

        val md = MessageDigest.getInstance("SHA-256")
        val conn = openRedirectingConnection(url)
        try {
            val total = conn.contentLengthLong
            var got = 0L
            BufferedInputStream(conn.inputStream, 131072).use { input ->
                BufferedOutputStream(FileOutputStream(part), 131072).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        got += n
                        if (total > 0) {
                            val pct = ((got * 100L) / total).coerceIn(0L, 100L)
                            liveProgress = "Downloading $label ($pct%)…"
                        } else {
                            liveProgress = "Downloading $label (${got / (1024 * 1024)} MiB)…"
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val actualSha = md.digest().joinToString("") { "%02x".format(it) }
        if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            throw SecurityException("SHA-256 mismatch for ${destFile.name}: $actualSha != $expectedSha256")
        }
        part.setReadable(true, false)
        if (destFile.exists()) destFile.delete()
        if (!part.renameTo(destFile)) {
            part.copyTo(destFile, overwrite = true)
            part.delete()
        }
        destFile.setReadable(true, false)
    }
}
