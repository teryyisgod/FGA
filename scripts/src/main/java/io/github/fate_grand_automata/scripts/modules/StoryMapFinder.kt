package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.github.lib_automata.Swiper
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Finds the next uncleared quest on the story map.
 *
 * FGO marks the next story quest with a "NEXT" banner above its node. There
 * is no template image for that banner in the assets (it is drawn in
 * different styles per chapter), so this finder localises it with the
 * existing OCR service instead: the map area is split into a grid, each cell
 * is OCR'd, and the centre of the first cell containing "NEXT" is returned
 * as the tap target.
 *
 * This is intentionally conservative: if no cell matches after scrolling,
 * it returns null and the caller stops safely instead of tapping blindly.
 */
@ScriptScope
class StoryMapFinder @Inject constructor(
    api: IFgoAutomataApi,
    private val swipe: Swiper
) : IFgoAutomataApi by api {

    companion object {
        /** How many times the map may be scrolled while searching. */
        const val MAX_MAP_SCROLLS = 3
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
