package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.NewsFeedRepository
import com.newsfeed.widget.data.WidgetConfigStore
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class WidgetWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store     = WidgetConfigStore(context)
        val repo      = NewsFeedRepository(context)
        val readIds   = ReadStatusStore(context).readIdsFlow().first()
        val manager   = GlanceAppWidgetManager(context)
        // Both widget types share this one periodic refresh job (see NewsFeedWidgetReceiver/
        // NewsFeedFocusWidgetReceiver's onEnabled/onDisabled) — a placed Focus widget needs
        // its articles refreshed here too, not just standard ones.
        val widgetIds = manager.getGlanceIds(NewsFeedWidget::class.java) +
                        manager.getGlanceIds(NewsFeedFocusWidget::class.java)

        for (glanceId in widgetIds) {
            val appWidgetId = manager.getAppWidgetId(glanceId)
            val config      = store.configFlow(appWidgetId).first()
            // Read prior state before fetching (not just inside updateAppWidgetState below)
            // so getArticles() knows which feeds are being fetched for the very first time —
            // a feed with no accumulated articles yet gets its full available backlog instead
            // of the normal per-refresh item cap (see NewsFeedRepository.getArticles).
            val priorPrefs    = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
            val priorArticles = priorPrefs[WidgetStateKey.articles]
                ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                ?: emptyList()
            val knownFeedIds  = priorArticles.map { it.feedId }.toSet()
            val fetchResult = repo.getArticles(config, knownFeedIds)
            val fresh       = fetchResult.articles.map { a ->
                if (a.id in readIds) a.copy(isRead = true) else a
            }

            val now = System.currentTimeMillis()
            // How long an accumulated article stays in the list, independent of the 300-item
            // safety cap below — 0 means "no date limit" (300-cap-only, the original behavior).
            val retentionCutoff = if (config.retentionDays > 0)
                now - config.retentionDays * 24L * 60 * 60 * 1000
            else 0L
            var merged: List<ArticleItem> = emptyList()
            updateAppWidgetState(context, glanceId) { prefs ->
                val existing = prefs[WidgetStateKey.articles]
                    ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                    ?: emptyList()
                val freshIds = fresh.map { it.id }.toSet()
                merged = (fresh + existing.filter { it.id !in freshIds })
                    .filter { retentionCutoff <= 0L || it.publishedAt >= retentionCutoff }
                    .sortedByDescending { it.publishedAt }
                    .take(300)
                prefs[WidgetStateKey.articles]        = Json.encodeToString(merged)
                prefs[WidgetStateKey.configJson]      = Json.encodeToString(config)
                prefs[WidgetStateKey.lastRefreshTime] = now
                prefs[WidgetStateKey.lastRefreshFailed] = fetchResult.allFailed
            }
            // Download thumbnails for the merged set so accumulated articles are covered too
            repo.downloadThumbnails(merged.take(30), config.feeds)
            repo.downloadFavicons(config.feeds)
        }

        NewsFeedWidget().updateAll(context)
        NewsFeedFocusWidget().updateAll(context)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "NewsFeedRefresh"

        fun schedule(context: Context, intervalMinutes: Long = 15) {
            val request = PeriodicWorkRequestBuilder<WidgetWorker>(
                intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request,
            )
        }

        fun refreshNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<WidgetWorker>().build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
