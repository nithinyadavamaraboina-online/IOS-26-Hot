package com.realme.samurai.messages.ui.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.realme.samurai.messages.ui.chat.ChatScreen
import com.realme.samurai.messages.ui.contactinfo.ContactInfoScreen
import com.realme.samurai.messages.ui.conversations.ConversationListScreen
import com.realme.samurai.messages.ui.settings.SettingsScreen

/** SavedState key: message the chat should jump to when shown again. */
private const val JUMP_TO = "jumpToMessage"

/** Sentinel thread id for a chat opened before its Telephony thread exists. */
private const val NO_THREAD_ID: Long = -1L

/**
 * The app's single navigation graph: conversation list -> chat / new-message.
 *
 * @param navController      hoisted controller (defaults to a fresh one).
 * @param startWithRecipient when non-null (a deep link from ACTION_SENDTO /
 *                           ACTION_SEND), the graph opens straight into a chat
 *                           with this recipient on first composition.
 * @param startWithBody      optional pre-filled message body for that deep link.
 *                           Carried through so the chat composer can pre-populate
 *                           it; included in the deep-link key so a new share with
 *                           the same recipient but different body re-navigates.
 */
@Composable
fun MessagesNavGraph(
    navController: NavHostController = rememberNavController(),
    startWithRecipient: String? = null,
    startWithBody: String? = null,
    startWithAttachments: List<android.net.Uri> = emptyList(),
    openNewMessage: Boolean = false,
    onShareConsumed: () -> Unit = {},
    /** Changes with every new deep link (e.g. a notification tap) so it re-navigates. */
    deepLinkNonce: Int = 0,
    showDefaultSmsBanner: Boolean = false,
    onRequestDefaultSms: () -> Unit = {},
) {
    // Deep-link handling: when launched via SENDTO/VIEW/SEND with a recipient, jump
    // straight to that chat. When launched via ACTION_SEND / ACTION_SEND_MULTIPLE
    // without a recipient (e.g. sharing text/images from another app), pop back to
    // the conversation list and present the New Message composer pre-filled.
    if (!startWithRecipient.isNullOrBlank()) {
        LaunchedEffect(startWithRecipient, startWithBody, startWithAttachments, deepLinkNonce) {
            // Already looking at this person's chat: nothing to do.
            val top = navController.currentBackStackEntry
            val onSameChat = top?.destination?.route == Routes.chatPattern &&
                Routes.decodeAddress(top.arguments?.getString(Routes.argAddress)) == startWithRecipient
            if (!onSameChat) {
                navController.navigate(Routes.chat(NO_THREAD_ID, startWithRecipient)) {
                    // Drop whatever chat / info screen is open so the new person's chat
                    // is really shown (single-top alone kept the previous conversation).
                    // The conversation list stays beneath so Back returns to it.
                    popUpTo(Routes.conversations) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    } else if (openNewMessage) {
        LaunchedEffect(openNewMessage, deepLinkNonce) {
            if (navController.currentBackStackEntry?.destination?.route != Routes.conversations) {
                navController.popBackStack(Routes.conversations, inclusive = false)
            }
        }
    }

    // iOS navigation-stack motion: the new page slides in from the right edge
    // while the old one drifts a third of the way left (and back on pop).
    val push = tween<IntOffset>(durationMillis = 380, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f))
    NavHost(
        navController = navController,
        startDestination = Routes.conversations,
        enterTransition = { slideInHorizontally(push) { it } },
        exitTransition = { slideOutHorizontally(push) { -it / 3 } },
        popEnterTransition = { slideInHorizontally(push) { -it / 3 } },
        popExitTransition = { slideOutHorizontally(push) { it } },
    ) {
        // ---- Conversation list ----
        composable(Routes.conversations) {
            ConversationListScreen(
                onConversationClick = { threadId, address ->
                    navController.navigate(Routes.chat(threadId, address))
                },
                onOpenSettings = { navController.navigate(Routes.settings) },
                onOpenRecentlyDeleted = { navController.navigate(Routes.recentlyDeleted) },
                openNewMessage = openNewMessage && startWithRecipient.isNullOrBlank(),
                initialNewMessageBody = startWithBody,
                initialNewMessageAttachments = startWithAttachments,
                newMessageNonce = deepLinkNonce,
                onNewMessageRequestConsumed = onShareConsumed,
                showDefaultSmsBanner = showDefaultSmsBanner,
                onRequestDefaultSms = onRequestDefaultSms,
            )
        }

        // ---- Chat (single thread) ----
        composable(
            route = Routes.chatPattern,
            arguments = listOf(
                navArgument(Routes.argThreadId) {
                    type = NavType.LongType
                    defaultValue = NO_THREAD_ID
                },
                navArgument(Routes.argAddress) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val threadId = args?.getLong(Routes.argThreadId) ?: NO_THREAD_ID
            val address = Routes.decodeAddress(args?.getString(Routes.argAddress))

            val jump by backStackEntry.savedStateHandle.getStateFlow<Long?>(JUMP_TO, null).collectAsState()
            val isDeepLinkTarget = address == startWithRecipient
            ChatScreen(
                threadId = threadId,
                address = address,
                jumpToMessageId = jump,
                onJumpHandled = { backStackEntry.savedStateHandle[JUMP_TO] = null },
                // Only the deep-linked chat gets the shared body / media.
                initialText = startWithBody.takeIf { isDeepLinkTarget },
                initialAttachments = if (isDeepLinkTarget) startWithAttachments else emptyList(),
                deepLinkNonce = if (isDeepLinkTarget) deepLinkNonce else 0,
                onBack = { navController.popBackStack() },
                onOpenInfo = { id, addr -> navController.navigate(Routes.contactInfo(id, addr)) },
            )
        }

        // ---- Contact info sheet ----
        composable(
            route = Routes.contactInfoPattern,
            arguments = listOf(
                navArgument(Routes.argThreadId) {
                    type = NavType.LongType
                    defaultValue = NO_THREAD_ID
                },
                navArgument(Routes.argAddress) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val threadId = args?.getLong(Routes.argThreadId) ?: NO_THREAD_ID
            val address = Routes.decodeAddress(args?.getString(Routes.argAddress))
            ContactInfoScreen(
                threadId = threadId,
                address = address,
                onBack = { navController.popBackStack() },
                onOpenMessage = { id ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(JUMP_TO, id)
                    navController.popBackStack()
                },
                // The chat underneath no longer exists — go straight back to the list.
                onConversationDeleted = { navController.popBackStack(Routes.conversations, inclusive = false) },
            )
        }

        // ---- Recently Deleted ----
        composable(Routes.recentlyDeleted) {
            com.realme.samurai.messages.ui.deleted.RecentlyDeletedScreen(onBack = { navController.popBackStack() })
        }

        // ---- Settings ----
        composable(Routes.settings) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
