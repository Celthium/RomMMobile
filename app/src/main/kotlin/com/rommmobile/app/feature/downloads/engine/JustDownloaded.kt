package com.rommmobile.app.feature.downloads.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Games whose download finished in the last few minutes and whose card has not celebrated yet.
 * A card on screen at the moment of completion animates by itself; this covers the usual case
 * of a download started from the game page: back on the grid, the card is created already
 * "present" and would otherwise never show the tick pop.
 */
object JustDownloaded {
    private const val WINDOW_MS = 10 * 60 * 1000L
    private val finished = ConcurrentHashMap<Int, Long>()

    fun mark(romId: Int) { finished[romId] = System.currentTimeMillis() }

    /** True once per finished game, within the window; the next call returns false. */
    fun consume(romId: Int): Boolean {
        val at = finished.remove(romId) ?: return false
        return System.currentTimeMillis() - at < WINDOW_MS
    }
}
