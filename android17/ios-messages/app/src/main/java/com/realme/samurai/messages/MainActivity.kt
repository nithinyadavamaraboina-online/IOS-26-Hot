package com.realme.samurai.messages

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.realme.samurai.messages.ui.components.GlassCapsule
import com.realme.samurai.messages.ui.components.IosDialogHost
import com.realme.samurai.messages.ui.components.IosIcons
import com.realme.samurai.messages.ui.glass.LocalBackdrop
import com.realme.samurai.messages.ui.glass.backdropSource
import com.realme.samurai.messages.ui.glass.rememberBackdrop
import com.realme.samurai.messages.ui.navigation.MessagesNavGraph
import com.realme.samurai.messages.ui.onboarding.SetupScreen
import com.realme.samurai.messages.ui.theme.IosType
import com.realme.samurai.messages.ui.theme.LiquidMessagesTheme
import com.realme.samurai.messages.ui.theme.LiquidTheme
import com.realme.samurai.messages.util.DefaultSmsAppManager
import com.realme.samurai.messages.util.PermissionManager
import kotlinx.coroutines.launch

/**
 * Single-activity host for Liquid Messages.
 *
 * Responsibilities:
 *  - Install the splash screen and go edge-to-edge.
 *  - Track whether the app is the default SMS handler and whether all runtime
 *    permissions are granted, refreshing both in [onResume] (the user may toggle
 *    either from system settings while we're backgrounded).
 *  - Drive the default-app role request and the runtime-permission request via
 *    the Activity Result APIs.
 *  - Parse incoming SENDTO / VIEW / SEND / SEND_MULTIPLE intents into a deep
 *    link (chat or pre-filled New Message composer) and feed it into the nav graph.
 *
 * Pre-granted system users (`hasPermissions == true`) always open straight into
 * [MessagesNavGraph]; if `!isDefaultSmsApp` while permissioned, a non-blocking
 * floating iOS 26 Liquid Glass banner pill offers a one-tap role restore.
 * [SetupScreen] is only shown when `!hasPermissions && !isDefaultSmsApp`.
 */
class MainActivity : ComponentActivity() {

    // Reactive gates read by the composition; refreshed in onResume.
    private var isDefaultSmsApp by mutableStateOf(false)
    private var hasPermissions by mutableStateOf(false)

    // Set when Android refused the request without the user getting a real
    // choice — typically Android 13+/15+ "restricted settings" for sideloaded
    // apps, where the role dialog never appears or returns instantly. The setup
    // screen then switches to step-by-step "Allow restricted settings" help.
    private var roleBlocked by mutableStateOf(false)
    private var permissionsBlocked by mutableStateOf(false)
    private var roleRequestStartedAt = 0L

    // Deep-link target derived from the launch (or new) intent.
    private var pendingRecipient by mutableStateOf<String?>(null)
    private var pendingBody by mutableStateOf<String?>(null)
    private var pendingAttachments by mutableStateOf<List<Uri>>(emptyList())
    private var pendingOpenNewMessage by mutableStateOf(false)

    // Bumped on every new deep link so tapping a notification from the same (or
    // another) person navigates again even while the app sits in the background.
    private var deepLinkNonce by mutableIntStateOf(0)

    /** Launches the system "make default SMS app" flow; refreshes state on return. */
    private val defaultAppLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Regardless of result code, re-query the authoritative system state.
            refreshGates()
            if (isDefaultSmsApp) {
                roleBlocked = false
                // The role auto-grants SMS permissions; ask for the optional ones
                // (contacts, notifications) right away, once.
                requestPermissions()
            } else {
                // Any refusal shows the help card; it also covers the silent
                // "restricted setting" block where no dialog was ever shown.
                roleBlocked = true
                val elapsed = SystemClock.elapsedRealtime() - roleRequestStartedAt
                Log.i(TAG, "Default-SMS role not granted (returned after ${elapsed}ms)")
            }
        }

    /** Requests the runtime permissions; refreshes state on return. */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshGates()
            permissionsBlocked = !hasPermissions &&
                PermissionManager.requiredPermanentlyDenied(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate / setContentView for the backport.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Draw behind the system bars; the theme keeps them transparent.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        refreshGates()
        // Only on a fresh launch: after a rotation the nav state is restored as-is.
        if (savedInstanceState == null) {
            parseDeepLink(intent)
            if (isDefaultSmsApp && !hasPermissions) {
                requestPermissions()
            }
        }

        setContent {
            LiquidMessagesTheme {
                Surface(color = LiquidTheme.colors.listBackground) {
                  IosDialogHost {
                    val showSetup = !hasPermissions && !isDefaultSmsApp
                    if (showSetup) {
                        SetupScreen(
                            isDefault = isDefaultSmsApp,
                            hasPermissions = hasPermissions,
                            roleBlocked = roleBlocked,
                            permissionsBlocked = permissionsBlocked,
                            onRequestDefault = ::requestDefaultSmsApp,
                            onRequestPermissions = ::requestPermissions,
                            onOpenAppSettings = { PermissionManager.openAppSettings(this) },
                        )
                    } else {
                        val showDefaultBanner = hasPermissions && !isDefaultSmsApp
                        MessagesNavGraph(
                            startWithRecipient = pendingRecipient,
                            startWithBody = pendingBody,
                            startWithAttachments = pendingAttachments,
                            openNewMessage = pendingOpenNewMessage,
                            onShareConsumed = {
                                pendingOpenNewMessage = false
                                pendingBody = null
                                pendingAttachments = emptyList()
                            },
                            deepLinkNonce = deepLinkNonce,
                            showDefaultSmsBanner = showDefaultBanner,
                            onRequestDefaultSms = ::requestDefaultSmsApp,
                        )
                    }
                  }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user can change the default app / permissions from settings while
        // we're backgrounded, so re-sync whenever we return to the foreground.
        refreshGates()
        if (isDefaultSmsApp) {
            roleBlocked = false
            pruneOldMessages()
        }
        if (hasPermissions) permissionsBlocked = false
    }

    /**
     * Handles relaunch via singleTop: a fresh SENDTO/VIEW/SEND intent arrives
     * here rather than through onCreate, so re-parse it for the deep link.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseDeepLink(intent)
    }

    /** iOS "Keep Messages": drops anything older than the chosen window. */
    private fun pruneOldMessages() {
        // Recently Deleted keeps things for 30 days, then deletes them for good.
        lifecycleScope.launch { appContainer.smsRepository.purgeExpired() }
        val days = appContainer.appSettings.keepMessages.value.days ?: return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        lifecycleScope.launch { appContainer.smsRepository.deleteOlderThan(cutoff) }
    }

    /** Re-reads the authoritative default-app + permission state into the gates. */
    private fun refreshGates() {
        isDefaultSmsApp = DefaultSmsAppManager.isDefaultSmsApp(this)
        hasPermissions = PermissionManager.hasRequiredPermissions(this)
    }

    /** Starts the platform-appropriate "set as default SMS app" request. */
    private fun requestDefaultSmsApp() {
        val intent = DefaultSmsAppManager.requestIntent(this)
        if (intent == null) {
            roleBlocked = true
            return
        }
        roleRequestStartedAt = SystemClock.elapsedRealtime()
        runCatching { defaultAppLauncher.launch(intent) }.onFailure {
            Log.w(TAG, "Could not start the default-SMS request", it)
            roleBlocked = true
        }
    }

    /**
     * Requests whatever is still missing: the required SMS permissions (normally
     * already granted by the role) plus the optional ones, the latter only once
     * so a "Don't allow" is respected instead of re-prompting on every launch.
     */
    private fun requestPermissions() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val askOptional = !prefs.getBoolean(KEY_ASKED_OPTIONAL, false)
        val toAsk = PermissionManager.missingRequired(this) +
            (if (askOptional) PermissionManager.missingOptional(this) else emptyList())
        if (toAsk.isEmpty()) {
            refreshGates()
            return
        }
        if (askOptional) prefs.edit().putBoolean(KEY_ASKED_OPTIONAL, true).apply()
        permissionLauncher.launch(toAsk.toTypedArray())
    }

    /**
     * Derives a recipient, body, and/or shared media from an incoming intent:
     *  - ACTION_SENDTO / ACTION_VIEW / ACTION_SEND with an `sms:`/`smsto:`/`mms:`/`mmsto:`
     *    URI opens that recipient's chat pre-filled with `sms_body`, [Intent.EXTRA_TEXT],
     *    or `?body=`.
     *  - ACTION_SEND (`text/plain` or image MIME) and ACTION_SEND_MULTIPLE (image MIME)
     *    without an `sms:` recipient open the New Message composer pre-filled with
     *    [pendingBody] and [pendingAttachments].
     *
     * Clears pending values on plain launcher taps so stale deep links never re-fire.
     */
    private fun parseDeepLink(intent: Intent?) {
        val action = intent?.action
        if (intent == null || action !in SUPPORTED_DEEP_LINK_ACTIONS) {
            pendingRecipient = null
            pendingBody = null
            pendingAttachments = emptyList()
            pendingOpenNewMessage = false
            return
        }

        val recipient = recipientFromSmsUri(intent.data)?.takeIf { it.isNotBlank() }
        val body = (
            intent.getStringExtra("sms_body")
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: bodyFromSmsUri(intent.data)
            )?.takeIf { it.isNotBlank() }
        val streams = when (action) {
            Intent.ACTION_SEND -> extractSharedUris(intent, multiple = false)
            Intent.ACTION_SEND_MULTIPLE -> extractSharedUris(intent, multiple = true)
            else -> emptyList()
        }

        pendingRecipient = recipient
        pendingBody = body
        pendingAttachments = streams

        if (recipient != null) {
            pendingOpenNewMessage = false
            deepLinkNonce++
        } else if (body != null || streams.isNotEmpty()) {
            pendingOpenNewMessage = true
            deepLinkNonce++
        } else {
            pendingOpenNewMessage = false
        }
    }

    private fun extractSharedUris(intent: Intent, multiple: Boolean): List<Uri> {
        val out = ArrayList<Uri>(4)
        if (multiple) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let(out::addAll)
        } else {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let(out::add)
        }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i)?.uri ?: continue
                if (uri.scheme?.lowercase() !in SMS_SCHEMES) out.add(uri)
            }
        }
        return out.distinct().take(5)
    }

    /**
     * Extracts the recipient phone number from an `sms:`/`smsto:`/`mms:`/`mmsto:`
     * URI. The number lives in the scheme-specific part, e.g. `smsto:+15551234567`,
     * and may be URL-encoded or carry a trailing `?body=` query we strip off.
     */
    private fun recipientFromSmsUri(data: Uri?): String? {
        if (data == null) return null
        val scheme = data.scheme?.lowercase() ?: return null
        if (scheme !in SMS_SCHEMES) return null

        // schemeSpecificPart is everything after "smsto:" up to a '#'; drop any
        // "?body=..." query the sender may have appended.
        val raw = data.schemeSpecificPart?.substringBefore('?')?.trim()
        if (raw.isNullOrBlank()) return null

        // Some senders pass multiple recipients separated by ',' or ';'; take the
        // first for a single-thread compose.
        val first = raw.split(',', ';').firstOrNull()?.trim()
        if (first.isNullOrBlank()) return null
        return Uri.decode(first).takeIf { it.isNotBlank() }
    }

    /** Reads an optional `?body=` query parameter from an `sms:`/`smsto:` URI. */
    private fun bodyFromSmsUri(data: Uri?): String? {
        if (data == null) return null
        val scheme = data.scheme?.lowercase() ?: return null
        if (scheme !in SMS_SCHEMES) return null
        val ssp = data.schemeSpecificPart ?: return null
        val query = ssp.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = Uri.decode(pair.substring(0, eq)).lowercase()
            if (key == "body") {
                return Uri.decode(pair.substring(eq + 1))
            }
        }
        return null
    }

    private companion object {
        const val TAG = "MainActivity"
        const val PREFS = "setup"
        const val KEY_ASKED_OPTIONAL = "asked_optional_permissions"
        val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")
        val SUPPORTED_DEEP_LINK_ACTIONS = setOf(
            Intent.ACTION_SENDTO,
            Intent.ACTION_VIEW,
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE,
        )
    }
}
