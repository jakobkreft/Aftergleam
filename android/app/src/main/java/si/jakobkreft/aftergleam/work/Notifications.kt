package si.jakobkreft.aftergleam.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import si.jakobkreft.aftergleam.MainActivity
import si.jakobkreft.aftergleam.R
import si.jakobkreft.aftergleam.data.DailyNote
import si.jakobkreft.aftergleam.data.Db
import si.jakobkreft.aftergleam.data.KeywordAlerts
import si.jakobkreft.aftergleam.data.Keywords
import si.jakobkreft.aftergleam.data.Paper
import si.jakobkreft.aftergleam.data.Prefs
import java.time.LocalDate

/**
 * Everything the app posts, in one place: the daily note and the keyword alerts.
 *
 * Two channels, so that Android's own settings can treat them differently. The daily note is
 * silent: the reader chose the hour, and a sound for a routine note would only teach them to
 * switch it off. A keyword alert uses the phone's ordinary sound, because the reader asked
 * for each one by ringing a keyword's bell; either can be changed in Android's settings.
 */
object Notifications {

    /** The old reading reminder's channel, kept so that what the reader set for it still holds. */
    const val DAILY_NOTE_CHANNEL = "reading_reminder"
    const val ALERT_CHANNEL = "keyword_alerts"

    /** The "papers are ready" notification's channel, no longer used. */
    private const val OLD_DIGEST_CHANNEL = "daily_digest"

    const val DAILY_NOTE_ID = 2

    const val EXTRA_PAPER = "si.jakobkreft.aftergleam.PAPER"
    const val EXTRA_KEYWORD = "si.jakobkreft.aftergleam.KEYWORD"
    private const val EXTRA_ID = "si.jakobkreft.aftergleam.NOTIFICATION"
    private const val EXTRA_GROUP = "si.jakobkreft.aftergleam.GROUP"
    private const val ACTION_SAVE = "si.jakobkreft.aftergleam.SAVE"

    /** Whether anything posted would be seen. From Android 13 posting is a permission. */
    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(DAILY_NOTE_CHANNEL, "Daily note", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet note naming the day's first paper, on days you have not opened the app."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Keyword alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A note when a new paper mentions a keyword with its bell on."
            }
        )
        nm.deleteNotificationChannel(OLD_DIGEST_CHANNEL)
    }

    /**
     * Carries the old settings over, once. The early notification is gone; a reader who had it
     * or the evening reminder on, and lets the app post, has the daily note instead, at the
     * reminder's hour. A new install has neither and is asked at the end of its first digest.
     *
     * @return whether the daily note is on afterwards, so the caller can schedule it.
     */
    fun migrate(context: Context, prefs: Prefs): Boolean {
        if (!prefs.notesMigrated) {
            if (prefs.onboarded && !prefs.reminderEnabled && prefs.notifyEnabled && canPost(context)) {
                prefs.reminderEnabled = true
            }
            prefs.notesMigrated = true
        }
        return prefs.reminderEnabled
    }

    fun postDailyNote(context: Context, paper: Paper, reason: String) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, DAILY_NOTE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(DailyNote.TITLE)
            .setContentText(paper.displayTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(paper.displayTitle + "\n" + DailyNote.detail(reason)))
            .setContentIntent(open(context, request = DAILY_NOTE_ID))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        post(context, DAILY_NOTE_ID, n)
    }

    /** The note is about a day the reader has now opened. */
    fun cancelDailyNote(context: Context) = NotificationManagerCompat.from(context).cancel(DAILY_NOTE_ID)

    /**
     * The app is on screen. Today counts as opened, so the daily note stays away and a note
     * already posted has done its work; and today's digest has been in front of the reader,
     * so its papers for a belled keyword are not news later in the day. Off the main thread.
     */
    fun appOpened(context: Context, today: LocalDate = LocalDate.now()) {
        val prefs = Prefs(context)
        prefs.lastOpenedDay = today.toString()
        cancelDailyNote(context)
        val belled = prefs.keywords.filter { it in prefs.keywordAlerts }
        if (belled.isEmpty()) return
        val db = Db(context)
        val papers = db.papersById(db.digestFor(today.toString()).map { it.paperId })
        for (keyword in belled) {
            val seen = papers.filter { Keywords.mentions(it.title + "\n" + it.abstract, keyword) }
            if (seen.isNotEmpty()) db.markAlerted(seen.map { it.id }, keyword, baseline = true)
        }
    }

    /**
     * One keyword's alert: a note for each paper, each opening its own page and with a Save
     * button, bundled under the keyword so that the shade shows one entry for it.
     *
     * @param papers today's papers for the keyword, newest first, those just found included.
     * @param quiet whether the keyword has already sounded today. Later papers join quietly.
     */
    fun postKeywordAlert(context: Context, keyword: String, papers: List<Paper>, quiet: Boolean) {
        if (papers.isEmpty()) return
        ensureChannels(context)
        val group = "keyword:$keyword"
        val summaryId = idOf(group)
        val shown = papers.take(KeywordAlerts.MAX_PER_KEYWORD)
        for (p in shown) {
            val id = idOf("paper:" + p.id)
            val n = NotificationCompat.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(keyword)
                .setContentText(p.displayTitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(p.displayTitle + "\n" + byline(p)))
                .setContentIntent(open(context, paperId = p.id, request = id))
                .addAction(0, "Save for later", save(context, p.id, id, group))
                .setAutoCancel(true)
                .setGroup(group)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setOnlyAlertOnce(true)
                .build()
            post(context, id, n)
        }
        // The bundle lists several titles and can open only one thing, so it opens them all:
        // the keyword's papers on the phone, the way a mail app's bundle opens the inbox.
        val summary = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(keyword)
            .setContentText(shown.first().displayTitle)
            .setStyle(NotificationCompat.InboxStyle().also { s -> shown.forEach { s.addLine(it.displayTitle) } })
            .setContentIntent(
                if (shown.size == 1) open(context, paperId = shown.first().id, request = summaryId)
                else open(context, keyword = keyword, request = summaryId)
            )
            .setAutoCancel(true)
            .setGroup(group)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setSilent(quiet)
            .build()
        post(context, summaryId, summary)
    }

    /** "bioRxiv · Chen et al.": where it is from and whose it is, under the title. */
    internal fun byline(p: Paper): String =
        listOfNotNull(p.sourceLabel ?: "arXiv", p.shortAuthors.ifBlank { null }).joinToString(" · ")

    /** A stable id for a notification, from what it is about. */
    internal fun idOf(key: String): Int = key.hashCode().let { if (it == DAILY_NOTE_ID) it + 1 else it }

    private fun open(context: Context, paperId: String? = null, keyword: String? = null, request: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Into the running app if there is one, rather than a second copy of it on top.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (paperId != null) putExtra(EXTRA_PAPER, paperId)
            if (keyword != null) putExtra(EXTRA_KEYWORD, keyword)
        }
        return PendingIntent.getActivity(
            context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun save(context: Context, paperId: String, id: Int, group: String): PendingIntent {
        val intent = Intent(context, SaveReceiver::class.java)
            .setAction(ACTION_SAVE)
            .putExtra(EXTRA_PAPER, paperId)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_GROUP, group)
        return PendingIntent.getBroadcast(
            context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun post(context: Context, id: Int, n: android.app.Notification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        // Asked here rather than trusted from the caller: posting without the permission
        // throws, and a refusal is the reader's answer, not a failure.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }

    /**
     * "Save for later" on an alert: saved as the bookmark on its card would save it, without
     * opening the app, and the note goes. The keyword's bundle goes too once it is empty.
     */
    class SaveReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val paperId = intent.getStringExtra(EXTRA_PAPER) ?: return
            val id = intent.getIntExtra(EXTRA_ID, 0)
            val group = intent.getStringExtra(EXTRA_GROUP)
            val app = context.applicationContext
            val pending = goAsync()
            Thread {
                try {
                    runCatching { Db(app).save(paperId) }
                    val nm = app.getSystemService(NotificationManager::class.java)
                    nm?.cancel(id)
                    if (nm != null && group != null) {
                        val left = nm.activeNotifications.filter {
                            it.notification.group == group && it.id != id
                        }
                        if (left.all { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 }) {
                            left.forEach { nm.cancel(it.id) }
                        }
                    }
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
