package org.amrit.core

import java.time.YearMonth

/**
 * "Check my medicine box": every strip in a household's box, scanned one after another, judged together.
 * Nothing new is decided here: each flag comes from the rules a single scan already uses (expiry, CDSCO batch
 * alerts, CDSCO bans, the cabinet duplicate/interaction check). What the box adds is grouping them into what to
 * throw away, what to ask a doctor about, and what is fine.
 */
object BoxAudit {
    data class Item(val report: RuleEngine.StripReport, val expiry: YearMonth?, val batch: String?, val alerts: List<BatchAlert>)
    enum class Group { THROW_AWAY, ASK_DOCTOR, FINE }
    enum class Reason { EXPIRED, RECALLED_BATCH, BANNED, DUPLICATE, INTERACTION, LEFTOVER_ANTIBIOTIC }
    /** [items]: indexes into [Report.items]; [alert]: the cabinet alert behind a DUPLICATE / INTERACTION finding */
    data class Finding(val reason: Reason, val items: List<Int>, val alert: RuleEngine.CabinetAlert? = null)
    data class Report(val items: List<Item>, val findings: List<Finding>, val groups: Map<Int, Group>)

    private val throwAway = setOf(Reason.EXPIRED, Reason.RECALLED_BATCH, Reason.BANNED)

    /**
     * [today]: a strip is expired only after its printed month (medicines are good until the end of that month).
     * [activeCourseNames]: product names with a course running; an antibiotic in the box is otherwise a leftover,
     * since it is sitting in a box rather than being taken.
     */
    fun audit(items: List<Item>, rules: RuleEngine, today: YearMonth, activeCourseNames: Set<String>): Report {
        // the same strip scanned twice (or two strips of one product) is one medicine, not a double dose with itself
        val unique = items.groupBy { it.report.product.id }.values.map { same ->
            same.first().copy(expiry = same.mapNotNull { it.expiry }.minOrNull(), alerts = same.flatMap { it.alerts }.distinct(),
                batch = same.firstNotNullOfOrNull { it.batch })
        }
        val findings = ArrayList<Finding>()
        unique.forEachIndexed { i, it ->
            val r = it.report
            if (it.expiry != null && it.expiry < today) findings += Finding(Reason.EXPIRED, listOf(i))
            if (it.alerts.isNotEmpty()) findings += Finding(Reason.RECALLED_BATCH, listOf(i))
            if (r.banned) findings += Finding(Reason.BANNED, listOf(i))
            val systemic = r.antibiotic?.let { a -> !a.topical } == true
            if (systemic && r.product.name !in activeCourseNames) findings += Finding(Reason.LEFTOVER_ANTIBIOTIC, listOf(i))
        }
        val indexOf = unique.withIndex().associate { (i, it) -> it.report.product.name to i }
        for (a in rules.cabinet(unique.map { it.report })) when (a) {
            is RuleEngine.CabinetAlert.Duplicate ->
                findings += Finding(Reason.DUPLICATE, a.products.mapNotNull { indexOf[it.first] }.distinct(), a)
            is RuleEngine.CabinetAlert.Interacts ->
                findings += Finding(Reason.INTERACTION, listOfNotNull(indexOf[a.productA], indexOf[a.productB]), a)
        }
        val groups = unique.indices.associateWith { i ->
            val reasons = findings.filter { i in it.items }.map { it.reason }
            when {
                reasons.any { it in throwAway } -> Group.THROW_AWAY
                reasons.isNotEmpty() -> Group.ASK_DOCTOR
                else -> Group.FINE
            }
        }
        return Report(unique, findings, groups)
    }
}
