package ca.heeney.doppler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.Uri
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.util.Calendar
import java.util.Collections

/** Thrown instead of touching the network when it would cost mobile data. */
class NotOnWifi(message: String = "not on Wi-Fi") : IOException(message)

/** Whether the phone is on Wi-Fi, and the meter for the little it may send over mobile data.
 *
 * Music, covers, the library, play counts, playlist edits and the hub's live state only ever
 * move over an unmetered network. Over mobile data the only thing allowed through is a
 * session report (the song and how far into it) and the session read a press of play needs
 * so it doesn't overwrite newer progress from elsewhere. Those are metered here and stop
 * for the month once [BUDGET] is used. */
object Net {
    /** 10 MB a month, less a margin for what the meter can't see (Tailscale's own wrapping). */
    const val BUDGET = 9_000_000L
    const val LIMIT_TEXT = "10 MB"

    private lateinit var app: Context
    private val cm: ConnectivityManager by lazy { app.getSystemService(ConnectivityManager::class.java) }
    private val prefs by lazy { app.getSharedPreferences("net", Context.MODE_PRIVATE) }

    private val _free = MutableStateFlow(false)
    /** True only on Wi-Fi or Ethernet that isn't metered. Starts false, so nothing leaks before the first look. */
    val state: StateFlow<Boolean> = _free
    val free: Boolean get() = _free.value

    /** The address of the track the player is on, so a stream that's only preloading can wait for Wi-Fi
     * while the one that's playing gives up at once. Set on the main thread, read on the loaders'. */
    @Volatile var playingUri: String? = null

    /** Hub requests that must not finish over mobile data; cut the moment the phone leaves Wi-Fi. */
    private val wifiOnly: MutableSet<HttpURLConnection> = Collections.synchronizedSet(HashSet())

    private val listeners = ArrayList<(Boolean) -> Unit>()

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // The app's default network (Tailscale's VPN, which copies the metered flag of the network under it)...
        cm.registerDefaultNetworkCallback(Watch())
        // ...and the real networks, so losing the Wi-Fi itself counts straight away, before Tailscale moves over.
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), Watch())
        recompute()
    }

    private class Watch : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { recompute() }
        override fun onLost(network: Network) { recompute() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { recompute() }
    }

    fun onChange(listener: (Boolean) -> Unit) = synchronized(listeners) { listeners.add(listener) }

    /** Looks again rather than trusting the last callback. */
    fun check(): Boolean = recompute()

    @Suppress("DEPRECATION")
    @Synchronized
    private fun recompute(): Boolean {
        val now = runCatching {
            val active = cm.getNetworkCapabilities(cm.activeNetwork)
            val activeOk = active != null && active.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                !active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val wifi = cm.allNetworks.any { n ->
                val c = cm.getNetworkCapabilities(n) ?: return@any false
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            activeOk && wifi
        }.getOrDefault(false)
        val was = _free.value
        if (was && !now) {
            // Into mobile data: anything still downloading for the screen stops here and now.
            settle()
            synchronized(wifiOnly) { wifiOnly.toList() }.forEach { runCatching { it.disconnect() } }
        } else if (!was && now) {
            settle()
        }
        _free.value = now
        if (was != now) synchronized(listeners) { listeners.toList() }.forEach { it(now) }
        return now
    }

    fun holdWhileOnWifi(conn: HttpURLConnection) = wifiOnly.add(conn)
    fun release(conn: HttpURLConnection) = wifiOnly.remove(conn)

    // ---------- the monthly meter

    private fun month(): String = Calendar.getInstance().let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.MONTH) + 1}" }

    @Synchronized
    fun used(): Long = if (prefs.getString("month", null) == month()) prefs.getLong("used", 0) else 0

    fun hasBudget(): Boolean = used() < BUDGET

    @Synchronized
    private fun charge(bytes: Long) {
        if (bytes <= 0) return
        val m = month()
        val before = if (prefs.getString("month", null) == m) prefs.getLong("used", 0) else 0
        prefs.edit().putString("month", m).putLong("used", before + bytes).apply()
    }

    private fun uidBytes(): Long {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        return if (rx < 0 || tx < 0) -1 else rx + tx
    }

    private var mark = -1L   // this app's byte count when the meter last settled

    /** Bring the meter up to date. While on mobile data everything else is stopped, so every byte the
     * app moved since the last look was a report; it's counted with a quarter on top for the
     * tunnel's own headers. */
    @Synchronized
    private fun settle() {
        val now = uidBytes()
        if (!_free.value && mark >= 0 && now >= mark) charge((now - mark) * 5 / 4)
        mark = now
    }

    /** Count a request made over mobile data: what the system saw, but never less than an estimate
     * of it (for phones that don't keep per-app counts). */
    @Synchronized
    fun charged(estimate: Long) {
        val before = mark
        val now = uidBytes()
        val seen = if (before >= 0 && now >= before) (now - before) * 5 / 4 else 0
        charge(maxOf(seen, estimate))
        mark = now
    }
}

/** The player's (and the notification cover's) way to the hub: nothing is fetched over mobile data.
 * With [wait], a load holds still until Wi-Fi comes back (a preload of the next track) and only gives
 * up when its track is the one that should be playing; without, it gives up straight away. */
@OptIn(UnstableApi::class)
class WifiOnlySource(private val upstream: DataSource, private val wait: Boolean) : DataSource {
    class Factory(private val upstream: DataSource.Factory, private val wait: Boolean) : DataSource.Factory {
        override fun createDataSource(): DataSource = WifiOnlySource(upstream.createDataSource(), wait)
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri.toString()
        if (!Net.check()) {
            while (!Net.free) {
                if (!wait || uri == Net.playingUri) throw NotOnWifi()
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("cancelled while waiting for Wi-Fi")
                }
            }
        }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!Net.free) throw NotOnWifi()
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
}

/** Not being on Wi-Fi isn't a network hiccup to retry: the player hears about it at once. */
@OptIn(UnstableApi::class)
class WifiOnlyErrors : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (notOnWifi(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)

    companion object {
        fun notOnWifi(error: Throwable?): Boolean {
            var e = error
            var depth = 0
            while (e != null && depth++ < 8) {
                if (e is NotOnWifi) return true
                e = e.cause
            }
            return false
        }
    }
}
