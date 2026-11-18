package org.amrit.core

import org.amrit.core.Passport.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The family antibiotic passport: course status from what was logged, and history-based prompts from fixed rules. */
class PassportTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test fun statusFollowsWhatWasLogged() {
        // 5-day course, 2 a day = 10 doses
        assertEquals(Status.ONGOING, Passport.status(true, now - 2 * day, 5, 2, 4, null, now))
        assertEquals(Status.COMPLETED, Passport.status(true, now - 9 * day, 5, 2, 10, null, now))
        assertEquals(Status.COMPLETED, Passport.status(false, now - 9 * day, 5, 2, 10, now - 4 * day, now))
        // removed on day 2 of 5 with doses missing: stopped early
        assertEquals(Status.STOPPED_EARLY, Passport.status(false, now - 9 * day, 5, 2, 3, now - 7 * day, now))
        // the 5 days are over but not every dose was ticked: we don't know it was stopped, so it is not called "stopped"
        assertEquals(Status.ENDED, Passport.status(true, now - 9 * day, 5, 2, 6, null, now))
        assertEquals(Status.ENDED, Passport.status(false, now - 9 * day, 5, 2, 6, null, now))
    }

    private fun e(daysAgo: Int, aware: Aware? = Aware.Access, status: Status = Status.COMPLETED, prescribed: Boolean? = true) =
        Passport.Entry("X", "x", aware, now - daysAgo * day, 5, 10, 10, prescribed, status)

    @Test fun twoWatchIn90DaysWarns() {
        val ins = Passport.insights(listOf(e(10, Aware.Watch), e(60, Aware.Reserve), e(20)), now)
        val w = ins.single { it.key == "passport.insight.watch" }
        assertEquals(2, w.args.toMap()["n"])
    }

    @Test fun oldCoursesDontCount() {
        assertTrue(Passport.insights(listOf(e(100, Aware.Watch), e(120, Aware.Watch)), now).none { it.key == "passport.insight.watch" })
        assertTrue(Passport.insights(listOf(e(400, status = Status.STOPPED_EARLY, prescribed = false)), now).isEmpty())
    }

    @Test fun stoppedEarlyAndNoRxInsights() {
        val keys = Passport.insights(listOf(e(30, status = Status.STOPPED_EARLY), e(50, prescribed = false)), now).map { it.key }
        assertTrue(keys.contains("passport.insight.stopped")); assertTrue(keys.contains("passport.insight.noRx"))
    }

    @Test fun threeCoursesInAYearIsMany() {
        val ins = Passport.insights(listOf(e(10), e(150), e(300)), now)
        assertEquals(3, ins.single { it.key == "passport.insight.many" }.args.toMap()["n"])
        // antibiotics only kept in the house are not courses
        assertTrue(Passport.insights(listOf(e(10), e(20, status = Status.IN_HOUSE), e(30, status = Status.IN_HOUSE)), now).none { it.key == "passport.insight.many" })
    }

    @Test fun noAntibioticsNoInsights() { assertTrue(Passport.insights(emptyList(), now).isEmpty()) }

    @Test fun insightsHaveWordsInEveryCompleteLanguage() {
        val ins = Passport.insights(listOf(e(10, Aware.Watch, Status.STOPPED_EARLY, false), e(20, Aware.Watch), e(30)), now)
        for (lang in listOf(Lang.EN, Lang.HI)) for (i in ins) {
            val t = Texts.passportInsight(i, lang)
            assertTrue("$lang ${i.key}: $t", t.isNotBlank() && !t.contains("{"))
        }
    }
}
