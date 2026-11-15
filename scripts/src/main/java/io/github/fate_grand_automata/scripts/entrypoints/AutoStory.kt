package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.ScriptNotify
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
import io.github.fate_grand_automata.scripts.enums.StoryState
import io.github.fate_grand_automata.scripts.modules.Battle
import io.github.fate_grand_automata.scripts.modules.ConnectionRetry
import io.github.fate_grand_automata.scripts.modules.PartySelection
import io.github.fate_grand_automata.scripts.modules.Refill
import io.github.fate_grand_automata.scripts.modules.StoryMapFinder
import io.github.fate_grand_automata.scripts.modules.Support
import io.github.fate_grand_automata.scripts.modules.SupportScreen
import io.github.fate_grand_automata.scripts.modules.SupportClassPicker
import io.github.fate_grand_automata.scripts.supportSelection.PreferredSupportSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportSelectionResult
import io.github.fate_grand_automata.scripts.modules.Withdraw
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.ExitManager
import io.github.lib_automata.Match
import io.github.lib_automata.Location
import io.github.lib_automata.Swiper
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
    private val storyMapFinder: StoryMapFinder,
    private val supportScreen: SupportScreen,
    private val supportClassPicker: SupportClassPicker,
    private val preferredSelection: PreferredSupportSelection,
    private val swiper: Swiper,
    private val supportPrefs: ISupportPreferences
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

        /** Quest-select passes with no verified tap target tolerated before giving up. */
        const val MAX_NO_TARGET_STREAK = 4

        /** Notice dismissals attempted per unrecognised-screen streak. */
        const val MAX_NOTICE_ATTEMPTS = 5

        /**
         * Consecutive unrecognised screens tolerated while a quest is in
         * progress. Battle intros, attack/NP animations, wave transitions
         * and the victory sequence hide every recognisable UI for seconds
         * at a time, so an unrecognised screen mid-quest means "wait",
         * not "search the map". Each pass also spends time in screen
         * detection, so this covers several minutes of hidden UI.
         */
        const val MAX_IN_QUEST_UNKNOWN_STREAK = 120

        /** Similarity for the "tap to continue" announcement prompt. */
        const val NOTICE_SIMILARITY = 0.7
        /** Max Leyline Stone continues per run (opt-in setting), so an unbeatable boss cannot burn every stone. */
        const val MAX_LEYLINE_CONTINUES = 3

        /** Similarity for the Leyline Stone button caption on the defeat dialog. */
        const val LEYLINE_BUTTON_SIMILARITY = 0.8
        /** Half-screen scroll steps per pass of the preferred-support scan. */
        const val MAX_SCAN_STEPS = 24

        /** List refreshes allowed during the preferred-support scan. */
        const val MAX_SUPPORT_REFRESHES = 2
    }

    private var completedQuests = 0

    /** A quest was tapped in the quest list and has not finished yet. */
    private var questActive = false
    private var leylineContinuesUsed = 0

    /** Battle, story or result was seen since the current quest was selected. */
    private var questProgressed = false

    /** The current quest's completion was already counted. */
    private var questCounted = true

    private var unknownStreak = 0

    /** Consecutive unrecognised screens seen during the current quest. */
    private var inQuestUnknownStreak = 0

    /** Notice dismissals attempted during the current unrecognised streak. */
    private var noticeAttempts = 0

    /** Consecutive quest-select passes where nothing verified could be tapped. */
    private var noTargetStreak = 0

    private var lastState: StoryState? = null

    /**
     * Diagnostic trail, only collected while the debug log setting is on.
     * When the run ends it is attached to the exit dialog so the exact
     * decision path (scores, gate results, tap targets) can be read off
     * the device instead of guessed from offline replays.
     */
    private val trail = StringBuilder()

    private fun trace(msg: String) {
        if (!prefs.story.debugLog) return

        trail.appendLine(msg)
        if (trail.length > 6000) {
            trail.delete(0, trail.length - 6000)
        }
    }

    private fun withTrail(reason: ExitReason): ExitException =
        if (prefs.story.debugLog && reason !is ExitReason.Unexpected) {
            // Keep the original exit reason readable in the dialog: the
            // trail alone cannot distinguish a user abort from a failure.
            ExitException(
                ExitReason.Unexpected(
                    IllegalStateException("DEBUG TRAIL:\n$trail\nEXIT=${reason.javaClass.simpleName}")
                )
            )
        } else {
            ExitException(reason)
        }

    override fun script(): Nothing {
        try {
            loop()
        } catch (e: StoryExitException) {
            throw withTrail(e.reason)
        } catch (e: AutoBattle.BattleExitException) {
            // Modules shared with AutoBattle report their exits this way; map
            // the ones Auto Story has an opinion about, stop safely otherwise.
            throw withTrail(
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
            throw withTrail(ExitReason.Abort)
        } catch (e: Exception) {
            throw ExitException(
                if (prefs.story.debugLog) {
                    ExitReason.Unexpected(
                        IllegalStateException(
                            "DEBUG TRAIL:\n$trail\nERROR=${e.javaClass.simpleName}: ${e.message}"
                        )
                    )
                } else {
                    ExitReason.Unexpected(e)
                }
            )
        }
    }

    override fun pausedStatus() = ExitException(ExitReason.Abort)

    private fun loop(): Nothing {
        storyMapFinder.tracer = ::trace

        while (true) {
            if (completedQuests >= prefs.story.maxQuests) {
                throw StoryExitException(ExitReason.MaxQuestsReached(completedQuests))
            }

            // Full-screen announcements (chapter-unlock popup, tap-to-
            // continue notices) can cover the map or quest list after a
            // clear. They only appear around unrecognised/list screens,
            // so the (partly OCR-based) check is gated on the last state
            // to keep battle passes responsive.
            if (
                (lastState == null || lastState == StoryState.UNKNOWN ||
                    lastState == StoryState.QUEST_SELECT) &&
                dismissNotices()
            ) {
                prefs.story.actionInterval.wait()
                continue
            }

            // Sub-screens that must win over the generic detectors below.
            if (handleOverlayScreens()) {
                prefs.story.actionInterval.wait()
                continue
            }

            val state = useSameSnapIn { detectState() }

            if (state != StoryState.UNKNOWN) {
                unknownStreak = 0
                inQuestUnknownStreak = 0
                noticeAttempts = 0
            }

            if (prefs.story.debugLog && state != lastState) {
                messages.log(ScriptLog.AutoStoryState(state.name, completedQuests))
                trace("state=$state")
            }
            lastState = state

            when (state) {
                StoryState.ERROR -> handleError()
                StoryState.BATTLE -> {
                    questProgressed = true
                    battle.performBattle()
                }

                StoryState.SUPPORT_SELECT -> {
                    // Story-mode support choice (see selectSupportForStory):
                    // the preferred servant wins whenever she is in the
                    // list; the first entry (friend or pinned guest) is
                    // only the fallback when she is not.
                    trace("support: story selection, class=${supportPrefs.supportClass}")
                    selectSupportForStory()
                    trace("support: selected")

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

                StoryState.UNKNOWN -> {
                    if (questActive && inQuestUnknownStreak < MAX_IN_QUEST_UNKNOWN_STREAK) {
                        // A quest is in progress, so an unrecognised
                        // screen is a transition: battle intro, attack/NP
                        // animation, wave change, victory sequence, story
                        // dialogue. Wait for a recognisable screen instead
                        // of searching the map and burning the unknown
                        // limit — gating this on "battle seen once" was
                        // not enough: the run died in the intro window
                        // before the battle UI ever settled (observed on
                        // device: exit dialog over a turn-1 battle).
                        ++inQuestUnknownStreak
                        if (inQuestUnknownStreak == 1 || inQuestUnknownStreak % 15 == 0) {
                            trace("quest: screen unrecognised, waiting (streak=$inQuestUnknownStreak)")
                        }
                        1.seconds.wait()
                    } else {
                        if (questActive) {
                            trace("quest: wait cap reached, treating as unknown")
                        }
                        handleUnknown()
                    }
                }
            }

            prefs.story.actionInterval.wait()
        }
    }

    // ------------------------------------------------------------------
    // Screen detection
    // ------------------------------------------------------------------

    private fun detectState(): StoryState = when {
        connectionRetry.needsToRetry() || withdraw.needsToWithdraw() -> StoryState.ERROR
        isInBattle() -> StoryState.BATTLE
        isInSupport() -> StoryState.SUPPORT_SELECT
        needsToStorySkip() -> StoryState.STORY
        isInResult() -> StoryState.RESULT
        isInPartyConfirm() -> StoryState.PARTY_CONFIRM
        isInQuestList() -> StoryState.QUEST_SELECT
        locations.story.loadingCheckRegion.isBlack() -> StoryState.LOADING
        else -> StoryState.UNKNOWN
    }

    /**
     * Battle detection: the built-in idle check, plus a weaker match of
     * the same template in the same region. A run was observed sitting
     * on an idle turn-2 battle until the unknown-screen limit hit, even
     * though offline replays of that screen score 0.92 — the on-device
     * score apparently flickers under the default similarity at times.
     * The weak match is traced so the trail shows when it was needed.
     */
    private fun isInBattle(): Boolean {
        if (battle.isIdle()) {
            return true
        }

        val weak = locations.battle.screenCheckRegion
            .find(images[Images.BattleScreen], similarity = 0.65) != null

        if (weak) {
            trace("probe: battle screen matched weakly (>=0.65)")
        }

        return weak
    }

    /**
     * Party confirmation shows the "Start Quest" button. The built-in
     * start_quest.png template does not match this button's rendering on
     * the party screen (measured 0.33 on a device screenshot, so this
     * state was never detected), hence Auto Story's own template of the
     * actual button (0.98 on the same screenshot, <=0.32 elsewhere).
     */
    private fun isInPartyConfirm() =
        locations.story.startQuestRegion
            .find(images[Images.StoryStartQuest], similarity = 0.6) != null ||
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
    /**
     * Dismisses full-screen announcements that can cover the map after a
     * quest/chapter clear. Returns true when a notice was acted on.
     *
     * Two kinds are handled:
     * - "Tap to continue" announcements (e.g. Mash's battle-style change):
     *   the prompt text is template-matched in the bottom strip and the
     *   match is tapped (any tap dismisses these).
     * - The chapter-unlock popup ("…開放！"): recognised by OCR of its
     *   centred title band and dismissed via its top-right close button.
     *
     * Attempts are capped per unrecognised streak so a false positive can
     * never turn into endless tapping.
     */
    private fun dismissNotices(): Boolean {
        if (noticeAttempts >= MAX_NOTICE_ATTEMPTS) {
            return false
        }

        locations.story.noticeTapRegion
            .find(images[Images.NoticeTap], NOTICE_SIMILARITY)
            ?.let { match ->
                ++noticeAttempts
                trace("notice: tap-to-continue announcement, tapping")
                match.region.center.click()
                1.seconds.wait()
                return true
            }

        val title = runCatching {
            locations.story.noticeTitleRegion.detectText(outlinedText = false)
        }.getOrDefault("")

        if (title.contains("開放")) {
            ++noticeAttempts
            trace("notice: unlock popup (ocr='${title.take(40)}'), tapping close")
            locations.story.noticeCloseClick.click()
            1.seconds.wait()
            return true
        }

        return false
    }

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

    /**
     * The built-in selection with its SupportSelectionManual fallback:
     * when the preferred search gives up, take the first entry (a
     * story quest always offers at least the pinned Guest Servant)
     * instead of ending the run on the selection screen.
     */
    private fun selectSupportWithFallback() {
        try {
            support.selectSupport()
        } catch (e: AutoBattle.BattleExitException) {
            trace("support: BattleExit reason=${e.reason}")
            if (e.reason != AutoBattle.ExitReason.SupportSelectionManual) {
                throw e
            }

            trace("support: preferred search gave up, taking first entry")
            support.selectSupport(SupportSelectionModeEnum.First)
        }
    }

    /**
     * Story-mode support selection. The user wants the preferred
     * servant (Morgan) whenever she is offered; the built-in search
     * sometimes misses her anyway — its full-screen scroll steps can
     * jump over the seam between two screenfuls, so a servant sitting
     * on a seam is skipped on every pass and the fallback picks the
     * top entry (often a pinned guest). This scan drives the built-in
     * preferred matcher directly with half-screen scroll steps (every
     * row is fully visible on some screenful), refreshes the list at
     * most [MAX_SUPPORT_REFRESHES] times and only when friend rows
     * exist at all, and falls back to the first entry when the
     * preferred servant is genuinely not in the list (guest-only
     * story quests). Non-preferred configurations keep the built-in
     * flow untouched.
     */
    private fun selectSupportForStory() {
        if (supportPrefs.selectionMode != SupportSelectionModeEnum.Preferred ||
            supportPrefs.preferredServants.isEmpty()
        ) {
            selectSupportWithFallback()
            return
        }

        // The load wait is done locally on purpose: the built-in wait
        // (inside selectSupport / the list refresher) also gates on the
        // ALL-tab icon being recognised, which can stay false for a
        // long time on some devices and freeze the run on this screen
        // before anything moves — the observed "stuck on the support
        // screen" failure. isListLoaded() only needs the rows
        // themselves. If the list genuinely never loads, the built-in
        // flow (whose wait also handles dialogs) takes over.
        if (!waitForSupportList(30)) {
            trace("support: list load timed out, using built-in selection")
            selectSupportWithFallback()
            return
        }

        supportClassPicker.selectSupportClass()
        waitForSupportList(15)

        val found = try {
            scanForPreferredSupport()
        } catch (e: AutoBattle.BattleExitException) {
            if (e.reason != AutoBattle.ExitReason.SupportSelectionPreferredNotSet) {
                throw e
            }
            false
        }

        if (!found) {
            trace("support: preferred not in list, taking first entry")
            support.selectSupport(SupportSelectionModeEnum.First)
        }
    }

    private fun scanForPreferredSupport(): Boolean {
        var refreshes = 0
        var sawFriendRow = false

        while (true) {
            var steps = 0
            var bottomReached = false

            while (steps < MAX_SCAN_STEPS) {
                if (friendRowVisible()) sawFriendRow = true

                when (preferredSelection.select()) {
                    SupportSelectionResult.Done -> {
                        trace("support: preferred selected")
                        return true
                    }

                    SupportSelectionResult.ScrollDown -> {
                        halfScrollDown()
                        ++steps
                        if (steps % 5 == 1) {
                            trace("support: scan step $steps, no match yet")
                        }
                        0.6.seconds.wait()
                    }

                    else -> {
                        // EarlyRefresh/Refresh: the scrollbar says the
                        // bottom is reached without a match.
                        bottomReached = true
                        break
                    }
                }
            }

            if (!bottomReached) {
                trace("support: scan step cap reached")
            }

            // Refreshing only helps when friend rows exist at all; a
            // guest-only list never gains the preferred servant.
            if (!sawFriendRow || refreshes >= MAX_SUPPORT_REFRESHES) {
                return false
            }

            ++refreshes
            trace("support: refreshing list (pass ${refreshes + 1})")
            refreshListOnce()
            supportScreen.scrollToTop()
            waitForSupportList(15)
        }
    }

    /**
     * Poll the built-in list-loaded check (rows present, by the Guest
     * marker / setup buttons / not-found notice) for up to
     * [timeoutSeconds] seconds.
     */
    private fun waitForSupportList(timeoutSeconds: Int): Boolean {
        repeat(timeoutSeconds) {
            if (supportScreen.isListLoaded()) return true
            1.seconds.wait()
        }

        return supportScreen.isListLoaded()
    }

    /**
     * Refresh the support list with the built-in screen actions,
     * waiting (bounded) for the refresh button to leave its in-game
     * cooldown first — tapping it while unavailable would leave the
     * follow-up confirm tap landing on a list row.
     */
    private fun refreshListOnce() {
        repeat(15) {
            if (supportScreen.refreshAvailable()) {
                supportScreen.refresh()
                return
            }
            1.seconds.wait()
        }

        trace("support: refresh button never became available")
    }

    /** Whether any visible row is a friend row (setup button present). */
    private fun friendRowVisible(): Boolean {
        val tool = locations.support.confirmSetupButtonRegion
            .findAll(images[Images.SupportConfirmSetupButton], 0.45).firstOrNull()

        return tool != null && tool.score >= Support.supportRegionToolSimilarity
    }

    /** Scroll the support list by half a screen so no row is skipped at a seam. */
    private fun halfScrollDown() {
        val start = locations.support.listSwipeStart
        val end = locations.support.listSwipeEnd

        swiper(start, Location((start.x + end.x) / 2, (start.y + end.y) / 2))
    }

    // ------------------------------------------------------------------
    // State handlers
    // ------------------------------------------------------------------

    private fun handleError() {
        when {
            withdraw.needsToWithdraw() -> {
                if (prefs.story.useLeylineStoneContinue &&
                    leylineContinuesUsed < MAX_LEYLINE_CONTINUES &&
                    tryLeylineStoneContinue()
                ) {
                    // The battle resumes with the party revived; the
                    // state machine re-detects the battle screen.
                    ++leylineContinuesUsed
                    return
                }

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

    /**
     * Spend one Leyline Stone to continue the current battle: tap the
     * stone button on the defeat dialog, then confirm. Only the stone
     * button is ever tapped — Command Spells and Saint Quartz are
     * never used. Returns false (and taps nothing) when the stone
     * button is not on screen, e.g. when no stones are left.
     */
    private fun tryLeylineStoneContinue(): Boolean {
        val button = locations.story.leylineStoneRegion
            .find(images[Images.LeylineStone], LEYLINE_BUTTON_SIMILARITY)
            ?: run {
                trace("defeat: leyline stone button not found")
                return false
            }

        trace("defeat: using leyline stone (attempt ${leylineContinuesUsed + 1})")
        button.region.click()
        1.seconds.wait()

        locations.story.leylineConfirmClick.click()
        3.seconds.wait()

        return true
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
        // The drops screen only advances through its bottom-right Next
        // button; the generic result tap lands below that button and
        // never advances it (observed on device: the run sat on the
        // loot screen). The farming flow's drops handling clicks the
        // MatRewards search region, whose centre is on the button —
        // do the same, then use the generic taps for the other result
        // screens (bond, master EXP, ...), which advance on any tap.
        locations.resultMatRewardsRegion.click()
        locations.resultClick.click(times = 5)
    }

    private fun startQuest() {
        partySelection.selectParty()

        trace("party: tapping start quest")
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

        // A quest panel is already open for some map spot: start it only
        // when it offers a Main Quest. Free Quest / Interlude panels belong
        // to neighbouring spots and must never be auto-started.
        if (isMainQuestPanelOpen()) {
            // Click the uppermost quest, exactly like AutoBattle.menu().
            // The Main Quest banner (with the NEXT marker) is listed first.
            trace("tap quest-start at ${locations.menuSelectQuestClick}")
            locations.menuSelectQuestClick.click()

            questActive = true
            questProgressed = false
            questCounted = false
            noTargetStreak = 0

            2.5.seconds.wait()

            if (isInventoryFull()) {
                throw StoryExitException(ExitReason.InventoryFull)
            }

            return
        }

        // No usable panel: tap the node carrying the NEXT marker. Its panel
        // opens and is verified on the next pass. Tapping the verified node
        // also replaces a wrong (Free Quest) panel that may be open.
        val next = storyMapFinder.findNextQuest()
        if (next != null) {
            trace("tap node at $next")
            next.click()
            noTargetStreak = 0
            2.seconds.wait()
            return
        }

        // Nothing verified to tap. Never tap blindly here — a fixed-position
        // tap on this screen used to start neighbouring Free Quests.
        if (++noTargetStreak >= MAX_NO_TARGET_STREAK) {
            throw StoryExitException(ExitReason.NextQuestNotFound)
        }

        2.seconds.wait()
    }

    /**
     * Whether the open quest panel offers the next Main Quest.
     *
     * Primary check: the gold NEXT banner the panel draws on the Main
     * Quest entry ([Images.StoryNextPanel], cut from a device screenshot;
     * Free Quest / Interlude entries never carry it). OCR of the banner
     * labels ("Main Quest") is kept as a fallback. An OCR-only check
     * proved unreliable on device: when it missed, the finder kept
     * re-tapping the already-selected node and the run never proceeded.
     */
    private fun isMainQuestPanelOpen(): Boolean {
        trace("panel: checking")
        if (storyMapFinder.findScaled(
                locations.story.questPanelRegion,
                Images.StoryNextPanel,
                StoryMapFinder.UI_MARKER_SCALES
            ) != null
        ) {
            trace("panel: TEMPLATE hit")
            return true
        }

        val text = runCatching { locations.story.questPanelRegion.detectText() }
            .getOrDefault("")

        // Both words are required: the chapter-select header reads
        // "Main Record", which must not count as a quest panel.
        val result = text.contains("Main", ignoreCase = true) &&
            text.contains("Quest", ignoreCase = true)

        trace("panel: template miss, ocr -> $result")
        return result
    }

    /**
     * Screens matched by no template (chapter-select lists, transitions)
     * are first given a chance to be the story map: [StoryMapFinder]
     * searches for the "NEXT" node marker (template first, OCR fallback).
     * Only when that also fails repeatedly do we treat the screen as an error.
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
