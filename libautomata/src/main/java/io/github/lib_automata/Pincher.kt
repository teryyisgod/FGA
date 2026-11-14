package io.github.lib_automata

import javax.inject.Inject

/**
 * Performs two-finger pinch gestures (used to zoom maps in/out).
 *
 * Distances are the horizontal gap between the two fingers in script
 * coordinates: moving the fingers apart ([startDistance] < [endDistance])
 * zooms in on most maps, moving them together zooms out.
 */
interface Pincher {
    operator fun invoke(center: Location, startDistance: Int, endDistance: Int)
}

class RealPincher @Inject constructor(
    private val gestureService: GestureService,
    private val transform: Transformer
) : Pincher {
    override fun invoke(center: Location, startDistance: Int, endDistance: Int) {
        fun finger(distance: Int, side: Int) =
            Location(center.x + side * distance / 2, center.y)

        gestureService.pinch(
            transform.toScreen(finger(startDistance, -1)),
            transform.toScreen(finger(endDistance, -1)),
            transform.toScreen(finger(startDistance, 1)),
            transform.toScreen(finger(endDistance, 1))
        )
    }
}
