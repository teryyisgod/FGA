package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
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
        const val ZOOM_OUT_PINCHES = 3

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

        /**
         * Ladder for the whole-map phase: at the deepest zoom-out the
         * marker renders far below the normal ladder's 0.85 floor, so
         * the small scales are tried first there, then the normal ones
         * (in case the pinch did not change the zoom after all).
         */
        val WHOLE_MAP_SCALES = listOf(0.75, 0.65, 0.55) + MARKER_SCALES
    }

    /** Diagnostic sink set by the entry point when the debug log is on. */
    var tracer: ((String) -> Unit)? = null

    /**
     * Finds [image] in [area], trying the template at several scales.
     * Returns the first match (exact scale first, then near scales).
     */
    fun findScaled(area: Region, image: Images, scales: List<Double>): Match? {
        val base = images[image]

        for (scale in scales) {
            val match = findAtScale(area, base, scale)

            if (match != null) {
                tracer?.invoke(
                    "HIT ${image.name} scale=$scale score=${"%.2f".format(match.score)} " +
                        "at=${match.region.center}"
                )
                return match
            }
        }

        tracer?.let { trace ->
            // Probe the best score with a single low-threshold find. Never
            // probe at 0.0: findAll drains every local maximum above the
            // threshold, which at 0.0 effectively never finishes.
            // Only near-misses are traced: tracing every miss flooded the
            // debug trail until the state history and exit reason fell off.
            val best = area.find(base, 0.45)
            if (best != null && best.score >= 0.55) {
                trace("near-miss ${image.name} best=${"%.2f".format(best.score)}")
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
        tracer?.invoke("search: current view")
        searchCurrentView()?.let { return it }

        // Zoom fully out and search the whole map.
        tracer?.invoke("search: pinch out")
        repeat(ZOOM_OUT_PINCHES) { index ->
            tracer?.invoke("zoom: pinch out ${index + 1}/$ZOOM_OUT_PINCHES")
            pincher(
                locations.story.pinchCenter,
                locations.story.pinchStartDistance,
                locations.story.pinchEndDistance
            )
            1.seconds.wait()
        }
        searchCurrentView(WHOLE_MAP_SCALES)?.let { return it }
        searchWholeMapPans()?.let { return it }

        // Sweep back in step by step, searching at every zoom level: the
        // deepest zoom-out renders the marker smaller than any template,
        // but the sweep passes through the levels the templates cover.
        repeat(ZOOM_IN_STEPS) { index ->
            tracer?.invoke("zoom: pinch in step ${index + 1}/$ZOOM_IN_STEPS")
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

    /**
     * Pan the zoomed-out map up/down/left/right, searching after each
     * pan: long or wide chapter maps extend past the screen even at
     * the deepest zoom-out (user request). Template matching only (no
     * OCR): at this zoom the marker text is too small for OCR anyway,
     * and four OCR grids would take minutes. Each pan returns to the
     * centre view so the pinch-in phase starts from the same place.
     */
    private fun searchWholeMapPans(): Location? {
        val pans = listOf(
            "top" to (0 to 300),
            "bottom" to (0 to -300),
            "left" to (500 to 0),
            "right" to (-500 to 0)
        )

        for ((name, delta) in pans) {
            tracer?.invoke("zoom: pan to $name")
            panMap(delta.first, delta.second)
            findNextMarker(WHOLE_MAP_SCALES, useOcr = false)?.let { return it }
            panMap(-delta.first, -delta.second)
        }

        return null
    }

    /** Drag the map by twice the given half-distance around the centre. */
    private fun panMap(dx: Int, dy: Int) {
        val c = locations.story.pinchCenter
        swipe(Location(c.x - dx, c.y - dy), Location(c.x + dx, c.y + dy))
        0.7.seconds.wait()
    }

    private fun searchCurrentView(scales: List<Double> = MARKER_SCALES): Location? {
        repeat(MAX_MAP_SCROLLS + 1) { attempt ->
            findNextMarker(scales)?.let { return it }

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

    private fun findNextMarker(
        scales: List<Double> = MARKER_SCALES,
        useOcr: Boolean = true
    ): Location? {
        val area = locations.story.mapScanRegion

        // Next node on the chapter map itself. The marker's rendering
        // varies with zoom level and map brightness, so tight-cut
        // variants are tried at a ladder of scales; the tap target is
        // the node just below the marker in every case. The gold-text
        // variant (e.g. E Pluribus Unum) has its own cut geometry, hence
        // its own node offset.
        val variants = listOf(
            Images.StoryNext to locations.story.storyNodeOffset,
            Images.StoryNextBright to locations.story.storyNodeOffset,
            Images.StoryNextWide to locations.story.storyNodeOffset,
            Images.StoryNextGold to locations.story.goldNodeOffset,
            Images.StoryNextGoldBright to locations.story.goldBrightNodeOffset
        )

        // One shared screenshot for the whole sweep, scales outermost:
        // every find used to capture a fresh screenshot, so a full miss
        // cost dozens of captures (seconds per view, and the marker's
        // pulse animation changed between captures). Trying the exact
        // scale for every variant first also finds the common case in
        // a handful of matches.
        val found = useSameSnapIn {
            for (scale in scales) {
                for ((image, nodeOffset) in variants) {
                    findAtScale(area, images[image], scale)?.let { match ->
                        tracer?.invoke(
                            "HIT ${image.name} scale=$scale score=${"%.2f".format(match.score)} " +
                                "at=${match.region.center}"
                        )
                        return@useSameSnapIn match.region.center + nodeOffset
                    }
                }
            }

            // Next chapter on the chapter-select list (rendered larger
            // there): tapping it enters that chapter's map, so chapter
            // boundaries are crossed with the same search.
            findScaled(area, Images.StoryNextChapter, UI_MARKER_SCALES)
                ?.let { match -> return@useSameSnapIn match.region.center + locations.story.chapterNodeOffset }

            null
        }

        return found ?: if (useOcr) findNextMarkerByOcr() else null
    }

    /** Single-scale match of [base] in [area] at the marker similarity. */
    private fun findAtScale(area: Region, base: Pattern, scale: Double): Match? {
        return if (scale == 1.0) {
            area.find(base, NEXT_MARKER_SIMILARITY)
        } else {
            val size = Size(
                (base.width * scale).roundToInt(),
                (base.height * scale).roundToInt()
            )
            base.resize(size).use { resized ->
                area.find(resized, NEXT_MARKER_SIMILARITY)
            }
        }
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
                    tracer?.invoke("OCR hit cell($row,$col)")

                    // A grid cell is far bigger than the marker, so its
                    // centre can be hundreds of pixels away from the node.
                    // Bisect the cell with further OCR passes to localise
                    // the text before tapping.
                    val localized = localizeOcrText(cell)
                    tracer?.invoke("OCR localized at ${localized.center}")
                    return localized.center + locations.story.storyNodeOffset
                }
            }
        }

        tracer?.invoke("OCR: no NEXT cell")
        return null
    }

    /**
     * Narrows [region] down around the "NEXT" text by repeatedly halving
     * it (along its longer side) and keeping the half whose OCR still
     * contains the text. Stops when the region is small enough for its
     * centre to anchor a tap, or when neither half matches anymore.
     */
    private fun localizeOcrText(region: Region): Region {
        var current = region

        while (current.width > 280 || current.height > 200) {
            val halves = if (current.width >= current.height) {
                val halfW = current.width / 2
                listOf(
                    Region(current.x, current.y, halfW, current.height),
                    Region(current.x + halfW, current.y, current.width - halfW, current.height)
                )
            } else {
                val halfH = current.height / 2
                listOf(
                    Region(current.x, current.y, current.width, halfH),
                    Region(current.x, current.y + halfH, current.width, current.height - halfH)
                )
            }

            val hit = halves.firstOrNull { half ->
                runCatching { half.detectText(outlinedText = true) }
                    .getOrDefault("")
                    .contains("NEXT", ignoreCase = true)
            } ?: return current

            current = hit
        }

        return current
    }
}
