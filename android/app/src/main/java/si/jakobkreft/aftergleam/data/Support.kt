package si.jakobkreft.aftergleam.data

import android.content.Context
import android.os.Build
import java.time.LocalDate

/**
 * Asking readers to help the app, and above all when not to.
 *
 * There is one quiet place that is always there, a page in settings, and one occasional
 * note. The note is a card after the end of the day's digest rather than a dialog. A dialog
 * interrupts whatever the reader opened the app to do; the end of the digest is the one
 * moment when they have just been given what they came for and are about to leave anyway.
 * Placing it there also means only readers who finish the digest ever see it.
 *
 * It waits for [FIRST_ASK_AFTER] separate days on which the reader opened, read or reacted to
 * something. Returning is the most honest sign that the app is useful to somebody: a first
 * session can be curiosity, and ten papers can be opened in one evening of looking around,
 * but coming back on five different days to read is a habit.
 *
 * After that the rule is deliberately simple enough to state on the card. It stays at the end
 * of the digest until the reader answers it. "Not now" puts it away for [LATER_DAYS] days, by
 * the calendar, and then it is back; "don't ask again" puts it away for good.
 */
object Support {

    /** Where money goes, in copies not installed from Google Play. */
    const val DONATE_URL = "https://ko-fi.com/jakobk"

    /** Where readers write to. Routed to the developer; distinct from the address APIs see. */
    const val CONTACT = "hello@aftergleam.app"

    /** Separate reading days before the note first appears. */
    const val FIRST_ASK_AFTER = 5

    /** How long "not now" puts it away for. */
    const val LATER_DAYS = 7L

    /** How long it stays away after the reader acts on it. */
    const val QUIET_AFTER_ACTING_DAYS = 365L

    enum class Card { ASK, THANKS }

    /**
     * Whether today's digest should end with the note.
     *
     * @param quietUntil the first day it may appear again, after "not now" or after the reader
     *   followed one of its links.
     */
    fun due(
        enabled: Boolean,
        readingDays: Int,
        today: LocalDate,
        quietUntil: LocalDate?,
    ): Boolean =
        enabled && readingDays >= FIRST_ASK_AFTER &&
            (quietUntil == null || !today.isBefore(quietUntil))

    /**
     * Whether this copy may ask for money.
     *
     * Google Play requires its own billing for payments to a developer, exempts only
     * tax-exempt donations, and counts any in-app link, button or message that leads to
     * another way of paying. Pointing at a website or the source repository instead of Ko-fi
     * changes nothing if that page carries a donate button. F-Droid and GitHub have no such
     * rule. So a copy installed from Play asks for a rating, a share or a note instead, and
     * everything else is the same build.
     */
    fun donationsAllowed(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        installer != PLAY_STORE
    }.getOrDefault(true)

    fun playListing(packageName: String) =
        "https://play.google.com/store/apps/details?id=$packageName"

    private const val PLAY_STORE = "com.android.vending"
}
