package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.WidgetStateKey

// Focus Mode only (BuildConfig.FOCUS_MODE build flavor). Wired to "−"/"+" buttons in the
// widget header (NewsFeedWidget.kt's WidgetHeader) — a real pinch-to-zoom gesture is not
// possible here: RemoteViews widgets receive no raw touch events at all, multi-touch included,
// only discrete clicks on pre-declared regions (the same platform wall the drag-magnifier idea
// hit). Stepped +/- buttons are the closest on-widget equivalent that's actually buildable.
class AdjustFocusScaleCallback : ActionCallback {
    companion object {
        val DELTA_KEY = ActionParameters.Key<Float>("delta")
        const val DEFAULT_SCALE = 1.25f
        const val MIN_SCALE = 0.75f
        const val MAX_SCALE = 2.5f
        const val STEP = 0.15f
        // How many lines ANY headline bitmap is allowed to grow to — focused row or not
        // (FeedItemRow.kt passes this to TextBitmapHelper.headline's maxLines unconditionally).
        // Used to be two separate numbers (8 for the focused row, a smaller cap for every other
        // row), which caused two distinct bugs from the same root mistake — a hardcoded number
        // in one place silently drifting from the real cap used elsewhere:
        //   1. The memory budget's "worst case" line count stayed hardcoded at the old
        //      non-focused default (3) after the focused row's real cap was raised to 8, so a
        //      high combined fontSize × focusScale grew a real bitmap the budget had never
        //      reserved room for, hitting RemoteViews' actual bitmap-memory ceiling for real
        //      (confirmed on-device: "Can't show content").
        //   2. A thumbnail narrows a headline's available width, so the same headline needs more
        //      lines to show in full next to a thumbnail than at full row width — the old
        //      non-focused cap (raised from 3 to 5 to help, still not enough) kept truncating
        //      real headlines with "…" that had room to just show another line.
        // One shared constant everywhere removes the drift risk entirely rather than trying to
        // keep two numbers in sync by hand. 8 was already proven memory-safe under the most
        // demanding real case tested (Focus Mode's own combined-extreme fontSize × focusScale) —
        // reusing it for ordinary rows, which never carry that multiplier, is strictly cheaper.
        const val HEADLINE_MAX_LINES = 8
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[DELTA_KEY] ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.focusScale] ?: DEFAULT_SCALE
            prefs[WidgetStateKey.focusScale] = (current + delta).coerceIn(MIN_SCALE, MAX_SCALE)
        }
        NewsFeedFocusWidget().update(context, glanceId)
    }
}
