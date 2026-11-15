package io.github.fate_grand_automata.scripts.prefs

import kotlin.time.Duration

/**
 * Settings for the Auto Story mode (see the AutoStory entrypoint).
 *
 * These are deliberately separate from the farming (AutoBattle) settings so
 * that enabling story progression never changes an existing farming setup.
 */
interface IAutoStoryPreferences {
    /** Skip story dialogue automatically (reuses the story-skip flow). */
    val autoSkip: Boolean

    /** Automatically pick the next uncleared quest on the story map / quest list. */
    val autoSelectQuest: Boolean

    /** Stop after this many completed story quests (safety bound for unattended runs). */
    val maxQuests: Int

    /** Stop instead of refilling when AP runs out. Story mode never uses apples/SQ unless the farming refill settings allow it; this forces a stop regardless. */
    val stopOnApOut: Boolean

    /** Stop when a battle is lost (withdraw screen) instead of withdrawing and continuing. */
    val stopOnDefeat: Boolean

    /** On defeat, continue the battle by spending a Leyline Stone (never Command Spells or Saint Quartz). Capped per run in the entrypoint. */
    val useLeylineStoneContinue: Boolean

    /** Stop when an unrecognised screen persists beyond the retry limit. */
    val stopOnError: Boolean

    /** Base delay between two state-machine actions. */
    val actionInterval: Duration

    /** Log every state transition (ScriptLog.AutoStoryState) for debugging. */
    val debugLog: Boolean
}
