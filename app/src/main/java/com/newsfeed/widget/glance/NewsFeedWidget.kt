package com.newsfeed.widget.glance

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.newsfeed.widget.config.WidgetConfigActivity
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.update.UpdateCheckWorker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class NewsFeedWidget : GlanceAppWidget() {

    // Default SizeMode.Single pins LocalSize.current to the widget's declared minimum
    // size forever, regardless of how large the user actually places/resizes it — that
    // was silently starving the Glamour headline bitmaps of their real column width.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Glance's Row/Column child order auto-mirrors under the device's system locale —
        // same underlying LocalLayoutDirection mechanism as regular Compose, and just as
        // invisible in English-locale testing as the isRtl xor bug this project also hit.
        // On a genuinely Hebrew-locale device this flipped the ENTIRE row (accent stripe,
        // meta-row time/name order, thumbnail side, footer) regardless of each feed's own
        // explicit RTL/LTR setting, which is the one thing meant to control it — this app
        // manages direction per-feed deliberately, not by following system locale. Locking
        // layout direction to Ltr here makes every Row/Column's physical child order behave
        // identically regardless of device locale, matching what every screenshot taken
        // during this project's (English-locale) development actually showed.
        provideContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                WidgetContent(isFocusWidget = false)
            }
        }
    }
}

/**
 * The Focus widget — same rendering path as NewsFeedWidget (WidgetContent below), just with
 * isFocusWidget = true. See the merge-focus-mode design spec for why this exists as a second
 * GlanceAppWidget instead of, say, a per-widget-instance config toggle: it needs to be a
 * separate entry in the system's "Add widget" picker, which requires a separate provider.
 */
class NewsFeedFocusWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                WidgetContent(isFocusWidget = true)
            }
        }
    }
}

/**
 * Re-renders whichever widget instance owns [glanceId] — resolving NewsFeedWidget vs.
 * NewsFeedFocusWidget by checking which receiver that appWidgetId is actually registered to,
 * rather than assuming. Needed by any ActionCallback reachable from BOTH widget types'
 * composables (as opposed to one only ever wired up under isFocusWidget == true or == false):
 * calling the wrong class's update() would render that class's layout for an appWidgetId that
 * belongs to the other one.
 */
suspend fun updateNewsFeedWidget(context: Context, glanceId: GlanceId) {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider
    if (provider?.className == NewsFeedFocusWidgetReceiver::class.java.name) {
        NewsFeedFocusWidget().update(context, glanceId)
    } else {
        NewsFeedWidget().update(context, glanceId)
    }
}

@Composable
private fun WidgetContent(isFocusWidget: Boolean) {
    val prefs             = currentState<androidx.datastore.preferences.core.Preferences>()
    val configJson        = prefs[WidgetStateKey.configJson]
    val articlesJson      = prefs[WidgetStateKey.articles]
    val lastRefreshTime   = prefs[WidgetStateKey.lastRefreshTime] ?: 0L
    val lastRefreshFailed = prefs[WidgetStateKey.lastRefreshFailed] ?: false
    val expandedArticleId = prefs[WidgetStateKey.expandedArticleId] ?: ""
    val fullArticleId     = prefs[WidgetStateKey.fullArticleId]     ?: ""
    val fullArticleText   = prefs[WidgetStateKey.fullArticleText]   ?: ""
    val fullArticleShown  = prefs[WidgetStateKey.fullArticleShownChars] ?: FetchFullArticleCallback.CHUNK_CHARS
    // Focus widget only (isFocusWidget) — see FeedItemRow.kt's fontSize shadowing.
    // Reading it unconditionally here is harmless for a standard widget instance: the key is
    // simply never written to (SetFocusArticleCallback/FocusStepCallback are only ever
    // wired up when isFocusWidget is true), so it stays blank forever there.
    val focusedArticleId  = prefs[WidgetStateKey.focusedArticleId] ?: ""
    // Focus Mode only — live, on-widget-adjustable via +/- buttons on the focused row
    // itself (AdjustFocusScaleCallback), not a Settings-screen slider. Absent means
    // either nothing's been adjusted yet, or focus just moved to a different article
    // (both callbacks clear this key on any change of *which* article is focused).
    val focusScale        = prefs[WidgetStateKey.focusScale] ?: AdjustFocusScaleCallback.DEFAULT_SCALE

    val config = configJson
        ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
        ?: WidgetConfig(widgetId = -1)

    val articles: List<ArticleItem> = articlesJson
        ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
        ?: emptyList()

    val feedMap         = config.feeds.associateBy { it.feedId }
    val visibleCount    = prefs[WidgetStateKey.visibleArticleCount] ?: LoadMoreArticlesCallback.ARTICLE_CHUNK_SIZE
    val availableArticles = articles.filter { feedMap.containsKey(it.feedId) }

    // Each row can carry a Glamour-theme headline bitmap and/or a thumbnail image, and
    // RemoteViews has a real total bitmap-memory budget for one widget update (this
    // project has hit "IllegalArgumentException: RemoteViews for widget update exceeds
    // maximum bitmap memory usage" before) — so the row count is capped by an actual
    // computed budget instead of a flat guessed number, the same chunked-reveal approach
    // used for full-article "Load more" (see FeedItemRow's maxChunksAllowed). 7.5MB here
    // + the full-article path's own 6MB budget stay under the ~15.5MB ceiling this
    // project has hit before, with margin left for other home-screen widgets.
    val context2       = LocalContext.current
    val density2       = context2.resources.displayMetrics.density
    val scaledDensity2 = context2.resources.displayMetrics.scaledDensity
    val widthPx2       = ((LocalSize.current.width.value.coerceAtMost(350f) - 9f) * density2)
                              .toInt().coerceAtLeast(50)
    // Focus Mode only — one row can render at up to focusScale× (default up to 2.5×) and
    // every other row at focusBackgroundScale× (up to 1.0×, see WidgetConfigActivity.kt's
    // "Background rows size" slider), neither of which is config.fontSize on its own. This
    // budget calculation predates focus mode's per-row scale entirely and was never updated
    // when that was added — it silently assumed every row was uniformly at config.fontSize,
    // under-provisioning the moment a real focused row's bitmap grew past what had been
    // reserved for it, eroding the safety margin this ceiling exists to protect (worst
    // case, re-risking the exact "RemoteViews for widget update exceeds maximum bitmap
    // memory usage" crash it was built to avoid). Taking the largest of the two scales as a
    // uniform worst case is the same deliberately-overestimating shape as the "3-line worst
    // case" comment below, just extended to cover the scale that can now apply to any row.
    val worstCaseRowScale = if (isFocusWidget)
        maxOf(focusScale, config.focusBackgroundScale, 1f) else 1f
    val headlineLineHeightPx = 13f * config.fontSize * worstCaseRowScale * scaledDensity2 * 1.2f
    // Worst case per row: a Glamour headline bitmap at AdjustFocusScaleCallback's shared
    // HEADLINE_MAX_LINES (8) — every row uses the same cap now, focused or not, see that
    // constant's own comment for why one shared number replaced what used to be two — plus
    // a small thumbnail — themes without a bitmap headline (plain Text) cost far less, so
    // this deliberately overestimates rather than risking under-provisioning.
    val worstCaseHeadlineLines = AdjustFocusScaleCallback.HEADLINE_MAX_LINES
    // 1 byte/pixel, not 4 — TextBitmapHelper now renders headline bitmaps as ALPHA_8 (a
    // colorless coverage mask, tinted at display time via ColorFilter.tint()), not
    // ARGB_8888. This formula predates that change and was left at the old multiplier,
    // making this budget ~4x more conservative than the real bitmaps it's sizing for —
    // confirmed on-device: Focus Mode's own combined-extreme test case (Font size 3.0 ×
    // focus scale 2.5) still capped at "Showing 1 of 300" after the ALPHA_8 switch, which
    // should have relaxed given the real per-row cost just dropped ~4x.
    val headlineBytes  = if (config.widgetTheme == "glamer")
        (widthPx2 * (worstCaseHeadlineLines * headlineLineHeightPx) * 1f) else 0f
    // Unchanged: this is a real downloaded photo thumbnail (ARGB_8888), not a text bitmap —
    // the ALPHA_8 switch only applies to TextBitmapHelper's headline/paragraph rendering.
    val thumbnailBytes = 100f * 100f * 4f
    val bytesPerRow    = (headlineBytes + thumbnailBytes).coerceAtLeast(1f)
    // Rebalanced from the original 7.5MB/9MB split against FeedItemRow.kt's chunkBudgetBytes
    // (down to 6.5MB there, up to 10MB here — same ~16.5MB total, just reallocated). The
    // list itself is what's visible and memory-constrained all the time; the full-article
    // chunk budget only matters for whichever single article is currently expanded, so it
    // never needed as large a share as this one.
    val rowBudgetBytes = 10_000_000f
    // Floor was 5 (never show fewer than 5 rows) until Focus Mode's combined fontSize ×
    // focusScale × HEADLINE_MAX_LINES made a single row's real worst-case bitmap
    // exceed this entire row budget on its own (confirmed on-device at Font size 3.0 +
    // focus scale 2.5: "Can't show content", RemoteViews' real bitmap-memory ceiling hit
    // for real) — forcing a minimum of 5 such rows when even 1 already doesn't fit is
    // exactly backwards for a safety floor. 1 lets the math legitimately reflect "only a
    // little room" instead of demanding room that was already established not to exist;
    // see the textSizePx cap in TextBitmapHelper.render() for the actual backstop that
    // keeps a single row's bitmap safe regardless of what this calculation concludes.
    //
    // Upper bound was a flat 60 for every theme — but headlineBytes is already 0 for
    // every non-Glamour theme (plain Text, no bitmap headline), so the honest formula
    // above already accounts for the real cost (thumbnails only) and safely allows nearly
    // the whole 300-article store. Capping it at the same flat 60 as Glamour needlessly
    // throttled non-Glamour themes far below what's actually safe (reported: articles
    // available when not using Glamour should reach the real total, not stop at 60).
    // Glamour keeps 60 since its bitmap cost genuinely can grow large and unpredictable
    // (see AdjustFocusScaleCallback.HEADLINE_MAX_LINES / Focus Mode's combined scaling).
    val maxRowsCeiling = if (config.widgetTheme == "glamer") 60 else 300
    val maxRowsAllowed = (rowBudgetBytes / bytesPerRow).toInt().coerceIn(1, maxRowsCeiling)

    val displayArticles = availableArticles.take(visibleCount.coerceAtMost(maxRowsAllowed))
    // Based on visibleCount (what's been requested), not displayArticles.size (what's
    // actually shown after clamping) — comparing the clamped size against maxRowsAllowed
    // was always false the moment a single "chunk" request met or exceeded the memory
    // ceiling, hiding the button on the very first render whenever that happened instead
    // of only once truly exhausted. visibleCount vs the two ceilings is what actually
    // determines whether tapping "Load more" would reveal anything new.
    val canLoadMoreArticles = visibleCount < maxRowsAllowed && visibleCount < availableArticles.size
    // Complementary "stuck" state: maxRowsAllowed itself (not the requested chunk size) is
    // the binding constraint, so tapping "Load more" would never reveal anything no matter
    // how many times it's tapped — this happens whenever maxRowsAllowed <= visibleCount
    // while more articles are genuinely available (common at larger font sizes / the
    // Glamour theme's bitmap headlines, where maxRowsAllowed can land as low as its 5-row
    // floor even with hundreds of articles cached). Previously this was indistinguishable
    // from "the feed is simply exhausted" — the widget just silently stopped growing with
    // zero indication that a large backlog was sitting unreachable behind it (reported as a
    // bug: "Load more" permanently unreachable at normal/large font sizes).
    val memoryCapReached = displayArticles.size >= maxRowsAllowed &&
        displayArticles.size < availableArticles.size
    // Scoped to displayArticles, not the full accumulated store (which can hold up to
    // 300) — counting the full store made the header badge claim "99+" unread while only
    // a fraction of articles were ever reachable by scrolling, which read as a bug (and
    // was reported as one) rather than the accumulation feature it actually was.
    val unreadCount     = displayArticles.count { !it.isRead }
    // Focus widget only (isFocusWidget) — position within what's actually
    // rendered (displayArticles, not FocusStepCallback's own visibleCount-only
    // approximation of it) so the "N / M" indicator always matches what's really on
    // screen, even in the rare case the two disagree because of the memory cap.
    val focusedIndex    = if (focusedArticleId.isNotBlank())
        displayArticles.indexOfFirst { it.id == focusedArticleId } else -1

    val themeColors = WidgetThemes.colorProvidersFor(config.widgetTheme, config.themeVariant)
    val surfaceColor = WidgetThemes.surfaceColorFor(config.widgetTheme, config.themeVariant)
    val bgColor = ColorProvider(surfaceColor.copy(alpha = config.backgroundAlpha))

    GlanceTheme(colors = themeColors) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .cornerRadius(18.dp)
                .padding(0.dp),
        ) {
            WidgetHeader(unreadCount, displayArticles.size, focusedArticleId, focusedIndex, displayArticles.size, isFocusWidget)
            Divider()

            if (displayArticles.isEmpty()) {
                Box(
                    modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No articles",
                        style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.SansSerif, color = GlanceTheme.colors.onSurfaceVariant),
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                    // Explicit itemId (verified via decompiling the real glance-appwidget:1.1.0
                    // jar — LazyListScope.items' default is LazyListScope.UnspecifiedItemId, a
                    // fixed constant, not a per-item hash as might be assumed; every row was
                    // effectively unidentified/position-only before this). A reported bug — a
                    // previously-focused row's highlight not clearing after stepping focus to a
                    // different row — is consistent with RemoteViews' list-item recycling
                    // getting view identity wrong across an update when every item shares the
                    // same id. Keying on the immutable article.id gives Android's list adapter a
                    // real, stable identity to track per row, independent of which other fields
                    // (isRead, etc.) changed — a correct improvement on its own regardless of
                    // whether it's the row-highlight bug's exact root cause, which is not yet
                    // independently confirmed on-device.
                    items(displayArticles, itemId = { it.id.hashCode().toLong() }) { article ->
                        val feedConfig = feedMap[article.feedId] ?: return@items
                        val isLast = article == displayArticles.last()
                        Column(modifier = GlanceModifier.fillMaxWidth()) {
                            FeedItemRow(
                                article           = article,
                                feedConfig        = feedConfig,
                                expandedArticleId = expandedArticleId,
                                fontSize          = config.fontSize,
                                articleFontSize   = config.articleFontSize,
                                articleLength     = config.articleLength,
                                fullArticleId     = fullArticleId,
                                fullArticleText   = fullArticleText,
                                fullArticleShown  = fullArticleShown,
                                useThemeColors    = config.useThemeColors,
                                widgetTheme       = config.widgetTheme,
                                externalApp       = config.externalApp,
                                themeVariant      = config.themeVariant,
                                focusedArticleId  = focusedArticleId,
                                focusScale        = focusScale,
                                focusBackgroundScale = config.focusBackgroundScale,
                                isFocusWidget     = isFocusWidget,
                            )
                            if (!isLast) {
                                Box(modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(GlanceTheme.colors.surfaceVariant)) {}
                                Box(modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(ColorProvider(Color(0x33000000)))) {}
                            }
                        }
                    }
                    if (canLoadMoreArticles) {
                        item {
                            Box(
                                modifier = GlanceModifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Load more articles ↓",
                                    style = TextStyle(
                                        fontSize   = 12.sp,
                                        fontFamily = FontFamily.SansSerif,
                                        color      = GlanceTheme.colors.primary,
                                    ),
                                    modifier = GlanceModifier
                                        .background(GlanceTheme.colors.primaryContainer)
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                        .clickable(actionRunCallback<LoadMoreArticlesCallback>()),
                                )
                            }
                        }
                    } else if (memoryCapReached) {
                        // Not clickable — there's genuinely nothing tapping could reveal at
                        // this font size (see memoryCapReached above). Telling the user why,
                        // instead of silently stopping, is the actual fix: the number itself
                        // points them at the way out (a smaller font size raises the cap).
                        // Font size's own slider floor (WidgetConfigActivity.kt) is 0.5f —
                        // below that, "reduce font size" is not actually possible, so showing
                        // that hint there is actively misleading (confirmed on-device: still
                        // capped at 29 of 300 even at the Font size slider's minimum, "Tiny").
                        // Even at the floor there's a real, permanent ceiling here — the
                        // RemoteViews bitmap-memory limit this project has hit for real
                        // before — worth saying plainly rather than pointing at a dead end.
                        val atFontFloor = config.fontSize <= 0.5f
                        item {
                            Box(
                                modifier = GlanceModifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (atFontFloor)
                                        "Showing ${displayArticles.size} of ${availableArticles.size} · this device can't show more at once"
                                    else
                                        "Showing ${displayArticles.size} of ${availableArticles.size} · reduce font size to see more",
                                    style = TextStyle(
                                        fontSize   = 11.sp,
                                        fontFamily = FontFamily.SansSerif,
                                        color      = GlanceTheme.colors.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            WidgetFooter(lastRefreshTime, lastRefreshFailed, config.refreshIntervalMinutes, config.widgetId)
        }
    }
}

@Composable
private fun WidgetHeader(
    unreadCount: Int,
    totalCount: Int,
    focusedArticleId: String = "",
    focusedIndex: Int = -1,
    displayCount: Int = 0,
    isFocusWidget: Boolean,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "NewsFeed",
            style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.SansSerif, color = GlanceTheme.colors.onSurfaceVariant),
        )
        Spacer(GlanceModifier.defaultWeight())
        // Focus widget only (isFocusWidget — see FeedItemRow.kt's
        // fontSize shadowing). Steps focus to the previous/next article via
        // FocusStepCallback rather than requiring a precise tap on a row that may currently
        // be shrunk to half size — that's the actual point of stepping instead of tapping.
        // Always shown on this widget type (not conditioned on a focus target already being
        // set): pressing either one from the normal, nothing-focused state starts focus
        // mode at the first article, same as tapping a row directly would.
        if (isFocusWidget) {
            val stepStyle = TextStyle(
                fontSize   = 13.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                color      = GlanceTheme.colors.primary,
            )
            // Position indicator ("N / M") — only meaningful once something is focused;
            // otherwise every row is the same size and "position" doesn't mean anything.
            // Answers "where am I in the list" without counting rows by eye, and confirms
            // ▲/▼ actually moved (there was previously no feedback beyond the row sizes
            // themselves changing, which is easy to miss at a glance).
            if (focusedArticleId.isNotBlank() && focusedIndex >= 0) {
                Text(
                    text = "${focusedIndex + 1}/$displayCount",
                    style = TextStyle(
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.SansSerif,
                        color      = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    modifier = GlanceModifier.padding(horizontal = 4.dp),
                )
            }
            Text(
                text = "▲",
                style = stepStyle,
                modifier = GlanceModifier
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clickable(actionRunCallback<FocusStepCallback>(
                        actionParametersOf(FocusStepCallback.DIRECTION_KEY to "prev")
                    )),
            )
            Text(
                text = "▼",
                style = stepStyle,
                modifier = GlanceModifier
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clickable(actionRunCallback<FocusStepCallback>(
                        actionParametersOf(FocusStepCallback.DIRECTION_KEY to "next")
                    )),
            )
            // Clear-focus button — only shown once something is actually focused (nothing
            // to clear otherwise). Exists because the alternative way to clear focus —
            // tapping the already-focused row again — only works if that tap lands on the
            // row's current bounds, and focusing a row reflows the whole list (every other
            // row shrinks), so the row the user thinks they're re-tapping may no longer be
            // there. This button's position never moves, so it doesn't have that problem.
            if (focusedArticleId.isNotBlank()) {
                Text(
                    text = "✕",
                    style = stepStyle,
                    modifier = GlanceModifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clickable(actionRunCallback<ClearFocusCallback>()),
                )
                // Focus-area size, moved here from the focused row itself (was FeedItemRow's
                // problem to render before) — a fixed header position that never moves as
                // the list reflows, same reasoning as the ✕ button beside it, and keeps
                // every other on-widget control (▲▼✕) in one place instead of split between
                // the header and whichever row happens to be focused.
                Text(
                    text = "−",
                    style = stepStyle,
                    modifier = GlanceModifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clickable(actionRunCallback<AdjustFocusScaleCallback>(
                            actionParametersOf(AdjustFocusScaleCallback.DELTA_KEY to -AdjustFocusScaleCallback.STEP)
                        )),
                )
                Text(
                    text = "+",
                    style = stepStyle,
                    modifier = GlanceModifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clickable(actionRunCallback<AdjustFocusScaleCallback>(
                            actionParametersOf(AdjustFocusScaleCallback.DELTA_KEY to AdjustFocusScaleCallback.STEP)
                        )),
                )
            }
            Spacer(GlanceModifier.width(6.dp))
        }
        // Both numbers scoped to displayArticles (what's actually on screen right now),
        // not the full accumulated store (which can hold up to 300) — showing the full
        // store's total here read as a bug (reported directly): a badge claiming e.g.
        // "10(300)" when only 30 articles are actually reachable by scrolling implies 300
        // are available, not 30. The real total-vs-available accounting lives at the end
        // of the list instead (canLoadMoreArticles/memoryCapReached's "Showing X of Y"
        // message below), where "of Y" unambiguously means the full available count.
        Text(
            text = "${if (unreadCount > 99) "99+" else "$unreadCount"}($totalCount)",
            style = TextStyle(
                fontSize   = 11.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                color      = GlanceTheme.colors.onPrimaryContainer,
            ),
            modifier = GlanceModifier
                .background(GlanceTheme.colors.primaryContainer)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun WidgetFooter(lastRefreshTime: Long, lastRefreshFailed: Boolean, intervalMinutes: Int, widgetId: Int) {
    Divider()
    val now           = System.currentTimeMillis()
    val nextMs        = lastRefreshTime + TimeUnit.MINUTES.toMillis(intervalMinutes.toLong())
    val leftMs        = (nextMs - now).coerceAtLeast(0L)
    val leftMin       = TimeUnit.MILLISECONDS.toMinutes(leftMs)
    val countdownText = when {
        lastRefreshFailed     -> "⚠ refresh failed — tap to retry"
        lastRefreshTime == 0L -> "↻ now"
        leftMs < 60_000L      -> "↻ <1min"
        else                  -> "↻ in ${leftMin}min"
    }
    val countdownColor = if (lastRefreshFailed) ColorProvider(Color(0xFFE0A030)) else GlanceTheme.colors.primary

    val context = LocalContext.current
    val settingsIntent = Intent(context, WidgetConfigActivity::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = countdownText,
            style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.SansSerif, color = countdownColor),
            modifier = GlanceModifier.clickable(actionRunCallback<RefreshNowCallback>()),
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = "⚙",
            style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.primary),
            modifier = GlanceModifier
                .padding(4.dp)
                .clickable(actionStartActivity(settingsIntent)),
        )
    }
}

@Composable
private fun Divider(thin: Boolean = false) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(if (thin) 0.5.dp else 1.dp)
            .background(GlanceTheme.colors.surfaceVariant),
    ) {}
}

class NewsFeedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NewsFeedWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetWorker.schedule(context)
        UpdateCheckWorker.schedule(context)
        scheduleClockTick(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetWorker.cancel(context)
        UpdateCheckWorker.cancel(context)
        cancelClockTick(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CLOCK_TICK) {
            val pending = goAsync()
            MainScope().launch {
                try { NewsFeedWidget().updateAll(context) }
                finally { pending.finish() }
            }
            scheduleClockTick(context)
        }
    }

    companion object {
        const val ACTION_CLOCK_TICK = "com.newsfeed.widget.CLOCK_TICK"
        private const val RC_CLOCK  = 1001

        fun scheduleClockTick(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.RTC,
                System.currentTimeMillis() + 60_000L,
                clockPi(context),
            )
        }

        private fun cancelClockTick(context: Context) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(clockPi(context))
        }

        private fun clockPi(context: Context) = PendingIntent.getBroadcast(
            context, RC_CLOCK,
            Intent(ACTION_CLOCK_TICK, null, context, NewsFeedWidgetReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
