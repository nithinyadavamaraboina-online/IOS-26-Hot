package com.realme.ota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

data class OtaRelease(
    val version: String,
    val filename: String,
    val datetime: Long,
    val sizeBytes: Long,
    val downloadUrl: String,
    val sha256: String,
    val changelog: String,
    val sourceLabel: String,
    val channel: String = OtaEngine.CHANNEL_STABLE
)

object OtaEngine {
    const val PREFS_NAME = "pixel_ota_prefs"
    const val KEY_CUSTOM_REPO = "custom_github_repo"
    const val KEY_CUSTOM_URL = "custom_manifest_url"
    const val KEY_LAST_CHECK_MS = "last_check_ms"
    const val KEY_PRE_OTA_DATETIME = "pre_ota_datetime"
    const val KEY_PRE_OTA_SHA = "pre_ota_sha256"
    const val KEY_PRE_OTA_CHANNEL = "pre_ota_channel"
    const val KEY_CACHED_JSON = "cached_release_json"
    const val KEY_IOS_BETA_ENABLED = "ios_style_ui_beta_enabled"

    const val CHANNEL_STABLE = "stable"
    const val CHANNEL_IOS_BETA = "ios_beta"
    const val SETTING_IOS_STYLE_UI_BETA = "samurai_ios_style_ui_beta"

    const val DEFAULT_GITHUB_REPO = "nithinyadavamaraboina-online/PixelOS-Android17-RMX1931"
    const val OTA_RAW_DIR_PATH = "/data/ota_raw"
    const val OTA_DIR_PATH = "/data/ota_package"
    const val NOTIFICATION_CHANNEL_ID = "pixel_system_updates"
    const val PROGRESS_CHANNEL_ID = "pixel_system_update_progress"
    const val NOTIF_ID_AVAILABLE = 1701
    const val NOTIF_ID_COMPLETE = 1702
    const val NOTIF_ID_DOWNLOADING = 1703
    const val NOTIF_ID_READY_TO_INSTALL = 1704
    const val JOB_ID_PERIODIC_CHECK = 17001

    private val propCache = HashMap<String, String>()

    fun getProp(key: String, fallback: String = ""): String {
        val cacheable = key.startsWith("ro.")
        if (cacheable) {
            synchronized(propCache) {
                propCache[key]?.let { return it }
            }
        }
        val value = try {
            val proc = ProcessBuilder("/system/bin/getprop", key).start()
            val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readLine()?.trim() ?: "" }
            proc.waitFor()
            if (out.isNotEmpty()) out else fallback
        } catch (_: Throwable) {
            fallback
        }
        if (cacheable) {
            synchronized(propCache) {
                propCache[key] = value
            }
        }
        return value
    }

    fun normalizeChannel(raw: String): String {
        val low = raw.trim().lowercase(Locale.US)
        return if (low == CHANNEL_IOS_BETA || low == "ios-beta" || low == "beta" || low == "ios") {
            CHANNEL_IOS_BETA
        } else {
            CHANNEL_STABLE
        }
    }

    fun installedChannel(): String =
        normalizeChannel(getProp("ro.ota.channel", CHANNEL_STABLE))

    fun isIosBetaEnabled(ctx: Context): Boolean {
        try {
            val globalVal = Settings.Global.getInt(ctx.contentResolver, SETTING_IOS_STYLE_UI_BETA, -1)
            if (globalVal == 0 || globalVal == 1) {
                return globalVal == 1
            }
        } catch (_: Throwable) {
        }
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_IOS_BETA_ENABLED)) {
            return prefs.getBoolean(KEY_IOS_BETA_ENABLED, false)
        }
        return installedChannel() == CHANNEL_IOS_BETA
    }

    fun desiredChannel(ctx: Context): String =
        if (isIosBetaEnabled(ctx)) CHANNEL_IOS_BETA else CHANNEL_STABLE

    fun setIosBetaEnabled(ctx: Context, enabled: Boolean) {
        try {
            Settings.Global.putInt(ctx.contentResolver, SETTING_IOS_STYLE_UI_BETA, if (enabled) 1 else 0)
        } catch (_: Throwable) {
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IOS_BETA_ENABLED, enabled)
            .remove(KEY_CACHED_JSON)
            .apply()

        clearStagedPackages(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm?.cancel(NOTIF_ID_AVAILABLE)
        nm?.cancel(NOTIF_ID_READY_TO_INSTALL)
    }

    fun clearStagedPackages(ctx: Context) {
        val dirs = listOf(
            File(OTA_RAW_DIR_PATH),
            File(OTA_DIR_PATH),
            resolveStageDir(ctx)
        )
        for (d in dirs) {
            for (fn in listOf("update.zip", "update.sha256", "update.zip.part", "update.part.meta", "install.ready", "apply.status")) {
                try {
                    File(d, fn).delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun localBuildDatetime(): Long {
        val otaTs = getProp("ro.ota.datetime", "").toLongOrNull()
        if (otaTs != null && otaTs > 0L) return otaTs
        val utcTs = getProp("ro.build.date.utc", "").toLongOrNull()
        return utcTs ?: (Build.TIME / 1000L)
    }

    fun localBuildVersion(): String =
        getProp("ro.ota.version", getProp("ro.build.display.id", Build.DISPLAY))

    fun displayVersion(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "v1.0"
        if (trimmed.equals("Local OTA ZIP", ignoreCase = true) ||
            trimmed.equals("Local ZIP", ignoreCase = true)
        ) {
            return "Local ZIP"
        }
        val stripped = trimmed
            .replace(Regex("^(17\\.0-PixelOS-|a17-pixelos-|pixelos-stable-|ios-beta-|PixelOS-|Android\\s*17\\s*)", RegexOption.IGNORE_CASE), "")
            .trim()
        if (stripped.isEmpty() ||
            stripped == "17.0" ||
            stripped.matches(Regex("^\\d{8}(-\\d{4})?$")) ||
            stripped.contains("CP3A", ignoreCase = true) ||
            stripped.contains("gsi_gms", ignoreCase = true)
        ) {
            return "v1.0"
        }
        if (stripped.startsWith("v", ignoreCase = true) && stripped.length >= 2 && stripped[1].isDigit()) {
            return "v" + stripped.substring(1)
        }
        if (stripped[0].isDigit()) {
            return "v$stripped"
        }
        return stripped
    }

    fun localSecurityPatch(): String =
        getProp("ro.build.version.security_patch", Build.VERSION.SECURITY_PATCH ?: "Unknown")

    fun localKernelVersion(): String = System.getProperty("os.version") ?: "4.14.356"

    private fun isLegacyRepoOrUrl(value: String): Boolean {
        val low = value.lowercase(Locale.US)
        return low.contains("ios-rmx1931") || low.contains("ios-26-hot") || low.contains("ios-roms")
    }

    fun activeRepo(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val custom = prefs.getString(KEY_CUSTOM_REPO, "")?.trim().orEmpty()
        if (custom.isNotEmpty() && !isLegacyRepoOrUrl(custom)) return custom
        val propRepo = getProp("ro.ota.repo", DEFAULT_GITHUB_REPO).trim()
        return if (propRepo.isNotEmpty() && !isLegacyRepoOrUrl(propRepo)) propRepo else DEFAULT_GITHUB_REPO
    }

    fun candidateManifestUrls(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customUrl = prefs.getString(KEY_CUSTOM_URL, "")?.trim().orEmpty()
        if (customUrl.isNotEmpty() && !isLegacyRepoOrUrl(customUrl)) {
            return listOf(customUrl)
        }
        val repo = activeRepo(ctx)
        return if (desiredChannel(ctx) == CHANNEL_IOS_BETA) {
            listOf("https://raw.githubusercontent.com/$repo/main/ota/ios-beta/rmx1931.json")
        } else {
            listOf(
                "https://raw.githubusercontent.com/$repo/main/ota/stable/rmx1931.json",
                "https://raw.githubusercontent.com/$repo/main/ota/rmx1931.json"
            )
        }
    }

    fun activeManifestUrl(ctx: Context): String =
        candidateManifestUrls(ctx).first()

    private fun openFollowingRedirects(urlStr: String, rangeStart: Long = 0L): HttpURLConnection {
        var current = urlStr
        for (hop in 0 until 8) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "PixelOS-OTA/17.0 (RMX1931; Android 17)")
                setRequestProperty("Accept", "application/json, application/octet-stream, */*")
                setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
                setRequestProperty("Pragma", "no-cache")
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
            val code = conn.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location") ?: break
                val resolved = URL(URL(current), loc).toString()
                conn.disconnect()
                current = resolved
                continue
            }
            return conn
        }
        throw IllegalStateException("Too many HTTP redirects for $urlStr")
    }

    private fun httpGetText(urlStr: String): String {
        val conn = openFollowingRedirects(urlStr)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from $urlStr")
            }
            return BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun checkLatestRelease(ctx: Context): OtaRelease? {
        val wantChannel = desiredChannel(ctx)
        val urls = candidateManifestUrls(ctx)
        val repo = activeRepo(ctx)
        var lastErr: Throwable? = null

        // 1. Try channel-specific repo manifests with CDN cache-busting query param
        for (manifestUrl in urls) {
            val sep = if (manifestUrl.contains('?')) '&' else '?'
            val uncachedManifestUrl = "${manifestUrl}${sep}cb=${System.currentTimeMillis()}"
            try {
                val txt = httpGetText(uncachedManifestUrl)
                parseManifestJson(txt, manifestUrl, wantChannel)?.let { rel ->
                    saveCheckTimestamp(ctx, rel)
                    return rel
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }

        // 2. Fallback to GitHub /releases/latest ONLY for Regular Stable PixelOS (never for iOS Beta)
        if (wantChannel == CHANNEL_STABLE) {
            try {
                val ghUrl = "https://api.github.com/repos/$repo/releases/latest"
                val txt = httpGetText(ghUrl)
                parseGitHubReleaseJson(txt, repo)?.let { rel ->
                    saveCheckTimestamp(ctx, rel)
                    return rel
                }
            } catch (t: Throwable) {
                if (lastErr == null) lastErr = t
            }
        }

        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
            .apply()

        if (lastErr != null && lastErr.message?.contains("HTTP 404") != true) {
            throw lastErr
        }
        return null
    }

    fun saveCachedRelease(ctx: Context, rel: OtaRelease) {
        saveCheckTimestamp(ctx, rel)
    }

    private fun saveCheckTimestamp(ctx: Context, rel: OtaRelease) {
        val cached = JSONObject().apply {
            put("version", rel.version)
            put("filename", rel.filename)
            put("datetime", rel.datetime)
            put("sizeBytes", rel.sizeBytes)
            put("downloadUrl", rel.downloadUrl)
            put("sha256", rel.sha256)
            put("changelog", rel.changelog)
            put("sourceLabel", rel.sourceLabel)
            put("channel", rel.channel)
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
            .putString(KEY_CACHED_JSON, cached.toString())
            .apply()
    }

    fun loadCachedRelease(ctx: Context): OtaRelease? {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_JSON, null) ?: return null
        return try {
            val o = JSONObject(raw)
            OtaRelease(
                version = o.optString("version"),
                filename = o.optString("filename"),
                datetime = o.optLong("datetime"),
                sizeBytes = o.optLong("sizeBytes"),
                downloadUrl = o.optString("downloadUrl"),
                sha256 = o.optString("sha256"),
                changelog = o.optString("changelog"),
                sourceLabel = o.optString("sourceLabel"),
                channel = normalizeChannel(o.optString("channel", CHANNEL_STABLE))
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseManifestJson(raw: String, source: String, defaultChannel: String): OtaRelease? {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("response")
        val item = if (arr != null && arr.length() > 0) arr.getJSONObject(0) else root
        val url = item.optString("url", "").trim()
        val filename = item.optString("filename", "").trim()
        if (url.isEmpty() && filename.isEmpty()) return null
        val sha = item.optString("sha256", item.optString("id", "")).trim().lowercase(Locale.US)
        val inferredChannel = when {
            item.has("channel") -> normalizeChannel(item.optString("channel"))
            source.contains("ios-beta", ignoreCase = true) || url.contains("ios-beta", ignoreCase = true) -> CHANNEL_IOS_BETA
            else -> defaultChannel
        }
        return OtaRelease(
            version = item.optString("version", "17.0"),
            filename = filename.ifEmpty { "RMX1931-Android17-PixelOS-OTA.zip" },
            datetime = item.optLong("datetime", 0L),
            sizeBytes = item.optLong("size", 0L),
            downloadUrl = url,
            sha256 = if (sha.length == 64) sha else "",
            changelog = item.optString("changelog", "Android 17 Pixel OS OTA build for Realme X2 Pro (RMX1931)."),
            sourceLabel = source,
            channel = inferredChannel
        )
    }

    private fun parseGitHubReleaseJson(raw: String, repo: String): OtaRelease? {
        val root = JSONObject(raw)
        if (root.optBoolean("prerelease", false)) return null
        val assets = root.optJSONArray("assets") ?: return null
        var zipObj: JSONObject? = null
        var shaSumsUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "")
            if (name.contains("-OTA", ignoreCase = true) && name.endsWith(".zip", ignoreCase = true)) {
                zipObj = a
            } else if (name.contains("System", ignoreCase = true) && name.endsWith(".zip", ignoreCase = true) && zipObj == null) {
                zipObj = a
            } else if (name.endsWith(".zip", ignoreCase = true) &&
                !name.contains("-mod-", ignoreCase = true) &&
                !name.contains("VendorBoot", ignoreCase = true) &&
                zipObj == null
            ) {
                zipObj = a
            } else if (name.equals("SHA256SUMS.txt", ignoreCase = true)) {
                shaSumsUrl = a.optString("browser_download_url", "")
            }
        }
        val chosen = zipObj ?: return null
        val zipName = chosen.optString("name", "update.zip")
        val dlUrl = chosen.optString("browser_download_url", "")
        if (dlUrl.isEmpty()) return null

        var sha = chosen.optString("digest", "").removePrefix("sha256:").trim().lowercase(Locale.US)
        if (sha.length != 64 && !shaSumsUrl.isNullOrEmpty()) {
            try {
                val sums = httpGetText(shaSumsUrl)
                for (ln in sums.lineSequence()) {
                    val parts = ln.trim().split(Regex("\\s+"))
                    if (parts.size >= 2 && parts[0].length == 64 && parts.last().endsWith(zipName)) {
                        sha = parts[0].lowercase(Locale.US)
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }
        val body = root.optString("body", "").trim()
        if (sha.length != 64) {
            Regex("\\b([a-fA-F0-9]{64})\\b").find(body)?.let {
                sha = it.groupValues[1].lowercase(Locale.US)
            }
        }
        val publishedIso = root.optString("published_at", "")
        val ts = try {
            Instant.parse(publishedIso).epochSecond
        } catch (_: Throwable) {
            0L
        }
        return OtaRelease(
            version = root.optString("tag_name", root.optString("name", "17.0")),
            filename = zipName,
            datetime = ts,
            sizeBytes = chosen.optLong("size", 0L),
            downloadUrl = dlUrl,
            sha256 = if (sha.length == 64) sha else "",
            changelog = body.ifEmpty { "GitHub Release ${root.optString("tag_name")} from $repo" },
            sourceLabel = "github:$repo",
            channel = CHANNEL_STABLE
        )
    }

    fun isChannelSwitch(ctx: Context, rel: OtaRelease? = null): Boolean {
        val want = desiredChannel(ctx)
        if (rel != null && rel.channel != want) return false
        return installedChannel() != want
    }

    fun shouldOfferRelease(ctx: Context, rel: OtaRelease): Boolean {
        if (rel.downloadUrl.isEmpty()) return false
        val want = desiredChannel(ctx)
        if (rel.channel != want) return false
        val localSha = getProp("ro.ota.build_sha256", "").trim().lowercase(Locale.US)
        if (localSha.isNotEmpty() && rel.sha256.isNotEmpty() && localSha == rel.sha256) {
            return false
        }
        if (installedChannel() != want) {
            // Explicit channel switch (Stable <-> iOS Style UI Beta) is always offered regardless of timestamp
            return true
        }
        return rel.datetime > localBuildDatetime()
    }

    fun isNewerThanInstalled(rel: OtaRelease): Boolean {
        val localTs = localBuildDatetime()
        val localSha = getProp("ro.ota.build_sha256", "").trim().lowercase(Locale.US)
        if (localSha.isNotEmpty() && rel.sha256.isNotEmpty() && localSha == rel.sha256) {
            return false
        }
        return rel.datetime > localTs && rel.downloadUrl.isNotEmpty()
    }

    fun resolveStageDir(ctx: Context): File {
        for (dirPath in listOf(OTA_RAW_DIR_PATH, OTA_DIR_PATH)) {
            val cand = File(dirPath)
            try {
                if (!cand.exists()) cand.mkdirs()
                val probe = File(cand, ".probe_${Process.myPid()}_${Thread.currentThread().id}")
                FileOutputStream(probe).use { it.write(0x4F) }
                probe.delete()
                return cand
            } catch (_: Throwable) {
            }
        }
        val deCtx = ctx.createDeviceProtectedStorageContext()
        val base = deCtx.filesDir ?: ctx.filesDir
        base.parentFile?.setExecutable(true, false)
        base.setExecutable(true, false)
        val fallback = File(base, "ota_stage")
        if (!fallback.exists()) fallback.mkdirs()
        fallback.setReadable(true, false)
        fallback.setExecutable(true, false)
        return fallback
    }

    fun stagedZipFile(ctx: Context): File = File(resolveStageDir(ctx), "update.zip")
    fun stagedShaFile(ctx: Context): File = File(resolveStageDir(ctx), "update.sha256")
    fun partZipFile(ctx: Context): File = File(resolveStageDir(ctx), "update.zip.part")
    fun partMetaFile(ctx: Context): File = File(resolveStageDir(ctx), "update.part.meta")

    private fun releasePartSignature(rel: OtaRelease): String =
        "${rel.channel}|${rel.sha256.lowercase(Locale.US)}|${rel.sizeBytes}|${rel.downloadUrl}"

    fun resumableBytesOnDisk(ctx: Context, rel: OtaRelease): Long {
        val part = partZipFile(ctx)
        if (!part.isFile || part.length() <= 0L) return 0L
        if (rel.sizeBytes > 0L && part.length() > rel.sizeBytes) return 0L
        val meta = partMetaFile(ctx)
        if (meta.isFile) {
            val saved = try {
                meta.readText().trim()
            } catch (_: Throwable) {
                ""
            }
            if (saved.isNotEmpty() && saved != releasePartSignature(rel)) return 0L
        }
        return part.length()
    }

    fun isAlreadyVerifiedOnDisk(ctx: Context, rel: OtaRelease): Boolean {
        val zip = stagedZipFile(ctx)
        val shaF = stagedShaFile(ctx)
        if (!zip.isFile || zip.length() <= 0L) return false
        if (rel.sizeBytes > 0L && zip.length() != rel.sizeBytes) return false
        if (rel.sha256.isNotEmpty()) {
            val savedSha = if (shaF.isFile) shaF.readText().trim().lowercase(Locale.US) else ""
            return savedSha == rel.sha256.lowercase(Locale.US)
        }
        return true
    }

    fun downloadAndVerify(
        ctx: Context,
        rel: OtaRelease,
        isCancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long, speedBps: Long, phase: String) -> Unit
    ): File {
        if (isAlreadyVerifiedOnDisk(ctx, rel)) {
            return stagedZipFile(ctx)
        }

        val dir = resolveStageDir(ctx)
        if (!dir.exists()) dir.mkdirs()

        val part = File(dir, "update.zip.part")
        val meta = File(dir, "update.part.meta")
        val expectedSig = releasePartSignature(rel)

        if (part.exists()) {
            val savedSig = if (meta.isFile) {
                try {
                    meta.readText().trim()
                } catch (_: Throwable) {
                    ""
                }
            } else {
                ""
            }
            val sizeInvalid = rel.sizeBytes > 0L && part.length() > rel.sizeBytes
            val sigMismatch = savedSig.isNotEmpty() && savedSig != expectedSig
            if (sizeInvalid || sigMismatch) {
                part.delete()
                meta.delete()
            }
        }
        try {
            meta.writeText(expectedSig + "\n")
            meta.setReadable(true, false)
        } catch (_: Throwable) {
        }

        var consecutiveIoErrors = 0
        val maxRetries = 6
        while (true) {
            if (isCancelled()) {
                throw InterruptedException("Download paused")
            }

            val existingLen = if (part.isFile && part.length() > 0L) part.length() else 0L
            if (rel.sizeBytes > 0L && existingLen == rel.sizeBytes) {
                onProgress(existingLen, rel.sizeBytes, 0L, "Verifying SHA-256 integrity…")
                break
            }

            var attemptStartBytes = existingLen
            var written = existingLen
            try {
                val conn = openFollowingRedirects(rel.downloadUrl, existingLen)
                try {
                    val code = conn.responseCode
                    if (code == 416 && rel.sizeBytes > 0L && existingLen == rel.sizeBytes) {
                        break
                    }
                    val appending = (code == 206 && existingLen > 0L)
                    if (code !in 200..299) {
                        throw IOException("HTTP $code while downloading update")
                    }
                    attemptStartBytes = if (appending) existingLen else 0L
                    written = attemptStartBytes
                    val contentLen = conn.contentLengthLong
                    val totalBytes = when {
                        rel.sizeBytes > 0L -> rel.sizeBytes
                        contentLen > 0L -> attemptStartBytes + contentLen
                        else -> -1L
                    }

                    var windowBytes = 0L
                    var windowStartMs = System.currentTimeMillis()
                    var speedBps = 0L

                    BufferedInputStream(conn.inputStream, 131072).use { input ->
                        FileOutputStream(part, appending).use { output ->
                            val buf = ByteArray(131072)
                            while (true) {
                                if (isCancelled()) {
                                    output.fd.sync()
                                    throw InterruptedException("Download paused")
                                }
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                written += n
                                windowBytes += n
                                val now = System.currentTimeMillis()
                                val elapsed = now - windowStartMs
                                if (elapsed >= 400L) {
                                    speedBps = (windowBytes * 1000L) / elapsed
                                    windowBytes = 0L
                                    windowStartMs = now
                                    onProgress(written, totalBytes, speedBps, "Downloading system update…")
                                }
                            }
                            output.fd.sync()
                        }
                    }

                    if (rel.sizeBytes > 0L && part.length() < rel.sizeBytes) {
                        throw IOException("Connection closed early at ${formatBytes(part.length())} / ${formatBytes(rel.sizeBytes)}")
                    }
                    onProgress(written, totalBytes, speedBps, "Verifying SHA-256 integrity…")
                    break
                } finally {
                    conn.disconnect()
                }
            } catch (ie: InterruptedException) {
                throw ie
            } catch (ioe: IOException) {
                if (isCancelled()) {
                    throw InterruptedException("Download paused")
                }
                if (written >= attemptStartBytes + 262144L) {
                    consecutiveIoErrors = 0
                }
                consecutiveIoErrors++
                if (consecutiveIoErrors > maxRetries) {
                    throw ioe
                }
                val curBytes = if (part.isFile) part.length() else 0L
                onProgress(
                    curBytes,
                    if (rel.sizeBytes > 0L) rel.sizeBytes else -1L,
                    0L,
                    "Reconnecting to resume download ($consecutiveIoErrors/$maxRetries)…"
                )
                val waitMs = (consecutiveIoErrors * 2000L).coerceAtMost(10000L)
                var slept = 0L
                while (slept < waitMs) {
                    if (isCancelled()) throw InterruptedException("Download paused")
                    Thread.sleep(200L)
                    slept += 200L
                }
            }
        }

        val actualSha = computeSha256(part) { done, tot ->
            onProgress(done, tot, 0L, "Verifying SHA-256 package signature…")
        }
        if (rel.sha256.isNotEmpty() && !actualSha.equals(rel.sha256, ignoreCase = true)) {
            part.delete()
            meta.delete()
            throw SecurityException("SHA-256 mismatch! Expected ${rel.sha256.take(12)}…, got ${actualSha.take(12)}…")
        }

        val dest = File(dir, "update.zip")
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        meta.delete()
        dest.setReadable(true, false)
        File(dir, "update.sha256").apply {
            writeText(actualSha + "\n")
            setReadable(true, false)
        }
        return dest
    }

    fun stageLocalUri(
        ctx: Context,
        uri: Uri,
        onProgress: (done: Long, total: Long, phase: String) -> Unit
    ): Pair<File, String> {
        val dir = resolveStageDir(ctx)
        if (!dir.exists()) dir.mkdirs()
        val part = File(dir, "update.zip.part")
        if (part.exists()) part.delete()

        val md = MessageDigest.getInstance("SHA-256")
        var written = 0L
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(part).use { output ->
                val buf = ByteArray(131072)
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    md.update(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 300L) {
                        lastReport = now
                        onProgress(written, -1L, "Copying & hashing local OTA package…")
                    }
                }
                output.fd.sync()
            }
        } ?: throw IllegalStateException("Unable to open selected ZIP file")

        if (written < 1024L) {
            part.delete()
            throw IllegalStateException("Selected file is empty or invalid")
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        val dest = File(dir, "update.zip")
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        dest.setReadable(true, false)
        File(dir, "update.sha256").apply {
            writeText(sha + "\n")
            setReadable(true, false)
        }
        return dest to sha
    }

    fun computeSha256(file: File, onProgress: (Long, Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var done = 0L
        var last = 0L
        FileInputStream(file).use { fis ->
            val buf = ByteArray(131072)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
                done += n
                val now = System.currentTimeMillis()
                if (now - last >= 350L) {
                    last = now
                    onProgress(done, total)
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun triggerUnattendedRecoveryInstall(ctx: Context) {
        val stageDir = resolveStageDir(ctx)
        val zip = File(stageDir, "update.zip")
        if (!zip.isFile || zip.length() <= 0L) {
            throw IllegalStateException("No verified OTA package found at ${zip.absolutePath}")
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PRE_OTA_DATETIME, localBuildDatetime())
            .putString(KEY_PRE_OTA_SHA, getProp("ro.ota.build_sha256", ""))
            .putString(KEY_PRE_OTA_CHANNEL, installedChannel())
            .commit()

        val statusFile = File(stageDir, "apply.status")
        if (statusFile.exists()) statusFile.delete()

        val readyFlag = File(stageDir, "install.ready")
        readyFlag.writeText("INSTALL\n")
        readyFlag.setReadable(true, false)

        // Allow up to 180s for root ota_watcher + samurai_ota_bcb to map raw UFS blocks & arm misc BCB
        var stagedOk = false
        val deadline = System.currentTimeMillis() + 180000L
        while (System.currentTimeMillis() < deadline) {
            if (statusFile.isFile) {
                val st = try {
                    statusFile.readText().trim()
                } catch (_: Throwable) {
                    "READY"
                }
                if (st.startsWith("ERROR:")) {
                    throw IllegalStateException(st)
                }
                if (st == "READY") {
                    stagedOk = true
                    break
                }
            }
            Thread.sleep(250L)
        }

        if (!stagedOk) {
            throw IllegalStateException("Timed out waiting for OTA BCB stager service (ota_watcher)")
        }

        // Plain reboot preserves bootloader_message ("boot-recovery" + "@/cache/recovery/block.map") across ABL -> TWRP
        Thread.sleep(2500L)
        val pm = ctx.getSystemService(PowerManager::class.java)
        pm?.reboot(null)
    }

    fun ensureNotificationChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "System updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Android 17 Pixel OS & iOS Style UI Beta update notifications"
        }
        val progCh = NotificationChannel(
            PROGRESS_CHANNEL_ID,
            "System update downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing background download and verification progress"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
        nm.createNotificationChannel(progCh)
    }

    fun postReadyToInstallNotification(ctx: Context, versionLabel: String, sizeBytes: Long) {
        ensureNotificationChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_ID_AVAILABLE)
        nm.cancel(NOTIF_ID_DOWNLOADING)
        val pi = PendingIntent.getActivity(
            ctx,
            2,
            Intent(ctx, SystemUpdateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ver = displayVersion(versionLabel)
        val sizePart = if (sizeBytes > 0L) " • ${formatBytes(sizeBytes)}" else ""
        val notif = Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle("System update ready to install")
            .setContentText("Version $ver$sizePart • Tap to restart & install")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_READY_TO_INSTALL, notif)
    }

    fun postDownloadInterruptedNotification(ctx: Context, versionLabel: String, reason: String) {
        ensureNotificationChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val pi = PendingIntent.getActivity(
            ctx,
            3,
            Intent(ctx, SystemUpdateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ver = displayVersion(versionLabel)
        val notif = Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle("System update download paused")
            .setContentText("Version $ver • Tap to resume ($reason)")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_AVAILABLE, notif)
    }

    fun postUpdateAvailableNotification(ctx: Context, rel: OtaRelease) {
        ensureNotificationChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val pi = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, SystemUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ver = displayVersion(rel.version)
        val title = when {
            isChannelSwitch(ctx, rel) && rel.channel == CHANNEL_IOS_BETA -> "iOS Style UI Beta ready to download"
            isChannelSwitch(ctx, rel) -> "Stable PixelOS switch available"
            rel.channel == CHANNEL_IOS_BETA -> "iOS Style UI Beta update available"
            else -> "PixelOS update available"
        }
        val notif = Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle(title)
            .setContentText("Version $ver • ${formatBytes(rel.sizeBytes)}")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_AVAILABLE, notif)
    }

    fun postUpdateCompletedIfUpgraded(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prevTs = prefs.getLong(KEY_PRE_OTA_DATETIME, 0L)
        val prevSha = prefs.getString(KEY_PRE_OTA_SHA, "") ?: ""
        val prevCh = prefs.getString(KEY_PRE_OTA_CHANNEL, "") ?: ""
        if (prevTs <= 0L && prevSha.isEmpty() && prevCh.isEmpty()) return

        val curTs = localBuildDatetime()
        val curSha = getProp("ro.ota.build_sha256", "")
        val curCh = installedChannel()
        val changed = (prevCh.isNotEmpty() && curCh != prevCh) ||
            (prevSha.isNotEmpty() && curSha.isNotEmpty() && curSha != prevSha) ||
            (prevTs > 0L && curTs > prevTs)

        if (changed) {
            prefs.edit()
                .remove(KEY_PRE_OTA_DATETIME)
                .remove(KEY_PRE_OTA_SHA)
                .remove(KEY_PRE_OTA_CHANNEL)
                .apply()
            ensureNotificationChannel(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val pi = PendingIntent.getActivity(
                ctx,
                1,
                Intent(ctx, SystemUpdateActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val ver = displayVersion(localBuildVersion())
            val chLabel = if (curCh == CHANNEL_IOS_BETA) "iOS Style UI Beta" else "PixelOS Stable"
            val notif = Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_system_update)
                .setContentTitle("System update complete")
                .setContentText("Now running $chLabel ($ver)")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID_COMPLETE, notif)
        }
    }

    fun schedulePeriodicCheck(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID_PERIODIC_CHECK) != null) return
        val job = JobInfo.Builder(JOB_ID_PERIODIC_CHECK, ComponentName(ctx, OtaCheckJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6L * 60L * 60L * 1000L)
            .setPersisted(false)
            .build()
        js.schedule(job)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "Unknown size"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }
}
