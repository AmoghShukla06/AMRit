package org.amrit.app

import org.amrit.app.ui.skinFor
import org.amrit.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Larger text" setting scales every text size of the look; the default look is unchanged. */
class LargeTextTest {
    @Test fun largerTextScalesEveryTextSize() {
        val normal = skinFor(Lang.EN, dark = false, large = false)
        val large = skinFor(Lang.EN, dark = false, large = true)
        for ((n, l) in listOf(normal.body to large.body, normal.small to large.small, normal.title to large.title,
                normal.display to large.display, normal.hero to large.hero)) {
            assertTrue("$l should be larger than $n", l.value >= n.value * 1.15f)
        }
        assertEquals(16f, normal.body.value)
    }

    @Test fun hindiKeepsItsFontWhenLarger() {
        assertEquals(skinFor(Lang.HI, false, false).font, skinFor(Lang.HI, false, true).font)
    }
}
