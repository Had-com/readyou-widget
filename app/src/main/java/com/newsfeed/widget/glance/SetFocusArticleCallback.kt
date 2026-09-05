package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Focus Mode only (BuildConfig.FOCUS_MODE build flavor — see FeedItemRow.kt's fontSize
// shadowing and FocusStepCallback). Tapping a row sets it as the focused article, shrinking
// every other displayed row; tapping the already-focused row again clears focus, returning
// all rows to the normal configured size — mirrors ToggleExpandCallback's toggle behavior in
// the standard flavor. That toggle-off tap only works when it lands on the focused row's
// current (post-reflow) bounds, which the ClearFocusCallback header button exists to make
// unnecessary — see its own comment for why.
class SetFocusArticleCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY = ActionParameters.Key<String>("articleId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        var didFocus = false
        var wasUnread = false
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.focusedArticleId] ?: ""
            didFocus = current != articleId
            prefs[WidgetStateKey.focusedArticleId] = if (didFocus) articleId else ""
            // Each newly-focused article starts at AdjustFocusScaleCallback's default size —
            // an earlier +/- adjustment made while looking at a different article isn't a
            // choice about this one, so it shouldn't carry over silently.
            if (didFocus) prefs.remove(WidgetStateKey.focusScale)

            // Focusing an article counts as having seen it — same reasoning as
            // ToggleExpandCallback marking read on expand in the standard flavor.
            if (didFocus) {
                val articles = prefs[WidgetStateKey.articles]
                    ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                if (articles != null) {
                    wasUnread = articles.any { it.id == articleId && !it.isRead }
                    if (wasUnread) {
                        prefs[WidgetStateKey.articles] = Json.encodeToString(
                            articles.map { if (it.id == articleId) it.copy(isRead = true) else it }
                        )
                    }
                }
            }
        }
        if (wasUnread) ReadStatusStore(context).markRead(articleId)
        NewsFeedFocusWidget().update(context, glanceId)
    }
}
