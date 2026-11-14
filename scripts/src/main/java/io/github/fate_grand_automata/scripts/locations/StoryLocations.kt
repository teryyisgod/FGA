package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

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
     * is expected (bottom-right). [io.github.fate_grand_automata.scripts.Images.StartQuest]
     * is matched inside it.
     */
    val startQuestRegion = Region(-760, -380, 700, 320).xFromRight().yFromBottom()

    /** Full story-map area scanned (via OCR) for the "NEXT" marker of the next quest node. */
    val mapScanRegion: Region
        get() = Region(
            scriptArea.x + 40,
            scriptArea.y + 140,
            scriptArea.width - 80,
            scriptArea.height - 420
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

    /** Tap target for advancing dialogue when story skip is disabled. */
    val dialogueAdvanceClick = Location(0, -120).xFromCenter().yFromBottom()

    /** Generic confirmation ("Yes"/decide) position, same anchor as the story-skip confirmation. */
    val confirmClick = Location(320, 1100).xFromCenter()

    /** Area checked for a black transition/loading screen. */
    val loadingCheckRegion = Region(-400, 500, 800, 400).xFromCenter()
}
