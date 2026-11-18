package org.amrit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Same salt, other brands: identical composition and strength, same dosage form and release type, cheapest first. */
class AlternativesTest {
    companion object {
        lateinit var ref: RefData
        @BeforeClass @JvmStatic fun setup() {
            val repo = File(System.getProperty("amrit.repo") ?: "..")
            ref = RefData(JdbcSql.open(File(repo, "app/src/main/assets/amrit_ref.db").absolutePath))
        }
    }
    private fun p(name: String) = ref.productsByName(name).first()

    @Test fun sameCompositionStrengthAndFormOnly() {
        val dolo = p("Dolo 650 Tablet")
        val r = Alternatives.sameSalt(dolo, ref, limit = 50)
        assertTrue(r.total >= 20)
        for (o in r.options) {
            assertEquals(o.product.name, dolo.compositionId, o.product.compositionId)
            assertEquals(o.product.name, dolo.form, o.product.form)       // no syrups, no dispersible tablets
            assertTrue(o.product.name, o.product.name != dolo.name && !o.product.discontinued)
        }
    }

    @Test fun cheapestFirstWithTheSavingAgainstTheScannedBrand() {
        val dolo = p("Dolo 650 Tablet")
        val r = Alternatives.sameSalt(dolo, ref)
        assertEquals(5, r.options.size)
        val prices = r.options.map { it.unitPrice!! }
        assertEquals(prices.sorted(), prices)
        assertEquals(34.27 / 15, r.unitPrice!!, 1e-6)
        val first = r.options.first()
        assertEquals(Math.round((1 - first.unitPrice!! / r.unitPrice!!) * 100).toInt(), first.savingPercent)
    }

    @Test fun releaseTypeMustMatch() {
        // a sustained-release tablet is not a swap for a plain one, or the other way round
        assertTrue(Alternatives.sameSalt(p("Dolo 650 Tablet"), ref, limit = 500).options
            .none { o -> Regex("""(?i)\b(SR|ER|CR|XR|MR|OD|XL|PR)\b""").containsMatchIn(o.product.name) })
    }

    @Test fun possiblyTruncatedCompositionsGetNothing() {
        val truncated = ref.productsWhereIncomplete(1).first()
        assertEquals(0, Alternatives.sameSalt(truncated, ref).total)
    }
}
