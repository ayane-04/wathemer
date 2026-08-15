package com.wathemer.app.hooks

/** The single decoder of WhatsApp's direction argument, so [BubbleShapes] and [BubbleColors] cannot disagree. */
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
