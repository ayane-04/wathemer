package com.wathemer.app.hooks

/** Decodes WhatsApp's direction argument for [BubbleShapes] and [BubbleColors], so the two cannot disagree. */
enum class BubbleSide {
    INCOMING,
    OUTGOING,

    /** Belongs to neither party: encryption notice, date separator, system message. */
    CENTERED,
    ;

    companion object {
        /** Unrecognised values map to [CENTERED], never to a side; a guessed side paints a visibly wrong colour. */
        fun of(direction: Int): BubbleSide = when (direction) {
            3 -> OUTGOING
            2 -> INCOMING
            else -> CENTERED
        }
    }
}
