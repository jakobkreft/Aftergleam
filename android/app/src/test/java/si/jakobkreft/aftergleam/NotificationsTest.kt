package si.jakobkreft.aftergleam

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import si.jakobkreft.aftergleam.data.DailyNote
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.KeywordAlerts
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Prefs
import si.jakobkreft.aftergleam.data.ShownItem
import si.jakobkreft.aftergleam.data.Signal
import si.jakobkreft.aftergleam.ui.Combining
import si.jakobkreft.aftergleam.work.KeywordAlertWorker
import si.jakobkreft.aftergleam.work.Notifications
import java.time.LocalDate
import java.time.LocalTime

/** The daily note and the keyword bells: what each says, when, and about what. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationsTest {

    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    // The real day: what is told about today is found by the time it was recorded.
    private val today = LocalDate.now()

    private fun paper(id: String, title: String, published: String = today.toString()) =
        Paper(id, title, "An abstract.", listOf("A. Chen", "B. Diaz"), listOf("q-bio.CB"), published, published)

    private fun posted(): List<Notification> =
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications

    @Before
    fun allow() = shadowOf(ctx).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun `news is recent, unseen, and told once under the first keyword that finds it`() {
        val new = paper("n", "Lanosterol under hypoxia")
        val old = paper("o", "Lanosterol, last month", today.minusDays(30).toString())
        val friday = paper("f", "Submitted on Friday", today.minusDays(3).toString())
        val seen = paper("s", "Already opened")
        val fresh = KeywordAlerts.fresh(
            mapOf(
                "lanosterol + hypoxia" to listOf(new, old, seen, friday),
                "hypoxia" to listOf(new, paper("h", "Hypoxia only")),
            ),
            known = setOf("s"),
            today = today,
        )
        assertEquals(listOf("n", "f"), fresh.getValue("lanosterol + hypoxia").map { it.id })
        assertEquals("not told twice", listOf("h"), fresh.getValue("hypoxia").map { it.id })
    }

    @Test
    fun `alerts wait for the day`() {
        assertFalse(KeywordAlerts.inWindow(LocalTime.of(7, 59)))
        assertTrue(KeywordAlerts.inWindow(LocalTime.of(8, 0)))
        assertTrue(KeywordAlerts.inWindow(LocalTime.of(20, 59)))
        assertFalse(KeywordAlerts.inWindow(LocalTime.of(21, 0)))
    }

    @Test
    fun `how often a keyword would ring, in words`() {
        fun days(n: Int, span: Int) = (0 until n).map { today.minusDays((it * span / n).toLong()).toString() }
        assertEquals("none in the last two months", KeywordAlerts.describe(KeywordAlerts.perWeek(emptyList(), today)))
        assertEquals("about 1 a week", KeywordAlerts.describe(KeywordAlerts.perWeek(days(9, 60), today)))
        assertEquals("a full fetch from one week is a busy keyword", "about 35 a week",
            KeywordAlerts.describe(KeywordAlerts.perWeek(days(40, 8), today)))
        assertEquals("about 1 a month", KeywordAlerts.describe(KeywordAlerts.perWeek(days(2, 60), today)))
        assertEquals("rarely", KeywordAlerts.describe(KeywordAlerts.perWeek(days(1, 60), today)))
    }

    @Test
    fun `the daily note names the first paper of the digest not yet looked at`() {
        val digest = listOf(
            ShownItem("a", "RELEVANCE", "mentions “LoRA”", 0.9f),
            ShownItem("b", "RELEVANCE", "", 0.8f),
        )
        assertEquals("a", DailyNote.pick(digest, acted = emptySet())?.paperId)
        assertEquals("b", DailyNote.pick(digest, acted = setOf("a"))?.paperId)
        assertNull(DailyNote.pick(digest, acted = setOf("a", "b")))
        assertEquals("First in today's digest, mentions “LoRA”", DailyNote.detail("Mentions “LoRA”"))
    }

    @Test
    fun `from the library it names a different kept paper each day, oldest first`() {
        val kept = mapOf("new" to 3_000L, "old" to 1_000L, "mid" to 2_000L)
        val days = (0L until 4L).map { DailyNote.fromLibrary(kept, LocalDate.ofEpochDay(30L + it)) }
        assertEquals(listOf("old", "mid", "new", "old"), days)
        assertNull(DailyNote.fromLibrary(emptyMap(), today))
    }

    @Test
    fun `the note names today's first paper even after the app was opened, then a kept one`() {
        val db = Db(ctx)
        db.upsertPapers(listOf(paper("d", "First in the digest"), paper("k", "Kept for later")))
        db.markShown(listOf(ShownItem("d", "RELEVANCE", "", 0.5f)), today.toString())
        db.save("k")

        assertTrue(Notifications.postDailyNote(ctx, today))
        val first = posted().single()
        assertEquals(DailyNote.TITLE, first.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("First in the digest", first.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        db.addSignal("d", Signal.OPENED)
        assertTrue(Notifications.postDailyNote(ctx, today))
        val kept = posted().single()
        assertEquals(DailyNote.LIBRARY_TITLE, kept.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Kept for later\nSaved today", kept.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }

    @Test
    fun `with no digest and nothing kept there is nothing to name`() {
        assertFalse(Notifications.postDailyNote(ctx, today))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `everyone is asked once, after onboarding, and a yes turns the daily note on at ten`() {
        val prefs = Prefs(ctx)
        assertFalse("not during onboarding", Notifications.promptDue(prefs))
        prefs.onboarded = true
        assertTrue(Notifications.promptDue(prefs))

        Notifications.promptAnswered(prefs, granted = true, today)
        assertTrue(prefs.reminderEnabled)
        assertEquals(10, prefs.reminderHour)
        assertFalse("asked once", Notifications.promptDue(prefs))
        assertFalse("nothing to offer after a yes", Notifications.offerDue(prefs, today.plusDays(1)))
    }

    @Test
    fun `a yes leaves alone a note the reader switched off, and keeps the hour they chose`() {
        val prefs = Prefs(ctx).apply { onboarded = true; reminderEnabled = false; reminderHour = 14 }
        Notifications.promptAnswered(prefs, granted = true, today)
        assertFalse(prefs.reminderEnabled)
        prefs.reminderEnabled = true
        Notifications.promptAnswered(prefs, granted = true, today)
        assertTrue(prefs.reminderEnabled)
        assertEquals(14, prefs.reminderHour)
    }

    @Test
    fun `after a no the digest offers the note once, from the next day, until it is answered`() {
        val prefs = Prefs(ctx).apply { onboarded = true }
        Notifications.promptAnswered(prefs, granted = false, today)
        assertFalse(prefs.reminderEnabled)
        assertFalse("not asked again", Notifications.promptDue(prefs))
        assertFalse("not on the day of the no", Notifications.offerDue(prefs, today))
        assertTrue(Notifications.offerDue(prefs, today.plusDays(1)))
        prefs.noteOfferAnswered = true
        assertFalse("answered, either way, it does not return", Notifications.offerDue(prefs, today.plusDays(2)))
    }

    @Test
    fun `bells are kept by keyword, with the day each was rung`() {
        val prefs = Prefs(ctx)
        prefs.keywordAlerts = mapOf("lanosterol + hypoxia" to "2026-10-19", "Ca2+" to "2026-10-01")
        assertEquals(mapOf("lanosterol + hypoxia" to "2026-10-19", "Ca2+" to "2026-10-01"), prefs.keywordAlerts)
    }

    @Test
    fun `a bell follows its keyword as it is combined, and goes with a keyword combined in`() {
        val open = Combining(listOf("lanosterol", "hypoxia", "sterols"), open = 0, alert = true).add("hypoxia")
        assertEquals(setOf("lanosterol + hypoxia", "sterols"), open.alerts(setOf("lanosterol", "hypoxia", "sterols")))
        val off = open.copy(alert = false)
        assertEquals(setOf("sterols"), off.alerts(setOf("lanosterol", "sterols")))
    }

    @Test
    fun `a bell announces only what comes after it, once, and later papers join quietly`() {
        val db = Db(ctx)
        val prefs = Prefs(ctx)
        val keyword = "lanosterol + hypoxia"
        prefs.keywords = listOf(keyword)
        db.upsertPapers(listOf(paper("before", "Lanosterol and hypoxia, already here")))
        db.baselineAlerts(keyword, today)
        prefs.keywordAlerts = mapOf(keyword to today.toString())

        KeywordAlertWorker.check(ctx, db, prefs, listOf(keyword), today)
        assertTrue("nothing new yet", posted().isEmpty())

        db.upsertPapers(listOf(paper("new1", "Lanosterol accumulates under hypoxia")))
        KeywordAlertWorker.check(ctx, db, prefs, listOf(keyword), today)
        val first = posted()
        val child = first.single { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        assertEquals(keyword, child.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Lanosterol accumulates under hypoxia", child.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Save for later", child.actions.single().title.toString())

        KeywordAlertWorker.check(ctx, db, prefs, listOf(keyword), today)
        assertEquals("told once", first.size, posted().size)

        db.upsertPapers(listOf(paper("new2", "Hypoxia raises lanosterol")))
        KeywordAlertWorker.check(ctx, db, prefs, listOf(keyword), today)
        val summary = posted().single { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        val lines = summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        assertEquals("both in one note, newest first",
            listOf("Hypoxia raises lanosterol", "Lanosterol accumulates under hypoxia"), lines)
        assertTrue("the second batch does not sound again", summary.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun `papers the reader acted on are not news`() {
        val db = Db(ctx)
        db.addSignal("x", Signal.OPENED)
        db.save("y")
        assertEquals(setOf("x", "y"), db.actedOnIds())
        assertTrue(db.allReactions().getValue("y").saved)
    }
}
