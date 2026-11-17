package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.models.BattleObservation
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/**
 * Sink for [BattleObservation]s captured inside [Battle].
 *
 * The battle engine only calls this when an observer is installed;
 * Auto Story installs one when its "AI battle data log" setting is on.
 * Farming (AutoBattle) never installs one, so its behaviour is
 * completely unchanged.
 */
fun interface BattleObserver {
    fun onObservation(observation: BattleObservation)
}

/**
 * Logs every observation as JSON (ScriptLog -> Timber -> Logcat) and
 * keeps a bounded in-memory history that Auto Story attaches to its
 * exit dialog, so the data can also be reviewed on-device without a
 * PC. Contains no account data: slots, card types and counters only.
 */
@ScriptScope
class BattleObservationLogger @Inject constructor(
    api: IFgoAutomataApi
) : IFgoAutomataApi by api, BattleObserver {

    private val history = StringBuilder()

    override fun onObservation(observation: BattleObservation) {
        val json = observation.toJson()

        messages.log(ScriptLog.BattleObservation(json))

        synchronized(history) {
            history.appendLine(json)
            if (history.length > MAX_HISTORY_CHARS) {
                history.delete(0, history.length - MAX_HISTORY_CHARS)
            }
        }
    }

    /** The recorded JSON lines (oldest dropped past the cap). */
    fun historyText(): String = synchronized(history) { history.toString() }

    companion object {
        const val MAX_HISTORY_CHARS = 12000
    }
}
