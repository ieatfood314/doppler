package ca.heeney.doppler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime

/** Today's screen time for the square widget, as lightcurve counts it: every screen, two at once counted once.
 * lightcurve's phone app sends it after each of its syncs (every 15 minutes, and whenever it's asked). */
object ScreenTime {
    const val LIGHTCURVE = "ca.heeney.lightcurve"
    /** lightcurve -> doppler: extras "lit" (seconds, Long) and "date" (yyyy-mm-dd, lightcurve's day). */
    const val TODAY = "ca.heeney.lightcurve.TODAY"
    /** doppler -> lightcurve: sync now and send TODAY. */
    private const val REFRESH = "ca.heeney.lightcurve.REFRESH"

    private const val PREFS = "screen_time"

    /** Seconds lit today, or null when lightcurve hasn't said anything about today. */
    fun today(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("date", null) != day().toString()) return null
        return prefs.getLong("lit", -1).takeIf { it >= 0 }
    }

    fun save(context: Context, lit: Long, date: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("lit", lit).putString("date", date)
            .putLong("heard", System.currentTimeMillis()).apply()
    }

    /** lightcurve's day runs from 04:00 to 04:00. */
    private fun day(): LocalDate = LocalDateTime.now().minusHours(4).toLocalDate()

    /** Asks lightcurve for a fresh number, unless it was asked or answered in the last minute.
     * Nothing happens if it isn't installed. */
    fun ask(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (listOf("heard", "asked").any { now - prefs.getLong(it, 0) in 0 until 60_000 }) return
        prefs.edit().putLong("asked", now).apply()
        runCatching { context.sendBroadcast(Intent(REFRESH).setPackage(LIGHTCURVE)) }
    }

    /** lightcurve's own screen, for a tap on the time. */
    fun open(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(LIGHTCURVE)
}

class ScreenTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScreenTime.TODAY) return
        val lit = intent.getLongExtra("lit", -1)
        val date = intent.getStringExtra("date") ?: return
        if (lit < 0) return
        ScreenTime.save(context, lit, date)
        Widgets.refresh(context)
    }
}
