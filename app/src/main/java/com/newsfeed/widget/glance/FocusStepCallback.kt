package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Focus Mode only (BuildConfig.FOCUS_MODE build flavor). The header's ▲/▼ buttons are wired
// to this so the user can move focus to the previous/next article without needing a precise
// tap on a row that's now shrunk to a fraction of its normal size — the whole point of
// stepping instead of tapping every time.
class FocusStepCallback : ActionCallback {
    companion object {
        // "prev" | "next"
        val DIRECTION_KEY = ActionParameters.Key<String>("direction")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val direction = parameters[DIRECTION_KEY] ?: return
        var newFocusId: String? = null
        var wasUnread = false
        updateAppWidgetState(context, glanceId) { prefs ->
            val config = prefs[WidgetStateKey.configJson]
                ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
                ?: return@updateAppWidgetState
            val articles = prefs[WidgetStateKey.articles]
                ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                ?: return@updateAppWidgetState
            val feedIds = config.feeds.map { it.feedId }.toSet()
            val visibleCount = prefs[WidgetStateKey.visibleArticleCount]
                ?: LoadMoreArticlesCallback.ARTICLE_CHUNK_SIZE
            // Approximates NewsFeedWidget's displayArticles by visibleCount alone, not the
            // exact memory-derived maxRowsAllowed — that depends on Compose-only LocalSize/
            // density context this callback (running outside composition) doesn't have.
            // articles is already newest-first (WidgetWorker sorts before persisting), so no
            // re-sort is needed here. Good enough for a test build to step through what's
            // realistically on screen; folding this into the real app would need the actual
            // render-time list threaded through instead of re-approximated here.
            val ordered = articles.filter { it.feedId in feedIds }.take(visibleCount)
            if (ordered.isEmpty()) return@updateAppWidgetState
            val currentId = prefs[WidgetStateKey.focusedArticleId] ?: ""
            val currentIndex = ordered.indexOfFirst { it.id == currentId }
            val nextIndex = when {
                currentIndex < 0    -> 0
                direction == "next" -> (currentIndex + 1).coerceAtMost(ordered.size - 1)
                else                -> (currentIndex - 1).coerceAtLeast(0)
            }
            val target = ordered[nextIndex]
            prefs[WidgetStateKey.focusedArticleId] = target.id
            newFocusId = target.id
            // Same reasoning as SetFocusArticleCallback: a +/- adjustment made on the
            // previously-focused article isn't a choice about this one.
            if (target.id != currentId) prefs.remove(WidgetStateKey.focusScale)

            // Stepping onto an article counts as having seen it, same as tapping it directly
            // (SetFocusArticleCallback) does.
            wasUnread = !target.isRead
            if (wasUnread) {
                prefs[WidgetStateKey.articles] = Json.encodeToString(
                    articles.map { if (it.id == target.id) it.copy(isRead = true) else it }
                )
            }
        }
        if (wasUnread) newFocusId?.let { ReadStatusStore(context).markRead(it) }
        NewsFeedFocusWidget().update(context, glanceId)
    }
}
