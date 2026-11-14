package io.github.lib_automata

/**
 * Interface for classes which can perform gestures.
 */
interface GestureService : AutoCloseable {
    /**
     * Swipes from one [Location] to another [Location].
     *
     * @param start the [Location] where the swipe should start
     * @param end the [Location] where the swipe should end
     */
    fun swipe(start: Location, end: Location)

    /**
     * Clicks on a given [location].
     *
     * @param location the location to click on
     * @param times the number of times to click
     */
    fun click(location: Location, times: Int = 1)

    /**
     * Moves two fingers simultaneously: finger A from [startA] to [endA]
     * and finger B from [startB] to [endB]. Used for pinch zoom gestures.
     */
    fun pinch(startA: Location, endA: Location, startB: Location, endB: Location)
}