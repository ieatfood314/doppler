package ca.heeney.doppler

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** The 2x1 and 1x2 widgets: a 1 and a 2. The one that is playing glows its colour. */
object Widgets {
    const val RED = 0xFFFF5A3C.toInt()
    const val BLUE = 0xFF5B8CFF.toInt()
    const val IDLE = 0xFFB9B4D6.toInt()
    private var shown: String? = "unset"

    fun update(context: Context, current: String?, playing: Boolean, slots: Map<String, String>) {
        val lit = if (playing) slots.entries.firstOrNull { it.value == current }?.key ?: "" else ""
        if (lit == shown) return
        shown = lit
        push(context, lit)
    }

    fun push(context: Context, lit: String) {
        val manager = AppWidgetManager.getInstance(context)
        for ((provider, layout) in listOf(WideWidget::class.java to R.layout.widget_wide, TallWidget::class.java to R.layout.widget_tall)) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, layout, lit))
        }
    }

    fun views(context: Context, layout: Int, lit: String): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        for ((slot, button, glyph, color) in listOf(
            Quad("1", R.id.slot1, R.id.glyph1, RED), Quad("2", R.id.slot2, R.id.glyph2, BLUE))) {
            val intent = Intent(context, ConfirmActivity::class.java).setAction(ConfirmActivity.SLOT).putExtra("slot", slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            views.setOnClickPendingIntent(button, PendingIntent.getActivity(context, slot.toInt(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            views.setInt(glyph, "setColorFilter", if (lit == slot) color else IDLE)
            views.setViewVisibility(if (slot == "1") R.id.ring1 else R.id.ring2,
                if (lit == slot) android.view.View.VISIBLE else android.view.View.INVISIBLE)
        }
        return views
    }

    private data class Quad(val slot: String, val button: Int, val glyph: Int, val color: Int)
}

abstract class SlotWidget(private val layout: Int) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = Engine.get(context).state.value
        val lit = if (state.playing) state.slots.entries.firstOrNull { it.value == state.playlist }?.key ?: "" else ""
        manager.updateAppWidget(ids, Widgets.views(context, layout, lit))
    }
}

class WideWidget : SlotWidget(R.layout.widget_wide)
class TallWidget : SlotWidget(R.layout.widget_tall)
