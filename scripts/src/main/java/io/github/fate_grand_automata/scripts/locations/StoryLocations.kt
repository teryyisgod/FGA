package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Coordinates used only by Auto Story mode.
 *
 * All values are in the 1440p script coordinate space (see
 * docs/agents/coordinates.md) and are anchored with the
 * [IScriptAreaTransforms] helpers instead of absolute X positions.
 */
@ScriptScope
class StoryLocations @Inject constructor(
    scriptAreaTransforms: IScriptAreaTransforms
) : IScriptAreaTransforms by scriptAreaTransforms {

    /**
     * Region where the "Start Quest" button of the party-confirmation screen
     * is expected (bottom-right). Sized to contain the whole button: an
     * earlier, shorter region cut off the button's bottom edge, which
     * depressed the match score. Auto Story's own button template
     * ([io.github.fate_grand_automata.scripts.Images.StoryStartQuest]) is
     * matched inside it; the built-in StartQuest template does not match
     * this button's rendering (measured 0.33 on a device screenshot).
     */
    val startQuestRegion = Region(-780, -450, 720, 400).xFromRight().yFromBottom()

    /**
     * Region where the "Use Leyline Stone" button of the defeat /
     * continue dialog is expected (leftmost of the three continue
     * buttons). The [io.github.fate_grand_automata.scripts.Images.LeylineStone]
     * template (the button's caption, cut from a device screenshot;
     * 1.00 on the source screen, <=0.66 on the neighbouring buttons)
     * is matched inside it.
     */
    val leylineStoneRegion = Region(-880, 520, 470, 170).xFromCenter()

    /** The confirm ("決定") button of the Leyline Stone continue dialog. */
    val leylineConfirmClick = Location(430, 830).xFromCenter()

    /**
     * Region holding the "クエストを開始しますか？" line of the quest-start
     * confirmation dialog. Some story quests (party formation happens
     * right before battle) show this dialog after the quest entry is
     * tapped, before the support list. The
     * [io.github.fate_grand_automata.scripts.Images.QuestStartConfirm]
     * template is the question line itself, cut from a device
     * screenshot (1.00 on the source dialog, <=0.30 on map / support /
     * continue screens).
     */
    val questStartConfirmRegion = Region(-580, 660, 1160, 160).xFromCenter()

    /** The "開始" (start) button of the quest-start confirmation dialog. */
    val questStartConfirmClick = Location(400, 1128).xFromCenter()

    /**
     * Region holding the lone "閉じる" button of the confirmation
     * dialogs shown after a chapter clear (chapter-clear rewards,
     * free-unlock notices, servant-join notices). The
     * [io.github.fate_grand_automata.scripts.Images.DialogClose]
     * template is the button's text, cut from a device screenshot
     * (1.00 on both observed dialogs, <=0.56 elsewhere).
     */
    val dialogCloseRegion = Region(-330, 1020, 660, 220).xFromCenter()

    /**
     * Region holding the x button of illustrated announcement popups
     * (e.g. the next-chapter unlock splash), at the popup frame's
     * top-right corner. The
     * [io.github.fate_grand_automata.scripts.Images.PopupCloseX]
     * template scores 1.00 on the unlock popup and <=0.35 elsewhere
     * inside this region.
     */
    val popupCloseRegion = Region(-720, 10, 380, 180).xFromRight()

    /**
     * Full story-map area scanned for the "NEXT" marker of the next quest
     * node. Margins stay small on purpose: southern/northern edge nodes are
     * legitimate targets — an earlier version cut 420px off the bottom and
     * could not see edge nodes at all.
     */
    val mapScanRegion: Region
        get() = Region(
            scriptArea.x + 20,
            scriptArea.y + 90,
            scriptArea.width - 40,
            scriptArea.height - 200
        )

    /** Number of OCR grid columns/rows used to localise the "NEXT" marker on the map. */
    val mapGridCols = 4
    val mapGridRows = 3

    /**
     * Offset from the centre of the floating "NEXT" marker to the centre of
     * the quest node icon below it (measured from JP reference screenshots in
     * docs/reference-images/Jp/quest/).
     */
    val storyNodeOffset = Location(12, 194)

    /**
     * Same offset for the gold-text marker variant (story_next_gold.png):
     * that template's cut is taller, so its centre sits closer to the node.
     * Measured on the E Pluribus Unum reference screenshot.
     */
    val goldNodeOffset = Location(12, 163)

    /**
     * Bottom-centre strip where full-screen announcements (e.g. Mash's
     * battle-style change) print "画面をタッチしてください". The
     * notice_tap.png template is matched inside it.
     */
    val noticeTapRegion = Region(-560, -160, 1120, 130).xFromCenter().yFromBottom()

    /**
     * Centred title band of the chapter-unlock popup ("…開放！"). OCR of
     * this band gates the popup dismissal, so the map's own chapter banner
     * (top-right, outside this band) cannot trigger it.
     */
    val noticeTitleRegion = Region(-700, 30, 1400, 260).xFromCenter()

    /**
     * Close (×) button of the chapter-unlock popup, at the popup frame's
     * top-right corner. The popup is height-fitted and centred, so the
     * position is a fraction of the script width (measured estimate from
     * the popup's 16:9 layout; refined once a device screenshot exists).
     */
    val noticeCloseClick: Location
        get() = Location(
            scriptArea.x + (scriptArea.width * 0.823).roundToInt(),
            scriptArea.y + 100
        )

    /**
     * Offset from the centre of the "NEXT" marker on the chapter-select
     * list to the centre of that chapter's entry banner (the marker sits at
     * the banner's top-right corner). Measured from the same JP reference
     * screenshots.
     */
    val chapterNodeOffset = Location(-344, 146)

    /**
     * Right-side panel listing the quests of the selected map spot. OCR of
     * this region tells apart a Main Quest panel (contains "Main") from
     * Free Quest / Interlude panels, which must never be auto-started.
     */
    val questPanelRegion = Region(-1750, 130, 1650, 650).xFromRight()

    /** Swipe used to scroll the story map when no "NEXT" marker is visible. */
    val mapScrollStart = Location(0, 1050).xFromCenter()
    val mapScrollEnd = Location(0, 450).xFromCenter()

    /** Centre of the pinch gestures used to change the story map's zoom level. */
    val pinchCenter = Location(0, 700).xFromCenter()

    /** Finger gap (script px) at the start of a pinch: wide open. */
    val pinchStartDistance = 760

    /** Finger gap (script px) at the end of a zoom-out pinch: nearly closed. */
    val pinchEndDistance = 140

    /** Tap target for advancing dialogue when story skip is disabled. */
    val dialogueAdvanceClick = Location(0, -120).xFromCenter().yFromBottom()

    /** Generic confirmation ("Yes"/decide) position, same anchor as the story-skip confirmation. */
    val confirmClick = Location(320, 1100).xFromCenter()

    /** Area checked for a black transition/loading screen. */
    val loadingCheckRegion = Region(-400, 500, 800, 400).xFromCenter()
}
