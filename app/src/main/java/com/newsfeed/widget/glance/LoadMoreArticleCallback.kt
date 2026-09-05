package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.WidgetStateKey

/**
 * Reveals the next chunk of the currently-loaded full article (Glamour's chunked, bitmap-
 * bounded pagination — see FetchFullArticleCallback.CHUNK_CHARS/MAX_CHUNKS for why this is
 * chunked instead of rendering the whole fetched text as one unbounded bitmap).
 */
class LoadMoreArticleCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY = ActionParameters.Key<String>("loadMoreArticleId")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val articleId = parameters[ARTICLE_ID_KEY] ?: return

        updateAppWidgetState(context, glanceId) { prefs ->
            // Ignore a stale tap from a previously-expanded article that's no longer the
            // one whose full text is loaded.
            if (prefs[WidgetStateKey.fullArticleId] != articleId) return@updateAppWidgetState
            val textLen = prefs[WidgetStateKey.fullArticleText]?.length ?: 0
            val current = prefs[WidgetStateKey.fullArticleShownChars] ?: FetchFullArticleCallback.CHUNK_CHARS
            val cap     = FetchFullArticleCallback.CHUNK_CHARS * FetchFullArticleCallback.MAX_CHUNKS
            prefs[WidgetStateKey.fullArticleShownChars] =
                (current + FetchFullArticleCallback.CHUNK_CHARS).coerceAtMost(textLen).coerceAtMost(cap)
        }
        updateNewsFeedWidget(context, glanceId)
    }
}
