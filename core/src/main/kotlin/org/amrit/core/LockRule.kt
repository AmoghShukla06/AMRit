package org.amrit.core

/**
 * When the live scanner stops reading and answers. A confident answer locks once it is stable over two frames (or
 * the printed composition agrees, or the pack's code names it); a "which one is it?" after 4.5 s. A single scan also
 * gives up after 6.5 s (9 s while a sharp still is being read) and says "not found". In box mode the camera stays open
 * between strips, so it never gives up on its own: the table between strips is not an unidentified strip.
 */
object LockRule {
    fun shouldLock(band: Band, stableCount: Int, compositionEvidence: Boolean, frames: Int, packHasNames: Boolean, elapsedMs: Long,
                   stillPending: Boolean, forced: Boolean, boxMode: Boolean): Boolean {
        if (forced) return true
        if (band == Band.ACCEPT && (stableCount >= 2 || compositionEvidence && frames >= 2 || packHasNames)) return true
        if (band == Band.CONFIRM && elapsedMs > 4500 && !stillPending) return true
        if (boxMode) return false
        return (elapsedMs > 6500 && !stillPending) || elapsedMs > 9000
    }
}
