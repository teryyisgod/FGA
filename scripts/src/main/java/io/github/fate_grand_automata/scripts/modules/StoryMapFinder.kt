package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.github.lib_automata.Swiper
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Finds the next uncleared quest on the story map.
 *
 * FGO marks the next story quest with a gold "NEXT" banner floating above
 * its node. The primary detection is template matching against
 * [Images.StoryNext] (cut from a JP reference screenshot); the tap target
 * is the node icon just below the marker. When the template does not match
 * (e.g. a chapter whose map dims the marker), an OCR grid search for the
 * text "NEXT" is used as a fallback.
 *
 * This is intentionally conservative: if nothing matches after scrolling,
 * it returns null and the caller stops safely instead of tapping blindly.
 * Callers must still verify the opened quest panel actually offers a Main
 * Quest before starting it — neighbouring Free Quest spots sit close to
 * story nodes on many maps.
 */
@ScriptScope
class StoryMapFinder @Inject constructor(
    api: IFgoAutomataApi,
    private val swipe: Swiper
) : IFgoAutomataApi by api {

    companion object {
        /** How many times the map may be scrolled while searching. */
        const val MAX_MAP_SCROLLS = 3

        /**
         * Similarity for the NEXT marker template. Measured against two JP
         * device screenshots: 0.97 (source frame) and 0.76 (same map, marker
         * dimmed by fog), with the best false location at 0.38 — so 0.7
         * keeps a wide safety margin on both sides.
         */
        const val NEXT_MARKER_SIMILARITY = 0.7
    }

    /**
     * Returns the tap location of the next quest node, or null when it could
     * not be located within [MAX_MAP_SCROLLS] scrolls.
     */
    fun findNextQuest(): Location? {
        repeat(MAX_MAP_SCROLLS + 1) { attempt ->
            findNextMarker()?.let { return it }

            if (attempt < MAX_MAP_SCROLLS) {
                swipe(
                    locations.story.mapScrollStart,
                    locations.story.mapScrollEnd
                )
                1.seconds.wait()
            }
        }

        return null
    }

    private fun findNextMarker(): Location? {
        val area = locations.story.mapScanRegion

        // Next node on the chapter map itself. The marker is rendered at
        // two sizes: zoomed into an area (StoryNext) and in the initial
        // whole-map view (StoryNextWide) — try both.
        area.find(images[Images.StoryNext], NEXT_MARKER_SIMILARITY)
            ?.let { match -> return match.region.center + locations.story.storyNodeOffset }

        area.find(images[Images.StoryNextWide], NEXT_MARKER_SIMILARITY)
            ?.let { match -> return match.region.center + locations.story.storyNodeOffset }

        // Next chapter on the chapter-select list (rendered larger there):
        // tapping it enters that chapter's map, so chapter boundaries are
        // crossed with the same search.
        area.find(images[Images.StoryNextChapter], NEXT_MARKER_SIMILARITY)
            ?.let { match -> return match.region.center + locations.story.chapterNodeOffset }

        return findNextMarkerByOcr()
    }

    private fun findNextMarkerByOcr(): Location? {
        val area = locations.story.mapScanRegion
        val cols = locations.story.mapGridCols
        val rows = locations.story.mapGridRows
        val cellW = area.width / cols
        val cellH = area.height / rows

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cell = Region(
                    area.x + col * cellW,
                    area.y + row * cellH,
                    cellW,
                    cellH
                )

                val text = runCatching { cell.detectText(outlinedText = true) }
                    .getOrDefault("")

                if (text.contains("NEXT", ignoreCase = true)) {
                    return cell.center
                }
            }
        }

        return null
    }
}
