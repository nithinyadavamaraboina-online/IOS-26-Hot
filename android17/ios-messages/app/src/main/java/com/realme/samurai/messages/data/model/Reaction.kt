package com.realme.samurai.messages.data.model

/**
 * iMessage-style "Tapback" reactions that can be attached to a single message.
 *
 * SMS has no native reaction channel, so reactions are stored locally (see
 * [com.realme.samurai.messages.data.local.MessageMetaStore]); the [emoji] is what
 * we render in the [com.realme.samurai.messages.ui.chat.reactions.ReactionBadge].
 */
enum class Reaction(val emoji: String, val label: String) {
    LOVE("❤️", "Love"),
    LIKE("👍", "Like"),
    DISLIKE("👎", "Dislike"),
    LAUGH("😂", "Laugh"),
    EMPHASIZE("‼️", "Emphasize"),
    QUESTION("❓", "Question");

    companion object {
        /** Order shown in the tapback bar, left→right, like iMessage. */
        val bar: List<Reaction> = listOf(LOVE, LIKE, DISLIKE, LAUGH, EMPHASIZE, QUESTION)
    }
}
