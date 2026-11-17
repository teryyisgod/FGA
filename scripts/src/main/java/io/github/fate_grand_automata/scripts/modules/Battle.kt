package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.models.BattleObservation
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.models.label
import io.github.fate_grand_automata.scripts.models.toFieldSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@ScriptScope
class Battle @Inject constructor(
    api: IFgoAutomataApi,
    private val servantTracker: ServantTracker,
    private val state: BattleState,
    private val battleConfig: IBattleConfig,
    private val autoSkill: AutoSkill,
    private val caster: Caster,
    private val card: Card,
    private val skillSpam: SkillSpam,
    private val shuffleChecker: ShuffleChecker,
    private val stageTracker: StageTracker,
    private val autoChooseTarget: AutoChooseTarget
) : IFgoAutomataApi by api {
    /**
     * Optional sink for battle observations (AI battle data capture).
     * Null — the default, and always the case for farming — disables
     * capture entirely; installing one never changes any decision.
     */
    var battleObserver: BattleObserver? = null

    private var battleStartObservationPending = true

    init {
        prefs.stopAfterThisRun = false
        state.markStartTime()

        resetState()
    }

    fun resetState() {
        battleStartObservationPending = true
        // Don't increment no. of runs if we're just clicking on quest again and again
        // This can happen due to lags introduced during some events
        if (state.stage != -1) {
            state.nextRun()

            servantTracker.nextRun()

            prefs.updateCompletedRuns(state.runs)
        }

        if (prefs.stopAfterThisRun) {
            prefs.stopAfterThisRun = false
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.StopAfterThisRun)
        }

        if (prefs.selectedServerConfigPref.shouldLimitRuns && state.runs >= prefs.selectedServerConfigPref.limitRuns) {
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.LimitRuns(state.runs))
        }
    }

    fun isIdle() = images[Images.BattleScreen] in locations.battle.screenCheckRegion

    fun clickAttack(): List<ParsedCard> {
        locations.battle.attackClick.click()

        // Wait for Attack button to disappear
        locations.battle.screenCheckRegion.waitVanish(images[Images.BattleScreen], 5.seconds)

        prefs.waitBeforeCards.wait()

        return card.readCommandCards()
    }

    fun performBattle() {
        prefs.waitBeforeTurn.wait()

        onTurnStarted()

        if (battleStartObservationPending) {
            battleStartObservationPending = false
            emitObservation(BattleObservation.EVENT_BATTLE_START, emptyList(), null)
        }

        if (battleConfig.addRaidTurnDelay){
            battleConfig.raidTurnDelaySeconds.seconds.wait()
        }

        servantTracker.beginTurn()

        val npUsage = autoSkill.execute(state.stage, state.turn)
        skillSpam.spamSkills()

        val cards = clickAttack()
            .takeUnless { shouldShuffle(it, npUsage) }
            ?: shuffleCards()

        // Decision point: the dealt cards are known, nothing has been
        // clicked yet. Observation only — the choice below is unchanged.
        emitObservation(BattleObservation.EVENT_TURN_DECISION, cards, npUsage)

        card.clickCommandCards(cards, npUsage)

        0.5.seconds.wait()
    }

    /**
     * Builds and forwards one observation. Wrapped so a capture
     * failure can never break the battle it observes.
     */
    private fun emitObservation(event: String, cards: List<ParsedCard>, npUsage: NPUsage?) {
        val observer = battleObserver ?: return

        runCatching {
            observer.onObservation(
                BattleObservation(
                    event = event,
                    stage = state.stage + 1,
                    turn = state.turn + 1,
                    cards = cards.map { card ->
                        BattleObservation.CardEntry(
                            index = card.card.index,
                            type = card.type.name,
                            affinity = card.affinity.name,
                            servant = card.servant.label(),
                            fieldSlot = card.fieldSlot?.label(),
                            stunned = card.isStunned
                        )
                    },
                    npQueued = npUsage?.nps
                        ?.map { it.toFieldSlot().label() }
                        ?: emptyList(),
                    cardsBeforeNp = npUsage?.cardsBeforeNP,
                    chosenTarget = state.chosenTarget?.label(),
                    servants = servantTracker.deployed.entries
                        .sortedBy { it.key.position }
                        .map { (fieldSlot, teamSlot) ->
                            BattleObservation.ServantEntry(
                                fieldSlot = fieldSlot.label(),
                                teamSlot = teamSlot.label(),
                                isSupport = teamSlot == servantTracker.supportTeamSlot
                            )
                        }
                )
            )
        }
    }

    private fun shouldShuffle(cards: List<ParsedCard>, npUsage: NPUsage): Boolean {
        // Not this wave
        if (state.stage != (battleConfig.shuffleCardsWave - 1)) {
            return false
        }

        // Already shuffled
        if (state.shuffled) {
            return false
        }

        return shuffleChecker.shouldShuffle(
            mode = battleConfig.shuffleCards,
            cards = cards,
            npUsage = npUsage
        )
    }

    private fun shuffleCards(): List<ParsedCard> {
        locations.attack.backClick.click()

        caster.castMasterSkill(Skill.Master.C)
        state.shuffled = true

        return clickAttack()
    }

    private fun onTurnStarted() = useSameSnapIn {
        stageTracker.checkCurrentStage()

        state.nextTurn()

        if (battleConfig.autoChooseTarget) {
            autoChooseTarget.choose()
        }
    }
}
