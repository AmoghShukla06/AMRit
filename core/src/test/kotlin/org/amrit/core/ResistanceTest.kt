package org.amrit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** ICMR AMRSN 2024 resistance facts: only drug/organism pairs printed in the report, matched to the exact antibiotic. */
class ResistanceTest {
    companion object {
        lateinit var ref: RefData
        @BeforeClass @JvmStatic fun setup() {
            val repo = File(System.getProperty("amrit.repo") ?: "..")
            ref = RefData(JdbcSql.open(File(repo, "app/src/main/assets/amrit_ref.db").absolutePath))
        }
    }
    private fun of(name: String) = ref.productsByName(name).first().let { ref.resistance(it.items) }

    @Test fun ciprofloxacinHasUrineTyphoidAndStaphRows() {
        val rows = of("Ciplox 500 Tablet")
        assertEquals(setOf("urine", "typhoid", "staph"), rows.map { it.infection }.toSet())
        val typhoid = rows.single { it.infection == "typhoid" }
        assertEquals(11, typhoid.susceptible); assertEquals(841, typhoid.tested); assertEquals(1.3, typhoid.pct, 1e-9)
        assertEquals(116, typhoid.reportPage)
    }

    @Test fun combinationNeedsBothDrugs() {
        val rows = of("Bactrim DS Tablet")
        assertTrue(rows.isNotEmpty()); assertTrue(rows.all { it.antibiotic == "Trimethoprim-sulfamethoxazole" })
    }

    @Test fun drugsTheReportDoesNotCoverGetNothing() {
        assertTrue(of("Dolo 650 Tablet").isEmpty())
        assertTrue(of("Augmentin 625 Duo Tablet").isEmpty())     // amoxicillin-clavulanate is not in these tables
        assertTrue(of("Cifran CT Tablet").isEmpty())             // ciprofloxacin + tinidazole: the pair isn't what ICMR tested
    }

    @Test fun cardNamesTheSourceAndSaysItIsNotATestOfYou() {
        val ciplox = ref.productsByName("Ciplox 500 Tablet").first()
        for (lang in listOf(Lang.EN, Lang.HI)) {
            val line = Texts.resistance(ref.resistance(ciplox.items), lang)
            assertNotNull(line)
            assertTrue(line!!.source!!.contains("ICMR"))
            assertTrue("$lang ${line.body}", line.body!!.contains("1.3%") && line.body!!.contains("22.4%") && !line.body!!.contains("{"))
        }
        assertEquals(Texts.Kind.WARNING, Texts.resistance(ref.resistance(ciplox.items), Lang.EN)!!.kind)
        assertNull(Texts.resistance(emptyList(), Lang.EN))
    }
}
