package io.github.fate_grand_automata.scripts.enums

/**
 * States of the Auto Story state machine (see AutoStory entrypoint).
 *
 * The machine never acts on a state it could not confirm from the current
 * screenshot: an unrecognised screen maps to [UNKNOWN], and [UNKNOWN]
 * only ever waits (up to a bounded number of retries) or stops safely.
 */
enum class StoryState {
    /** Story map / quest list (the FGO "menu" screen where a quest can be picked). */
    MAP,

    /** A quest node/banner was tapped and the quest detail is being confirmed. */
    QUEST_SELECT,

    /** Support selection screen. */
    SUPPORT_SELECT,

    /** Party confirmation ("Start Quest") screen. */
    PARTY_CONFIRM,

    /** Story dialogue / cutscene (skippable or advance-by-tap). */
    STORY,

    /** In battle (delegated to the existing Battle module). */
    BATTLE,

    /** Result / bond / drops / quest-reward screens after a battle or a no-battle quest. */
    RESULT,

    /** Loading / black transition screen. */
    LOADING,

    /** A recoverable error screen (connection retry, withdraw, AP refill). */
    ERROR,

    /** Screen could not be recognised. */
    UNKNOWN
}
