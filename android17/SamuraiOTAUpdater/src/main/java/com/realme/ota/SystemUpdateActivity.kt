package com.realme.ota

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SystemUpdateActivity : Activity() {

    private enum class UiState {
        UP_TO_DATE,
        CHECKING,
        UPDATE_AVAILABLE,
        DOWNLOADING,
        READY_TO_INSTALL
    }

    private var state: UiState = UiState.UP_TO_DATE
    private var availableRelease: OtaRelease? = null
    private var localPackageSha: String? = null
    private var localPackageSize: Long = 0L
    private var suppressBetaSwitchCallback: Boolean = false

    private val serviceListener: (OtaDownloadService.Snapshot) -> Unit = { snap ->
        applyServiceSnapshot(snap, showToasts = true)
    }

    private lateinit var statusBadge: TextView
    private lateinit var headlineText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var betaSwitch: Switch
    private lateinit var betaBadge: TextView
    private lateinit var betaStatusHint: TextView
    private lateinit var detailsCard: LinearLayout
    private lateinit var detailsTitle: TextView
    private lateinit var detailsMeta: TextView
    private lateinit var changelogText: TextView
    private lateinit var progressContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var primaryBtn: Button
    private lateinit var secondaryBtn: Button

    companion object {
        private const val REQ_PICK_LOCAL_ZIP = 901
    }

    private fun isDarkMode(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    private fun sysColor(resName: String, fallback: Int): Int {
        val id = resources.getIdentifier(resName, "color", "android")
        return if (id != 0) getColor(id) else fallback
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundedBg(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OtaEngine.schedulePeriodicCheck(applicationContext)
        OtaEngine.postUpdateCompletedIfUpgraded(applicationContext)
        buildPixelUi()

        val snap = OtaDownloadService.snapshot()
        if (snap.status == OtaDownloadService.Status.RUNNING) {
            applyServiceSnapshot(snap, showToasts = false)
            return
        }

        val cached = OtaEngine.loadCachedRelease(this)
        if (cached != null && OtaEngine.shouldOfferRelease(this, cached)) {
            availableRelease = cached
            state = if (OtaEngine.isAlreadyVerifiedOnDisk(this, cached)) UiState.READY_TO_INSTALL else UiState.UPDATE_AVAILABLE
        }
        refreshUi()
        startCheckForUpdates(userInitiated = false)
    }

    override fun onResume() {
        super.onResume()
        syncBetaSwitchState()
        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        OtaDownloadService.addListener(serviceListener)
        applyServiceSnapshot(OtaDownloadService.snapshot(), showToasts = false)
    }

    override fun onStop() {
        OtaDownloadService.removeListener(serviceListener)
        super.onStop()
    }

    private fun syncBetaSwitchState() {
        if (!::betaSwitch.isInitialized) return
        val want = OtaEngine.isIosBetaEnabled(this)
        if (betaSwitch.isChecked != want) {
            suppressBetaSwitchCallback = true
            betaSwitch.isChecked = want
            suppressBetaSwitchCallback = false
        }
    }

    private fun buildPixelUi() {
        val dark = isDarkMode()
        val bgPage = if (dark) sysColor("system_neutral1_900", Color.parseColor("#111318"))
        else sysColor("system_neutral1_50", Color.parseColor("#F8F9FA"))
        val bgCard = if (dark) sysColor("system_neutral1_800", Color.parseColor("#1E2026"))
        else Color.WHITE
        val strokeCard = if (dark) Color.parseColor("#2E313A") else Color.parseColor("#E0E3E7")
        val textPrimary = if (dark) Color.parseColor("#E3E2E6") else Color.parseColor("#1B1B1F")
        val textSecondary = if (dark) Color.parseColor("#C4C6D0") else Color.parseColor("#44474F")
        val accent = if (dark) sysColor("system_accent1_200", Color.parseColor("#A8C7FA"))
        else sysColor("system_accent1_600", Color.parseColor("#0B57D0"))
        val onAccent = if (dark) sysColor("system_accent1_800", Color.parseColor("#062E6F"))
        else Color.WHITE
        val accentSoft = if (dark) sysColor("system_accent1_800", Color.parseColor("#0842A0"))
        else sysColor("system_accent1_100", Color.parseColor("#D3E3FD"))

        val root = FrameLayout(this).apply {
            setBackgroundColor(bgPage)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top App Bar (Back + Local ZIP Action)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(16), dp(8))
        }
        val backBtn = TextView(this).apply {
            text = "←"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(textPrimary)
            setPadding(dp(12), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        }
        val topSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val localBtn = TextView(this).apply {
            text = "Local ZIP"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(accent)
            background = roundedBg(Color.TRANSPARENT, 18, strokeCard, 1)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { pickLocalOtaZip() }
        }
        topBar.addView(backBtn)
        topBar.addView(topSpacer)
        topBar.addView(localBtn)
        mainCol.addView(topBar)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }

        statusBadge = TextView(this).apply {
            text = "↓"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (dark) accent else onAccent)
            background = roundedBg(if (dark) accentSoft else accent, 24)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                bottomMargin = dp(20)
            }
            setOnLongClickListener {
                showRepoConfigDialog()
                true
            }
        }
        content.addView(statusBadge)

        headlineText = TextView(this).apply {
            text = "Your system is up to date"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(textPrimary)
            setLineSpacing(0f, 1.12f)
        }
        content.addView(headlineText)

        subtitleText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textSecondary)
            setLineSpacing(0f, 1.38f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
        }
        content.addView(subtitleText)

        // Opt-In "iOS Style UI Beta" Channel Card
        val betaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(bgCard, 24, strokeCard, 1)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(20)
            }
        }
        val betaHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val betaTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val betaTitle = TextView(this).apply {
            text = "iOS Style UI Beta"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        }
        betaBadge = TextView(this).apply {
            text = "BETA"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (dark) accent else onAccent)
            background = roundedBg(if (dark) accentSoft else accent, 10)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }
        betaTitleCol.addView(betaTitle)
        betaTitleCol.addView(betaBadge)

        betaSwitch = Switch(this).apply {
            isChecked = OtaEngine.isIosBetaEnabled(this@SystemUpdateActivity)
            setOnCheckedChangeListener { _, isChecked ->
                if (suppressBetaSwitchCallback) return@setOnCheckedChangeListener
                onBetaChannelToggled(isChecked)
            }
        }
        betaHeaderRow.addView(betaTitleCol)
        betaHeaderRow.addView(betaSwitch)

        val betaDesc = TextView(this).apply {
            text = "Opt in to experimental iOS 26 Liquid Glass UI, Notification Shade, Control Center & Lock Screen beta updates. Leave off for quiet, stable PixelOS updates."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textSecondary)
            setLineSpacing(0f, 1.32f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
        betaStatusHint = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(accent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }
        betaCard.addView(betaHeaderRow)
        betaCard.addView(betaDesc)
        betaCard.addView(betaStatusHint)
        content.addView(betaCard)

        // Progress Section
        progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedBg(bgCard, 24, strokeCard, 1)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
            }
        }
        progressLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }
        progressContainer.addView(progressLabel)
        progressContainer.addView(progressBar)
        content.addView(progressContainer)

        // Release / Changelog Card
        detailsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedBg(bgCard, 24, strokeCard, 1)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
            }
        }
        detailsTitle = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        }
        detailsMeta = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(accent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        changelogText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textSecondary)
            setLineSpacing(0f, 1.35f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
        detailsCard.addView(detailsTitle)
        detailsCard.addView(detailsMeta)
        detailsCard.addView(changelogText)
        content.addView(detailsCard)

        scroll.addView(content)
        mainCol.addView(scroll)

        // Bottom Pixel Action Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(20))
        }
        secondaryBtn = Button(this).apply {
            isAllCaps = false
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(accent)
            background = roundedBg(Color.TRANSPARENT, 24, strokeCard, 1)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(12)
            }
            setOnClickListener { onSecondaryAction() }
        }
        primaryBtn = Button(this).apply {
            isAllCaps = false
            text = "Check for update"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(onAccent)
            background = roundedBg(accent, 24)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { onPrimaryAction() }
        }
        bottomBar.addView(secondaryBtn)
        bottomBar.addView(primaryBtn)
        mainCol.addView(bottomBar)

        root.addView(mainCol)
        setContentView(root)
    }

    private fun onBetaChannelToggled(enableBeta: Boolean) {
        if (OtaDownloadService.snapshot().status == OtaDownloadService.Status.RUNNING) {
            OtaDownloadService.pauseDownload(applicationContext)
        }
        val installedCh = OtaEngine.installedChannel()
        if (!enableBeta && installedCh == OtaEngine.CHANNEL_IOS_BETA) {
            AlertDialog.Builder(this)
                .setTitle("Return to Stable PixelOS?")
                .setMessage("Turning off iOS Style UI Beta will offer the latest clean, regular Stable PixelOS build so your phone switches back to pure Google Pixel UI without losing your user data.")
                .setNegativeButton("Stay on Beta") { _, _ ->
                    suppressBetaSwitchCallback = true
                    betaSwitch.isChecked = true
                    suppressBetaSwitchCallback = false
                }
                .setPositiveButton("Switch to Stable") { _, _ ->
                    applyChannelChoice(false)
                }
                .setOnCancelListener {
                    suppressBetaSwitchCallback = true
                    betaSwitch.isChecked = true
                    suppressBetaSwitchCallback = false
                }
                .show()
            return
        }
        applyChannelChoice(enableBeta)
    }

    private fun applyChannelChoice(enableBeta: Boolean) {
        OtaEngine.setIosBetaEnabled(applicationContext, enableBeta)
        OtaDownloadService.resetIdle()
        availableRelease = null
        localPackageSha = null
        state = UiState.UP_TO_DATE
        refreshUi()
        startCheckForUpdates(userInitiated = true)
    }

    private fun formatLastChecked(): String {
        val ms = getSharedPreferences(OtaEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(OtaEngine.KEY_LAST_CHECK_MS, 0L)
        if (ms <= 0L) return "Never"
        return SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))
    }

    private fun buildSystemMetadataSummary(): String {
        val relVer = OtaEngine.getProp("ro.build.version.release", "17")
        val secPatch = OtaEngine.localSecurityPatch()
        val curVer = OtaEngine.displayVersion(OtaEngine.localBuildVersion())
        val installedChLabel = if (OtaEngine.installedChannel() == OtaEngine.CHANNEL_IOS_BETA) {
            "iOS Style UI Beta"
        } else {
            "PixelOS Stable"
        }
        return buildString {
            append("Android version: ").append(relVer).append("\n")
            append("Android security update: ").append(secPatch).append("\n")
            append("Installed build: ").append(curVer).append(" (").append(installedChLabel).append(")\n")
            append("Device: Realme X2 Pro (RMX1931)\n")
            append("Last checked for update: ").append(formatLastChecked())
        }
    }

    private fun refreshUi() {
        subtitleText.text = buildSystemMetadataSummary()
        val betaOn = OtaEngine.isIosBetaEnabled(this)
        val installedBeta = OtaEngine.installedChannel() == OtaEngine.CHANNEL_IOS_BETA
        betaBadge.visibility = if (betaOn) View.VISIBLE else View.GONE
        betaStatusHint.text = when {
            betaOn && !installedBeta -> "Channel selected: iOS Style UI Beta (download below to activate)"
            betaOn && installedBeta -> "Active channel: Receiving iOS Style UI Beta updates"
            !betaOn && installedBeta -> "Channel selected: Stable PixelOS (download below to switch back)"
            else -> "Active channel: Regular Stable PixelOS"
        }

        when (state) {
            UiState.UP_TO_DATE -> {
                statusBadge.text = "✓"
                headlineText.text = "Your system is up to date"
                progressContainer.visibility = View.GONE
                detailsCard.visibility = View.GONE
                secondaryBtn.visibility = View.GONE
                primaryBtn.isEnabled = true
                primaryBtn.text = "Check for update"
            }

            UiState.CHECKING -> {
                statusBadge.text = "↻"
                headlineText.text = "Checking for update…"
                progressContainer.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressLabel.text = if (betaOn) "Checking iOS Style UI Beta channel…" else "Checking Stable PixelOS channel…"
                detailsCard.visibility = View.GONE
                secondaryBtn.visibility = View.GONE
                primaryBtn.isEnabled = false
                primaryBtn.text = "Checking…"
            }

            UiState.UPDATE_AVAILABLE -> {
                val rel = availableRelease
                val switching = OtaEngine.isChannelSwitch(this, rel)
                statusBadge.text = "↓"
                headlineText.text = when {
                    switching && rel?.channel == OtaEngine.CHANNEL_IOS_BETA -> "iOS Style UI Beta available"
                    switching -> "Return to Stable PixelOS"
                    rel?.channel == OtaEngine.CHANNEL_IOS_BETA -> "iOS Style UI Beta update"
                    else -> "System update available"
                }
                progressContainer.visibility = View.GONE
                if (rel != null) {
                    val ver = OtaEngine.displayVersion(rel.version)
                    val chPrefix = if (rel.channel == OtaEngine.CHANNEL_IOS_BETA) "iOS Style UI Beta" else "PixelOS Stable"
                    detailsCard.visibility = View.VISIBLE
                    detailsTitle.text = "$chPrefix $ver"
                    detailsMeta.text = "Update size: ${OtaEngine.formatBytes(rel.sizeBytes)}"
                    changelogText.text = rel.changelog
                }
                val resumed = if (rel != null) OtaEngine.resumableBytesOnDisk(this, rel) else 0L
                secondaryBtn.visibility = View.VISIBLE
                secondaryBtn.text = "Refresh"
                primaryBtn.isEnabled = true
                primaryBtn.text = when {
                    resumed > 0L -> "Resume download (${OtaEngine.formatBytes(resumed)})"
                    switching && rel?.channel == OtaEngine.CHANNEL_IOS_BETA -> "Download & switch to Beta"
                    switching -> "Download & return to Stable"
                    else -> "Download and install"
                }
            }

            UiState.DOWNLOADING -> {
                val snap = OtaDownloadService.snapshot()
                statusBadge.text = "↓"
                headlineText.text = if (snap.localStaging) "Staging local OTA package…" else "Downloading system update…"
                progressContainer.visibility = View.VISIBLE
                detailsCard.visibility = if (availableRelease != null && !snap.localStaging) View.VISIBLE else View.GONE
                if (snap.localStaging) {
                    secondaryBtn.visibility = View.GONE
                } else {
                    secondaryBtn.visibility = View.VISIBLE
                    secondaryBtn.text = "Pause"
                }
                primaryBtn.isEnabled = false
                primaryBtn.text = if (snap.localStaging) "Staging…" else "Downloading…"
            }

            UiState.READY_TO_INSTALL -> {
                statusBadge.text = "✓"
                headlineText.text = "Ready to install"
                progressContainer.visibility = View.GONE
                detailsCard.visibility = View.VISIBLE
                val rel = availableRelease
                if (rel != null) {
                    val ver = OtaEngine.displayVersion(rel.version)
                    val chPrefix = if (rel.channel == OtaEngine.CHANNEL_IOS_BETA) "iOS Style UI Beta" else "PixelOS Stable"
                    detailsTitle.text = "$chPrefix $ver ready"
                    detailsMeta.text = "${OtaEngine.formatBytes(rel.sizeBytes)} • Verified"
                    changelogText.text = "${rel.changelog}\n\nYour phone will reboot into TWRP recovery, atomically flash the verified system image, and restart into Android 17."
                } else {
                    detailsTitle.text = "Local update package ready"
                    detailsMeta.text = "${OtaEngine.formatBytes(localPackageSize)} • Verified"
                    changelogText.text = "Tap 'Restart now' to reboot into TWRP recovery and install automatically."
                }
                secondaryBtn.visibility = View.VISIBLE
                secondaryBtn.text = "Discard"
                primaryBtn.isEnabled = true
                primaryBtn.text = "Restart now"
            }
        }
    }

    private fun onPrimaryAction() {
        when (state) {
            UiState.UP_TO_DATE -> startCheckForUpdates(userInitiated = true)
            UiState.UPDATE_AVAILABLE -> startDownloadUpdate()
            UiState.READY_TO_INSTALL -> confirmAndInstallNow()
            else -> {}
        }
    }

    private fun onSecondaryAction() {
        when (state) {
            UiState.UPDATE_AVAILABLE -> startCheckForUpdates(userInitiated = true)
            UiState.DOWNLOADING -> {
                OtaDownloadService.pauseDownload(applicationContext)
            }
            UiState.READY_TO_INSTALL -> {
                OtaEngine.clearStagedPackages(this)
                OtaDownloadService.resetIdle()
                getSystemService(NotificationManager::class.java)?.cancel(OtaEngine.NOTIF_ID_READY_TO_INSTALL)
                localPackageSha = null
                state = if (availableRelease != null) UiState.UPDATE_AVAILABLE else UiState.UP_TO_DATE
                refreshUi()
            }
            else -> {}
        }
    }

    private fun applyServiceSnapshot(snap: OtaDownloadService.Snapshot, showToasts: Boolean) {
        when (snap.status) {
            OtaDownloadService.Status.RUNNING -> {
                if (snap.release != null) {
                    availableRelease = snap.release
                }
                state = UiState.DOWNLOADING
                refreshUi()
                if (snap.totalBytes > 0L) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = ((snap.downloadedBytes * 1000L) / snap.totalBytes).toInt().coerceIn(0, 1000)
                    val pct = (snap.downloadedBytes * 100L) / snap.totalBytes
                    val spd = if (snap.speedBps > 0L) " • ${OtaEngine.formatBytes(snap.speedBps)}/s" else ""
                    progressLabel.text = "${snap.phaseText} ($pct% • ${OtaEngine.formatBytes(snap.downloadedBytes)} / ${OtaEngine.formatBytes(snap.totalBytes)}$spd)"
                } else {
                    progressBar.isIndeterminate = true
                    progressLabel.text = if (snap.downloadedBytes > 0L) {
                        "${snap.phaseText} (${OtaEngine.formatBytes(snap.downloadedBytes)})"
                    } else {
                        snap.phaseText
                    }
                }
            }

            OtaDownloadService.Status.COMPLETED -> {
                if (snap.localStaging && OtaEngine.stagedZipFile(this).isFile) {
                    availableRelease = null
                    localPackageSha = snap.localSha
                    localPackageSize = snap.localSize
                    state = UiState.READY_TO_INSTALL
                    refreshUi()
                } else {
                    val rel = snap.release ?: availableRelease ?: OtaEngine.loadCachedRelease(this)
                    if (rel != null && OtaEngine.shouldOfferRelease(this, rel) && OtaEngine.isAlreadyVerifiedOnDisk(this, rel)) {
                        availableRelease = rel
                        state = UiState.READY_TO_INSTALL
                        refreshUi()
                    }
                }
            }

            OtaDownloadService.Status.PAUSED -> {
                val wasDownloading = (state == UiState.DOWNLOADING)
                val rel = snap.release ?: availableRelease ?: OtaEngine.loadCachedRelease(this)
                if (rel != null && OtaEngine.shouldOfferRelease(this, rel)) {
                    availableRelease = rel
                    state = if (OtaEngine.isAlreadyVerifiedOnDisk(this, rel)) UiState.READY_TO_INSTALL else UiState.UPDATE_AVAILABLE
                } else if (wasDownloading) {
                    state = UiState.UP_TO_DATE
                }
                refreshUi()
                if (showToasts && wasDownloading) {
                    Toast.makeText(this, "Download paused (will resume when restarted)", Toast.LENGTH_SHORT).show()
                }
            }

            OtaDownloadService.Status.FAILED -> {
                val wasDownloading = (state == UiState.DOWNLOADING)
                val rel = snap.release ?: availableRelease ?: OtaEngine.loadCachedRelease(this)
                if (rel != null && OtaEngine.shouldOfferRelease(this, rel)) {
                    availableRelease = rel
                    state = if (OtaEngine.isAlreadyVerifiedOnDisk(this, rel)) UiState.READY_TO_INSTALL else UiState.UPDATE_AVAILABLE
                } else if (wasDownloading) {
                    state = UiState.UP_TO_DATE
                }
                refreshUi()
                if (showToasts && wasDownloading && !snap.errorMessage.isNullOrEmpty()) {
                    Toast.makeText(this, "Download error: ${snap.errorMessage}", Toast.LENGTH_LONG).show()
                }
            }

            OtaDownloadService.Status.IDLE -> {
                if (state == UiState.DOWNLOADING) {
                    val rel = availableRelease ?: OtaEngine.loadCachedRelease(this)
                    state = if (rel != null && OtaEngine.shouldOfferRelease(this, rel)) {
                        if (OtaEngine.isAlreadyVerifiedOnDisk(this, rel)) UiState.READY_TO_INSTALL else UiState.UPDATE_AVAILABLE
                    } else {
                        UiState.UP_TO_DATE
                    }
                    refreshUi()
                }
            }
        }
    }

    private fun startCheckForUpdates(userInitiated: Boolean) {
        if (state == UiState.DOWNLOADING || state == UiState.CHECKING ||
            OtaDownloadService.snapshot().status == OtaDownloadService.Status.RUNNING
        ) return
        state = UiState.CHECKING
        refreshUi()

        Thread {
            try {
                val ctx = applicationContext
                val rel = OtaEngine.checkLatestRelease(ctx)
                runOnUiThread {
                    if (OtaDownloadService.snapshot().status == OtaDownloadService.Status.RUNNING) {
                        return@runOnUiThread
                    }
                    if (rel != null && OtaEngine.shouldOfferRelease(ctx, rel)) {
                        availableRelease = rel
                        state = if (OtaEngine.isAlreadyVerifiedOnDisk(ctx, rel)) {
                            UiState.READY_TO_INSTALL
                        } else {
                            UiState.UPDATE_AVAILABLE
                        }
                    } else {
                        availableRelease = null
                        state = if (localPackageSha != null && OtaEngine.stagedZipFile(ctx).isFile) {
                            UiState.READY_TO_INSTALL
                        } else {
                            UiState.UP_TO_DATE
                        }
                        if (userInitiated) {
                            val msg = if (OtaEngine.isChannelSwitch(ctx)) {
                                "No release published yet on selected channel"
                            } else {
                                "Your system is up to date"
                            }
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    refreshUi()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    state = if (availableRelease != null) UiState.UPDATE_AVAILABLE else UiState.UP_TO_DATE
                    refreshUi()
                    if (userInitiated) {
                        Toast.makeText(this, "Update check failed: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun startDownloadUpdate() {
        val rel = availableRelease ?: return
        state = UiState.DOWNLOADING
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressLabel.text = "Starting background download…"
        refreshUi()
        OtaDownloadService.startDownload(applicationContext, rel)
    }

    private fun pickLocalOtaZip() {
        if (state == UiState.DOWNLOADING || OtaDownloadService.snapshot().status == OtaDownloadService.Status.RUNNING) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        startActivityForResult(intent, REQ_PICK_LOCAL_ZIP)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_LOCAL_ZIP && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            stageLocalZipAsync(uri)
        }
    }

    private fun stageLocalZipAsync(uri: Uri) {
        state = UiState.DOWNLOADING
        progressBar.isIndeterminate = true
        progressLabel.text = "Staging & verifying local OTA ZIP…"
        refreshUi()
        OtaDownloadService.startLocalStage(applicationContext, uri)
    }

    private fun confirmAndInstallNow() {
        AlertDialog.Builder(this)
            .setTitle("Restart and install update?")
            .setMessage("Your Realme X2 Pro will reboot into recovery, automatically flash the verified Android 17 package, and restart. User data is preserved.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restart now") { _, _ ->
                primaryBtn.isEnabled = false
                primaryBtn.text = "Rebooting…"
                Thread {
                    try {
                        OtaEngine.triggerUnattendedRecoveryInstall(applicationContext)
                    } catch (t: Throwable) {
                        runOnUiThread {
                            primaryBtn.isEnabled = true
                            primaryBtn.text = "Restart now"
                            Toast.makeText(this, "Install trigger failed: ${t.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun showRepoConfigDialog() {
        val prefs = getSharedPreferences(OtaEngine.PREFS_NAME, Context.MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val repoLabel = TextView(this).apply {
            text = "GitHub Repository (owner/repo):"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val repoInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = OtaEngine.DEFAULT_GITHUB_REPO
            setText(prefs.getString(OtaEngine.KEY_CUSTOM_REPO, OtaEngine.activeRepo(this@SystemUpdateActivity)))
        }
        val urlLabel = TextView(this).apply {
            text = "Optional Custom Manifest JSON URL (leave blank for channel default):"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(12), 0, 0)
        }
        val urlInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://raw.githubusercontent.com/<owner>/<repo>/main/ota/stable/rmx1931.json"
            setText(prefs.getString(OtaEngine.KEY_CUSTOM_URL, ""))
        }
        box.addView(repoLabel)
        box.addView(repoInput)
        box.addView(urlLabel)
        box.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("OTA Update Channel")
            .setView(box)
            .setNeutralButton("Reset default") { _, _ ->
                prefs.edit()
                    .remove(OtaEngine.KEY_CUSTOM_REPO)
                    .remove(OtaEngine.KEY_CUSTOM_URL)
                    .apply()
                startCheckForUpdates(userInitiated = true)
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save & Check") { _, _ ->
                prefs.edit()
                    .putString(OtaEngine.KEY_CUSTOM_REPO, repoInput.text.toString().trim())
                    .putString(OtaEngine.KEY_CUSTOM_URL, urlInput.text.toString().trim())
                    .apply()
                startCheckForUpdates(userInitiated = true)
            }
            .show()
    }
}
