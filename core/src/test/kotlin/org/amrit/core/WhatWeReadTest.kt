package org.amrit.core

import org.amrit.core.WhatWeRead.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.1: which lines of the frozen frame were the brand, the ingredients, the batch and the expiry. */
class WhatWeReadTest {
    companion object {
        lateinit var ref: RefData
        @org.junit.BeforeClass @JvmStatic fun setup() {
            val repo = java.io.File(System.getProperty("amrit.repo") ?: "..")
            ref = RefData(JdbcSql.open(java.io.File(repo, "app/src/main/assets/amrit_ref.db").absolutePath))
        }
    }
    private fun l(t: String, h: Float) = OcrLine(t, h, 0.9f)

    @Test fun eachLineGetsTheRoleItPlayedInTheAnswer() {
        val dolo = ref.productsByName("Dolo 650 Tablet").first()
        val lines = listOf(
            l("DOLO-650", 90f),                                   // 0 brand
            l("Each uncoated tablet contains:", 14f),             // 1 nothing
            l("Paracetamol IP 650 mg", 14f),                      // 2 ingredient
            l("Mfd. by Micro Labs Limited", 12f),                 // 3 nothing
            l("B.No. DOBS4021", 12f),                             // 4 batch
            l("EXP. 08/2027", 12f),                               // 5 expiry
            l("Store in a cool dry place", 11f),                  // 6 nothing
        )
        val roles = WhatWeRead.roles(lines, dolo, CompositionReader(ref))
        assertEquals(mapOf(0 to Role.BRAND, 2 to Role.INGREDIENT, 4 to Role.BATCH, 5 to Role.EXPIRY), roles)
    }

    @Test fun ingredientsNotInTheAnswerAreNotHighlighted() {
        // the answer is Dolo (paracetamol only): a stray "Caffeine" line on the strip is not presented as what we used
        val dolo = ref.productsByName("Dolo 650 Tablet").first()
        val roles = WhatWeRead.roles(listOf(l("DOLO 650", 90f), l("Caffeine IP 30 mg", 14f)), dolo, CompositionReader(ref))
        assertEquals(mapOf(0 to Role.BRAND), roles)
    }

    @Test fun brandIsMatchedAsAWholeWordNotInsideAnotherWord() {
        val dolo = ref.productsByName("Dolo 650 Tablet").first()
        val roles = WhatWeRead.roles(listOf(l("Doloride gel", 40f)), dolo, CompositionReader(ref))
        assertTrue(roles.isEmpty())
    }
}
