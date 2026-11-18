package org.amrit.core

import org.amrit.core.BoxAudit.Group
import org.amrit.core.BoxAudit.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.YearMonth

/** Medicine-box audit: one report over every strip in the box, from the same rules as a single scan. */
class BoxAuditTest {
    companion object {
        lateinit var ref: RefData
        lateinit var rules: RuleEngine
        @BeforeClass @JvmStatic fun setup() {
            val repo = File(System.getProperty("amrit.repo") ?: "..")
            ref = RefData(JdbcSql.open(File(repo, "app/src/main/assets/amrit_ref.db").absolutePath))
            rules = RuleEngine(ref)
        }
    }

    private val today = YearMonth.of(2026, 11)
    private fun item(name: String, expiry: YearMonth? = null, batch: String? = null): BoxAudit.Item {
        val p = ref.productsByName(name).first()
        val alerts = if (batch != null) BatchAlerts.check(ref, batch, p, p.items) else emptyList()
        return BoxAudit.Item(rules.evaluate(p, p.items), expiry, batch, alerts)
    }
    private fun audit(vararg items: BoxAudit.Item, courses: Set<String> = emptySet()) =
        BoxAudit.audit(items.toList(), rules, today, courses)

    @Test fun expiredBannedAndRecalledAreThrowAway() {
        val r = audit(item("Dolo 650 Tablet", expiry = YearMonth.of(2026, 8)), item("Nixar 100mg/500mg Tablet DT"),
            item("Telma-AM Tablet", batch = "18230791"))
        assertEquals(listOf(Group.THROW_AWAY, Group.THROW_AWAY, Group.THROW_AWAY), r.items.indices.map { r.groups[it] })
        val reasons = r.findings.map { it.reason }.toSet()
        assertTrue(reasons.containsAll(setOf(Reason.EXPIRED, Reason.BANNED, Reason.RECALLED_BATCH)))
    }

    @Test fun thisMonthIsNotExpiredYet() {
        val r = audit(item("Dolo 650 Tablet", expiry = today))
        assertEquals(Group.FINE, r.groups[0])
    }

    @Test fun duplicateAndInteractionAreAskDoctor() {
        val r = audit(item("Dolo 650 Tablet"), item("Crocin Advance Tablet"))
        val dup = r.findings.single { it.reason == Reason.DUPLICATE }
        assertEquals(setOf(0, 1), dup.items.toSet())
        assertEquals(Group.ASK_DOCTOR, r.groups[0]); assertEquals(Group.ASK_DOCTOR, r.groups[1])
    }

    @Test fun antibioticInBoxIsLeftoverUnlessCourseActive() {
        val leftover = audit(item("Azithral 500 Tablet"))
        assertEquals(Reason.LEFTOVER_ANTIBIOTIC, leftover.findings.single().reason)
        assertEquals(Group.ASK_DOCTOR, leftover.groups[0])
        val onCourse = audit(item("Azithral 500 Tablet"), courses = setOf("Azithral 500 Tablet"))
        assertTrue(onCourse.findings.isEmpty())
        assertEquals(Group.FINE, onCourse.groups[0])
    }

    @Test fun sameStripTwiceIsOneItem() {
        val r = audit(item("Dolo 650 Tablet"), item("Dolo 650 Tablet"))
        assertEquals(1, r.items.size)
        assertTrue(r.findings.isEmpty())
    }

    @Test fun throwAwayWinsOverAskDoctor() {
        // an expired strip that is also a double dose: it goes in the bin, not to the doctor
        val r = audit(item("Dolo 650 Tablet", expiry = YearMonth.of(2025, 1)), item("Crocin Advance Tablet"))
        assertEquals(Group.THROW_AWAY, r.groups[0])
        assertEquals(Group.ASK_DOCTOR, r.groups[1])
    }

    @Test fun cleanStripIsFine() {
        val r = audit(item("Dolo 650 Tablet"))
        assertTrue(r.findings.isEmpty()); assertEquals(Group.FINE, r.groups[0])
    }

    @Test fun everyFindingHasWordsNamingTheMedicine() {
        val r = audit(item("Dolo 650 Tablet", expiry = YearMonth.of(2026, 8)), item("Nixar 100mg/500mg Tablet DT"),
            item("Telma-AM Tablet", batch = "18230791"), item("Crocin Advance Tablet"), item("Azithral 500 Tablet"))
        for (lang in listOf(Lang.EN, Lang.HI)) for (f in r.findings) {
            val line = Texts.boxFinding(f, r, lang)
            assertTrue("$lang ${f.reason}: ${line.title}", line.title.isNotBlank() && !line.title.contains("{"))
            val names = f.items.map { r.items[it].report.product.name }
            assertTrue("$lang ${f.reason} names a product: ${line.text}", names.any { line.text.contains(it) })
        }
        assertEquals(Texts.Kind.DANGER, Texts.boxFinding(r.findings.first { it.reason == Reason.EXPIRED }, r, Lang.EN).kind)
        assertEquals(Texts.Kind.WARNING, Texts.boxFinding(r.findings.first { it.reason == Reason.LEFTOVER_ANTIBIOTIC }, r, Lang.EN).kind)
    }
}
