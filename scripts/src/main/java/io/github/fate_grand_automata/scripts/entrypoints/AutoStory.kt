package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.ScriptNotify
import io.github.fate_grand_automata.scripts.enums.StoryState
import io.github.fate_grand_automata.scripts.modules.Battle
import io.github.fate_grand_automata.scripts.modules.ConnectionRetry
import io.github.fate_grand_automata.scripts.modules.PartySelection
import io.github.fate_grand_automata.scripts.modules.Refill
import io.github.fate_grand_automata.scripts.modules.StoryMapFinder
import io.github.fate_grand_automata.scripts.modules.Support
import io.github.fate_grand_automata.scripts.modules.Withdraw
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.ExitManager
import io.github.lib_automata.ScriptAbortException
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Auto Story mode: progresses main-story quests back to back.
 *
 * A [StoryState] state machine drives the flow
 * MAP -> QUEST_SELECT -> SUPPORT_SELECT -> PARTY_CONFIRM -> STORY -> BATTLE
 * -> RESULT -> back to MAP, reusing the existing modules for everything the
 * farming mode already does well:
 * [Battle] for combat (skills, NPs, targets, multi-wave), [Support] for
 * support selection, [PartySelection] for the configured party,
 * [ConnectionRetry], [Withdraw] and [Refill] for the error/AP paths.
 *
 * Safety rules (by design):
 * - The machine only taps when the current screen was recognised.
 * - An unrecognised screen is retried a bounded number of times; beyond
 *   that the script stops instead of tapping blindly (when
 *   `story.stopOnError` is on).
 * - Reaching the configured quest limit, running out of AP (when
 *   `story.stopOnApOut` is on) or losing a battle (when `story.stopOnDefeat`
 *   is on) stops the script with a dedicated [ExitReason].
 * - The user stop button works at any time: [ExitManager] aborts waits and
 *   clicks by throwing [ScriptAbortException], exactly like other modes.
 */
@ScriptScope
class AutoStory @Inject constructor(
    exitManager: ExitManager,
    api: IFgoAutomataApi,
    private val battle: Battle,
    private val support: Support,
    private val partySelection: PartySelection,
    private val connectionRetry: ConnectionRetry,
    private val withdraw: Withdraw,
    private val refill: Refill,
    private val storyMapFinder: StoryMapFinder
) : EntryPoint(exitManager), IFgoAutomataApi by api {

    sealed class ExitReason(val cause: Exception? = null) {
        data object Abort : ExitReason()
        class Unexpected(cause: Exception) : ExitReason(cause)
        class MaxQuestsReached(val completed: Int) : ExitReason()
        data object APRanOut : ExitReason()
        data object Defeated : ExitReason()
        data object NextQuestNotFound : ExitReason()
        data object UnknownScreen : ExitReason()
        data object InventoryFull : ExitReason()
        data object SupportSelectionManual : ExitReason()
    }

    class ExitException(val reason: ExitReason) : Exception(reason.cause)

    private class StoryExitException(val reason: ExitReason) : Exception()

    companion object {
        /** Consecutive unrecognised screens tolerated before giving up (stopOnError). */
        const val MAX_UNKNOWN_STREAK = 5

        /** OCR map search runs at most once per this many UNKNOWN detections (it is slow). */
        const val MAP_SEARCH_EVERY = 2
    }

    private var completedQuests = 0

    /** A quest was tapped in the quest list and has not finished yet. */
    private var questActive = false

    /** Battle, story or result was seen since the current quest was selected. */
    private var questProgressed = false

    /** The current quest's completion was already counted. */
    private var questCounted = true

    private var unknownStreak = 0
    private var lastState: StoryState? = null

    override fun script(): Nothing {
        try {
            loop()
        } catch (e: StoryExitException) {
            throw ExitException(e.reason)
        } catch (e: AutoBattle.BattleExitException) {
            // Modules shared with AutoBattle report their exits this way; map
            // the ones Auto Story has an opinion about, stop safely otherwise.
            throw ExitException(
                when (e.reason) {
                    AutoBattle.ExitReason.APRanOut -> ExitReason.APRanOut
                    AutoBattle.ExitReason.SupportSelectionManual -> ExitReason.SupportSelectionManual
                    AutoBattle.ExitReason.WithdrawDisabled -> ExitReason.Defeated
                    else -> ExitReason.Unexpected(
                        IllegalStateException("Battle module exited: ${e.reason}")
                    )
                }
            )
        } catch (_: ScriptAbortException) {
            throw ExitException(ExitReason.Abort)
        } catch (e: Exception) {
            throw ExitException(ExitReason.Unexpected(e))
        }
    }

    override fun pausedStatus() = ExitException(ExitReason.Abort)

    private fun loop(): Nothing {
        while (true) {
            if (completedQuests >= prefs.story.maxQuests) {
                throw StoryExitException(ExitReason.MaxQuestsReached(completedQuests))
            }

            // Sub-screens that must win over the generic detectors below.
            if (handleOverlayScreens()) {
                prefs.story.actionInterval.wait()
                continue
            }

            val state = useSameSnapIn { detectState() }

            if (state != StoryState.UNKNOWN) {
                unknownStreak = 0
            }

            if (prefs.story.debugLog && state != lastState) {
                messages.log(ScriptLog.AutoStoryState(state.name, completedQuests))
            }
            lastState = state

            when (state) {
                StoryState.ERROR -> handleError()
                StoryState.BATTLE -> {
                    questProgressed = true
                    battle.performBattle()
                }

                StoryState.SUPPORT_SELECT -> {
                    support.selectSupport()
                    4.seconds.wait()
                }

                StoryState.PARTY_CONFIRM -> startQuest()
                StoryState.STORY -> {
                    questProgressed = true
                    handleStory()
                }

                StoryState.RESULT -> {
                    questProgressed = true
                    countCompletionIfNeeded()
                    handleResult()
                }

                StoryState.QUEST_SELECT -> selectQuest()
                StoryState.MAP,
                StoryState.LOADING -> {
                    // Transitional: wait for the next recognisable screen.
                    1.seconds.wait()
                }

                StoryState.UNKNOWN -> handleUnknown()
            }

            prefs.story.actionInterval.wait()
        }
    }

    // ------------------------------------------------------------------
    // Screen detection
    // ------------------------------------------------------------------

    private fun detectState(): StoryState = when {
        connectionRetry.needsToRetry() || withdraw.needsToWithdraw() -> StoryState.ERROR
        battle.isIdle() -> StoryState.BATTLE
        isInSupport() -> StoryState.SUPPORT_SELECT
        needsToStorySkip() -> StoryState.STORY
        isInResult() -> StoryState.RESULT
        isInPartyConfirm() -> StoryState.PARTY_CONFIRM
        isInQuestList() -> StoryState.QUEST_SELECT
        locations.story.loadingCheckRegion.isBlack() -> StoryState.LOADING
        else -> StoryState.UNKNOWN
    }

    /** Party confirmation shows the "Start Quest" button (start_quest.png). */
    private fun isInPartyConfirm() =
        images[Images.StartQuest] in locations.story.startQuestRegion

    /** The quest list is the same screen AutoBattle's menu() handles. */
    private fun isInQuestList() = images[Images.Menu] in locations.menuScreenRegion

    private fun needsToStorySkip() =
        locations.menuStorySkipRegion.exists(images[Images.StorySkip], similarity = 0.7)

    private fun isInResult(): Boolean {
        val cases = sequenceOf(
            images[Images.Result] to locations.resultScreenRegion,
            images[Images.MasterLevelUp] to locations.resultMasterLvlUpRegion,
            images[Images.MasterExp] to locations.resultMasterExpRegion,
            images[Images.Bond] to locations.resultBondRegion,
            images[Images.MatRewards] to locations.resultMatRewardsRegion,
            images[Images.QuestReward] to locations.resultQuestRewardRegion
        )

        return cases.any { (image, region) -> image in region }
    }

    // ------------------------------------------------------------------
    // Overlay screens (friend requests, interlude close, ...)
    // ------------------------------------------------------------------

    /** Returns true when an overlay screen was handled this iteration. */
    private fun handleOverlayScreens(): Boolean = useSameSnapIn {
        when {
            images[Images.SupportExtra] in locations.resultFriendRequestRegion -> {
                // Friend request after using a non-friend support: decline.
                locations.resultFriendRequestRejectClick.click()
                true
            }

            images[Images.Close] in locations.interludeEndScreenClose -> {
                locations.interludeCloseClick.click()
                true
            }

            images[Images.RankUp] in locations.rankUpRegion -> {
                locations.middleOfScreenClick.click()
                true
            }

            else -> false
        }
    }

    // ------------------------------------------------------------------
    // State handlers
    // ------------------------------------------------------------------

    private fun handleError() {
        when {
            withdraw.needsToWithdraw() -> {
                if (prefs.story.stopOnDefeat) {
                    throw StoryExitException(ExitReason.Defeated)
                }

                // Withdraw and let the flow continue from the map/list.
                withdraw.withdraw()
                questActive = false
                questProgressed = false
                questCounted = true
            }

            connectionRetry.needsToRetry() -> connectionRetry.retry()
        }
    }

    private fun handleStory() {
        if (prefs.story.autoSkip) {
            // Same two taps as AutoBattle.skipStory(): SKIP, then "Yes".
            // The confirmation tap also dismisses the skip-confirm dialog,
            // and a story choice prompt (which also hides the skip button
            // flow) is handled by the state machine re-detecting the screen
            // afterwards instead of tapping blindly.
            locations.menuStorySkipClick.click()
            0.5.seconds.wait()
            locations.menuStorySkipYesClick.click()
        } else {
            // Skip disabled: advance the dialogue with a tap in the lower
            // dialogue area.
            locations.story.dialogueAdvanceClick.click()
        }
    }

    private fun handleResult() {
        locations.resultClick.click(times = 5)
    }

    private fun startQuest() {
        partySelection.selectParty()

        locations.menuStartQuestClick.click()

        2.seconds.wait()

        // AP check right after starting: story quests with an AP cost show
        // the stamina screen here when AP is insufficient.
        if (images[Images.Stamina] in locations.staminaScreenRegion) {
            if (prefs.story.stopOnApOut) {
                // Close the stamina screen before stopping, like a user would.
                locations.staminaCloseClick.click()
                throw StoryExitException(ExitReason.APRanOut)
            }

            refill.refill()
        }
    }

    private fun selectQuest() {
        // Returning to the quest list with a progressed quest means the
        // previous (possibly battle-less) quest finished without a result
        // screen being counted.
        countCompletionIfNeeded()

        if (!prefs.story.autoSelectQuest) {
            // Manual selection: wait for the user to tap a quest; support
            // selection and everything after it is still automated.
            2.seconds.wait()
            return
        }

        battleResetIgnoringFarmingLimits()

        // Click the uppermost quest, exactly like AutoBattle.menu().
        locations.menuSelectQuestClick.click()

        questActive = true
        questProgressed = false
        questCounted = false

        2.5.seconds.wait()

        if (isInventoryFull()) {
            throw StoryExitException(ExitReason.InventoryFull)
        }
    }

    /**
     * The story map is not matched by any template (its art differs per
     * chapter), so an UNKNOWN screen is first given a chance to be the map:
     * [StoryMapFinder] OCR-searches for the "NEXT" node marker. Only when
     * that also fails repeatedly do we treat the screen as an error.
     */
    private fun handleUnknown() {
        ++unknownStreak

        if (prefs.story.autoSelectQuest && unknownStreak % MAP_SEARCH_EVERY == 0) {
            // Back at the map after a finished quest: count it if needed.
            countCompletionIfNeeded()

            val next = storyMapFinder.findNextQuest()
            if (next != null) {
                next.click()
                unknownStreak = 0
                2.seconds.wait()
                return
            }

            if (unknownStreak >= MAX_UNKNOWN_STREAK * MAP_SEARCH_EVERY) {
                throw StoryExitException(ExitReason.NextQuestNotFound)
            }
        }

        if (prefs.story.stopOnError && unknownStreak >= MAX_UNKNOWN_STREAK) {
            throw StoryExitException(ExitReason.UnknownScreen)
        }

        // Unrecognised but tolerated: wait longer for animations/dialogue
        // choices instead of tapping anything.
        3.seconds.wait()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun countCompletionIfNeeded() {
        if (questActive && questProgressed && !questCounted) {
            questCounted = true
            questActive = false
            questProgressed = false
            ++completedQuests

            // Drop farming-mode per-run state so the next battle starts clean.
            battleResetIgnoringFarmingLimits()

            messages.notify(
                ScriptNotify.StoryQuestCompleted(completedQuests, prefs.story.maxQuests)
            )
        }
    }

    /**
     * [Battle.resetState] also enforces the farming run limits; those belong
     * to AutoBattle, not to Auto Story (which has its own quest limit), so
     * its exit exception is ignored here.
     */
    private fun battleResetIgnoringFarmingLimits() {
        try {
            battle.resetState()
        } catch (_: AutoBattle.BattleExitException) {
            // farming limit/stop-after-run: not applicable to story mode
        }
    }
}
