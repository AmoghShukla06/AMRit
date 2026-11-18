package org.amrit.core

/**
 * The family antibiotic passport: every antibiotic course one person took, and antibiotics kept in the house, with
 * prompts drawn from fixed rules over that history. A chatbot sees one photo; this is months of a household's use,
 * kept only on the phone. Prompts point to the doctor; they never say which antibiotic to take.
 */
object Passport {
    enum class Status {
        ONGOING,
        /** every planned dose was ticked */
        COMPLETED,
        /** removed before its planned last day with doses missing */
        STOPPED_EARLY,
        /** the planned days are over but not every dose was ticked (it may just not have been logged) */
        ENDED,
        /** an antibiotic in the person's medicines with no course started for it */
        IN_HOUSE,
    }

    data class Entry(val product: String, val antibiotics: String, val aware: Aware?, val startedAt: Long, val daysPlanned: Int?,
                     val dosesDone: Int?, val dosesPlanned: Int?, val prescribed: Boolean?, val status: Status)

    data class Insight(val key: String, val args: List<Pair<String, Any>> = emptyList())

    private const val DAY = 86_400_000L

    fun status(active: Boolean, startedAt: Long, days: Int, perDay: Int, dosesTaken: Int, endedAt: Long?, nowMs: Long): Status {
        val plannedEnd = startedAt + days * DAY
        return when {
            dosesTaken >= days * perDay -> Status.COMPLETED
            !active && endedAt != null && endedAt < plannedEnd -> Status.STOPPED_EARLY
            nowMs >= plannedEnd || !active -> Status.ENDED
            else -> Status.ONGOING
        }
    }

    fun insights(entries: List<Entry>, nowMs: Long): List<Insight> {
        val year = entries.filter { nowMs - it.startedAt <= 365 * DAY }
        val courses = year.filter { it.status != Status.IN_HOUSE }
        val out = ArrayList<Insight>()
        val watch = entries.count { nowMs - it.startedAt <= 90 * DAY && it.status != Status.IN_HOUSE &&
            (it.aware == Aware.Watch || it.aware == Aware.Reserve) }
        if (watch >= 2) out += Insight("passport.insight.watch", listOf("n" to watch))
        if (courses.any { it.status == Status.STOPPED_EARLY }) out += Insight("passport.insight.stopped")
        if (courses.any { it.prescribed == false }) out += Insight("passport.insight.noRx")
        if (courses.size >= 3) out += Insight("passport.insight.many", listOf("n" to courses.size))
        return out
    }
}
