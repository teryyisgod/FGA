package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.Pincher
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import io.github.lib_automata.Swiper
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.math.roundToInt
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
    private val swipe: Swiper,
    private val pincher: Pincher
) : IFgoAutomataApi by api {

    companion object {
        /** How many times the map may be scrolled while searching. */
        const val MAX_MAP_SCROLLS = 3

        /** Pinch-out gestures used to reach the whole-map zoom level. */
        const val ZOOM_OUT_PINCHES = 2

        /** Pinch-in steps swept (with a search after each) on the way back in. */
        const val ZOOM_IN_STEPS = 2

        /**
         * Similarity for the NEXT marker templates. The templates are cut
         * tightly around the letters (background fog/brightness varies too
         * much for loose crops). Cross-measured on four JP device
         * screenshots: every visible marker scores 0.66–1.00 with at least
         * one template at its exact scale, while screens without a marker
         * peak at 0.42 — so 0.65 sits between the two sides.
         */
        const val NEXT_MARKER_SIMILARITY = 0.65

        /**
         * The marker throbs (pulse animation) and its size also depends on
         * the map zoom, and template matching is extremely scale-sensitive:
         * measured offline, a ±5% size difference drops the score to
         * 0.62–0.77 and ±10% to 0.41–0.55. Searching a ladder of scales
         * keeps the worst-case size error at 2.5%.
         */
        val MARKER_SCALES = listOf(1.0, 0.95, 1.05, 0.9, 1.1, 0.85, 1.15)

        /** Smaller ladder for fixed-scale UI markers (chapter list, panel). */
        val UI_MARKER_SCALES = listOf(1.0, 0.95, 1.05, 0.9, 1.1)
    }

    /**
     * Finds [image] in [area], trying the template at several scales.
     * Returns the first match (exact scale first, then near scales).
     */
    fun findScaled(area: Region, image: Images, scales: List<Double>): Match? {
        val base = images[image]

        for (scale in scales) {
            if (scale == 1.0) {
                area.find(base, NEXT_MARKER_SIMILARITY)?.let { return it }
            } else {
                val size = Size(
                    (base.width * scale).roundToInt(),
                    (base.height * scale).roundToInt()
                )
                base.resize(size).use { resized ->
                    area.find(resized, NEXT_MARKER_SIMILARITY)?.let { return it }
                }
            }
        }

        return null
    }

    /**
     * Returns the tap location of the next quest node, or null when it could
     * not be located.
     *
     * The marker renders at different sizes per zoom level, so the search
     * runs in phases: the current view first, then after pinching fully out
     * (the whole-map view, where every node is visible at once), and finally
     * after pinching back in once. Each phase also scrolls the map a few
     * times before giving up.
     */
    fun findNextQuest(): Location? {
        searchCurrentView()?.let { return it }

        // Zoom fully out and search the whole map.
        repeat(ZOOM_OUT_PINCHES) {
            pincher(
                locations.story.pinchCenter,
                locations.story.pinchStartDistance,
                locations.story.pinchEndDistance
            )
            1.seconds.wait()
        }
        searchCurrentView()?.let { return it }

        // Sweep back in step by step, searching at every zoom level: the
        // deepest zoom-out renders the marker smaller than any template,
        // but the sweep passes through the levels the templates cover.
        repeat(ZOOM_IN_STEPS) {
            pincher(
                locations.story.pinchCenter,
                locations.story.pinchEndDistance,
                locations.story.pinchStartDistance
            )
            1.seconds.wait()
            searchCurrentView()?.let { return it }
        }

        return null
    }

    private fun searchCurrentView(): Location? {
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

        // Next node on the chapter map itself. The marker's rendering
        // varies with zoom level and map brightness, so three tight-cut
        // variants are tried, each at a ladder of scales; the tap target
        // is the node just below the marker in every case.
        for (image in listOf(Images.StoryNext, Images.StoryNextBright, Images.StoryNextWide)) {
            findScaled(area, image, MARKER_SCALES)
                ?.let { match -> return match.region.center + locations.story.storyNodeOffset }
        }

        // Next chapter on the chapter-select list (rendered larger there):
        // tapping it enters that chapter's map, so chapter boundaries are
        // crossed with the same search.
        findScaled(area, Images.StoryNextChapter, UI_MARKER_SCALES)
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
