package com.newsfeed.widget.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.newsfeed.widget.update.UpdateCheckWorker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        MainScope().launch {
            try {
                val manager     = GlanceAppWidgetManager(context)
                val standardIds = manager.getGlanceIds(NewsFeedWidget::class.java)
                val focusIds    = manager.getGlanceIds(NewsFeedFocusWidget::class.java)
                if (standardIds.isNotEmpty()) {
                    NewsFeedWidgetReceiver.scheduleClockTick(context)
                }
                if (focusIds.isNotEmpty()) {
                    NewsFeedFocusWidgetReceiver.scheduleClockTick(context)
                }
                // Previously only WidgetWorker was rescheduled here — UpdateCheckWorker
                // silently never resumed its daily check after a device reboot until a
                // widget was removed and re-added. Fixed as part of making this
                // multi-widget-aware anyway.
                if (standardIds.isNotEmpty() || focusIds.isNotEmpty()) {
                    WidgetWorker.ensureScheduled(context)
                    UpdateCheckWorker.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
