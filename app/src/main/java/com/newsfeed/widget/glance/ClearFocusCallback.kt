package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.WidgetStateKey

// Focus Mode only (BuildConfig.FOCUS_MODE build flavor). Unconditionally clears focus,
// regardless of which article is currently focused or where it ended up on screen.
//
// Tapping the focused row a second time DOES also clear focus (SetFocusArticleCallback's own
// toggle) — verified working when the tap lands precisely on the row's current bounds. The
// problem is reliably landing that second tap at all: focusing a row reflows the whole list
// (every other row shrinks), so "tap the same screen position again" often lands on a
// different, now-shrunk row instead — the row visually moved out from under the second tap.
// This button is a fixed target that's always in the same place regardless of how the list
// just reflowed, so it doesn't depend on the user re-locating a row that may have moved.
class ClearFocusCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetStateKey.focusedArticleId] = ""
        }
        NewsFeedFocusWidget().update(context, glanceId)
    }
}
