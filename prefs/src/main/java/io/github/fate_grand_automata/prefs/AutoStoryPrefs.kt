package io.github.fate_grand_automata.prefs

import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.map
import io.github.fate_grand_automata.scripts.prefs.IAutoStoryPreferences
import kotlin.time.Duration.Companion.milliseconds

internal class AutoStoryPrefs(
    private val prefs: PrefsCore
) : IAutoStoryPreferences {
    override val autoSkip by prefs.autoStoryAutoSkip
    override val autoSelectQuest by prefs.autoStoryAutoSelectQuest
    override val maxQuests by prefs.autoStoryMaxQuests
    override val stopOnApOut by prefs.autoStoryStopOnApOut
    override val stopOnDefeat by prefs.autoStoryStopOnDefeat
    override val useLeylineStoneContinue by prefs.autoStoryUseLeylineStone
    override val stopOnError by prefs.autoStoryStopOnError
    override val actionInterval by prefs.autoStoryActionIntervalMs.map { it.milliseconds }
    override val debugLog by prefs.autoStoryDebugLog
}
