package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.WidgetStateKey

/**
 * Reveals the next chunk of the accumulated article list — same chunked-reveal pattern as
 * LoadMoreArticleCallback, but for the list itself rather than one article's body. The
 * precise, memory-safe ceiling (maxRowsAllowed, computed from real width/density/theme) is
 * enforced where it's rendered in NewsFeedWidget; this callback just advances the raw
 * revealed count, coerced to the total available articles as a coarse bound.
 */
class LoadMoreArticlesCallback : ActionCallback {
    companion object {
        // Smaller than the typical computed maxRowsAllowed (see NewsFeedWidget) so a first
        // reveal doesn't already land on the memory ceiling in one jump, leaving "Load more"
        // an actual chunked climb toward it instead of a single all-or-nothing step.
        const val ARTICLE_CHUNK_SIZE = 10
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.visibleArticleCount] ?: ARTICLE_CHUNK_SIZE
            prefs[WidgetStateKey.visibleArticleCount] = current + ARTICLE_CHUNK_SIZE
        }
        updateNewsFeedWidget(context, glanceId)
    }
}
