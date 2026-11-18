package org.amrit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Bill saver: printed pharmacy bill lines -> products, quantity, amount; Jan Aushadhi saving per line. */
class BillReaderTest {
    companion object {
        lateinit var ref: RefData
        lateinit var index: BrandIndex
        lateinit var rules: RuleEngine
        @BeforeClass @JvmStatic fun setup() {
            val repo = File(System.getProperty("amrit.repo") ?: "..")
            ref = RefData(JdbcSql.open(File(repo, "app/src/main/assets/amrit_ref.db").absolutePath))
            index = BrandIndex(ref.allBaseKeys())
            rules = RuleEngine(ref)
        }
    }
    private fun bill(vararg lines: String) = BillReader.read(OcrResult(lines.map { OcrLine(it, 20f) }), ref, index)

    @Test fun readsPrintedBillLines() {
        val r = bill("MEDICO PHARMACY", "1 DOLO 650 TAB 15 30.50", "2. AZITHRAL 500 TAB 3 120.00", "PAN 40 TAB 10 155.00",
            "GST 12% 18.00", "TOTAL 323.50")
        assertEquals(listOf("Dolo 650 Tablet", "Azithral 500 Tablet", "PAN 40 Tablet"), r.lines.map { it.product.name })
        val dolo = r.lines.first()
        assertEquals(15, dolo.qty); assertEquals(30.5, dolo.amount!!, 1e-9)
        assertTrue("tax and total lines are not items: ${r.unrecognised}", r.unrecognised.isEmpty())
    }

    @Test fun leadingFormWordIsSkipped() {
        assertEquals("Dolo 650 Tablet", bill("TAB DOLO 650 15 30.50").lines.single().product.name)
    }

    @Test fun brandInsideWordIsNotMatched() {
        val r = bill("DOLOMITE POWDER 1 50.00")
        assertTrue(r.lines.isEmpty())
        assertEquals(listOf("DOLOMITE POWDER 1 50.00"), r.unrecognised)
    }

    @Test fun variantMustBePrintedOnTheLine() {
        // "PAN TAB 10 155.00" names no strength: PAN 20 or PAN 40 would be a guess
        val r = bill("PAN TAB 10 155.00")
        assertTrue(r.lines.isEmpty()); assertEquals(1, r.unrecognised.size)
    }

    @Test fun missingQtyUsesOnePack() {
        val r = bill("DOLO 650 TABLET")
        val line = r.lines.single()
        assertNull(line.qty); assertNull(line.amount)
        val s = BillReader.savings(r.lines, rules).single()
        val g = s.generic!!
        assertEquals(g.brandUnitPrice!! * 15, s.brandCost!!, 0.01)       // Dolo 650 is a strip of 15
        assertEquals(g.genericUnitPrice!! * 15, s.genericCost!!, 0.01)
        assertTrue(s.genericCost!! < s.brandCost!!)
    }

    @Test fun amountOnTheBillIsWhatWasPaid() {
        val s = BillReader.savings(bill("DOLO 650 TAB 15 30.50").lines, rules).single()
        assertEquals(30.5, s.brandCost!!, 1e-9)
        assertEquals(s.generic!!.genericUnitPrice!! * 15, s.genericCost!!, 0.01)
    }

    @Test fun totalsOnlyLinesWithGeneric() {
        val sv = BillReader.savings(bill("DOLO 650 TAB 15 30.50", "AZITHRAL 500 TAB 3 120.00").lines, rules)
        val (brand, generic) = BillReader.total(sv)
        val withG = sv.filter { it.generic != null && it.brandCost != null && it.genericCost != null }
        assertEquals(withG.sumOf { it.brandCost!! }, brand, 1e-9)
        assertEquals(withG.sumOf { it.genericCost!! }, generic, 1e-9)
    }

    @Test fun batchAndExpiryColumnsAreNotTheQuantity() {
        // real bills print "Batch  Exp  Qty  Amount": the expiry 08/27 must not be read as quantity 8
        val r = bill("1  DOLO 650 TAB          DOBS4021 08/27   15     30.50", "3  AZITHRAL 500 TAB      AZ8810  11/27    3    120.00",
            "Sl Product Batch Exp Qty Amount", "CGST 6% 19.99", "NET AMOUNT 368.10")
        assertEquals(listOf(15, 3), r.lines.map { it.qty })
        assertEquals(listOf(30.5, 120.0), r.lines.map { it.amount })
        assertTrue(r.unrecognised.isEmpty())
    }

    @Test fun expiryWithFullYearIsNotTheQuantityEither() {
        assertEquals(10, bill("PAN 40 TAB PN4471 02/2028 10 155.00").lines.single().qty)
    }

    @Test fun columnsReadAsSeparateBlocksAreJoinedIntoRows() {
        // ML Kit returns a bill table column by column; each piece carries its box (left, top, right, bottom)
        fun at(t: String, x: Int, y: Int) = OcrLine(t, 30f, 0.9f, listOf(x, y, x + 20 * t.length, y + 30))
        val ocr = OcrResult(listOf(
            at("1 DOLO 650 TAB", 60, 400), at("3 AZITHRAL 500 TAB", 60, 500),        // the product column
            at("DOBS4021", 520, 402), at("AZ8810", 520, 503),                          // batch
            at("08/27", 700, 398), at("11/27", 700, 501),                              // expiry
            at("15", 820, 401), at("3", 820, 499),                                    // qty
            at("30.50", 900, 403), at("120.00", 900, 500)))                            // amount
        val r = BillReader.read(ocr, ref, index)
        assertEquals(listOf("Dolo 650 Tablet", "Azithral 500 Tablet"), r.lines.map { it.product.name })
        assertEquals(listOf(15, 3), r.lines.map { it.qty })
        assertEquals(listOf(30.5, 120.0), r.lines.map { it.amount })
    }

    @Test fun realPhoneOcrOfASlightlyTiltedBill() {
        // ML Kit's output on the Moto for a bill photo tilted 1.5°: the Amount column sits ~17 px above its product name
        fun at(t: String, vararg b: Int) = OcrLine(t, (b[3] - b[1]).toFloat(), 0.9f, b.toList())
        val ocr = OcrResult(listOf(
            at("DEMO PHARMACY", 63, 84, 345, 122), at("Sl Product", 69, 322, 234, 354),
            at("1", 72, 399, 84, 418), at("2", 73, 449, 87, 468), at("3", 75, 499, 88, 518), at("4", 74, 548, 89, 567),
            at("DOLO 650 TAB", 122, 390, 322, 416), at("CROCIN ADVANCE TAB", 123, 437, 424, 468),
            at("AZITHRAL 500 TAB", 124, 487, 392, 516), at("PAN 40 TAB", 126, 540, 291, 566),
            at("CGST 6%", 76, 611, 192, 638), at("SGST 6%", 78, 661, 193, 688), at("NET AMOUNT", 79, 712, 244, 738),
            at("Batch Exp Qty", 478, 314, 763, 343), at("DOBS402108/2 7", 492, 377, 696, 411), at("CA2291 05/27 20", 480, 427, 765, 459),
            at("AZ881", 491, 481, 564, 510), at("PN4471", 484, 536, 582, 557), at("15", 735, 381, 764, 400),
            at("11/27", 617, 477, 697, 507), at("3", 755, 481, 767, 500), at("02/28 15", 619, 531, 768, 556),
            at("19.99", 707, 597, 786, 621), at("19.99", 708, 647, 787, 671), at("368.10", 693, 698, 788, 722),
            at("Amount", 816, 310, 915, 332), at("30.50", 853, 373, 933, 398), at("22.62", 854, 424, 935, 447),
            at("120.00", 839, 473, 936, 498), at("155.00", 841, 523, 937, 549)))
        val r = BillReader.read(ocr, ref, index)
        assertEquals(listOf("Dolo 650 Tablet", "Crocin Advance Tablet", "Azithral 500 Tablet", "PAN 40 Tablet"), r.lines.map { it.product.name })
        assertEquals(listOf(15, 20, 3, 15), r.lines.map { it.qty })
        assertEquals(listOf(30.5, 22.62, 120.0, 155.0), r.lines.map { it.amount })
    }
}
