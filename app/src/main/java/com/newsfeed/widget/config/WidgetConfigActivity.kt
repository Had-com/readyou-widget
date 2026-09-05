package com.newsfeed.widget.config

import com.newsfeed.widget.BuildConfig
import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.newsfeed.widget.R
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.FilterMode
import com.newsfeed.widget.data.OpmlManager
import com.newsfeed.widget.data.NewsFeedRepository
import com.newsfeed.widget.data.SortOrder
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetConfigStore
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.glance.NewsFeedWidget
import com.newsfeed.widget.glance.NewsFeedFocusWidget
import com.newsfeed.widget.glance.updateNewsFeedWidget
import com.newsfeed.widget.glance.WidgetThemes
import com.newsfeed.widget.glance.WidgetWorker
import com.newsfeed.widget.update.UpdateManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

class WidgetConfigActivity : ComponentActivity() {

    companion object {
        fun feedAccentColors(theme: String) = paletteForTheme(theme)
    }

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent.extras
            ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED); finish(); return
        }

        val store = WidgetConfigStore(this)
        val repo  = NewsFeedRepository(this)

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var config by remember { mutableStateOf(WidgetConfig(widgetId = appWidgetId)) }
                val scope  = rememberCoroutineScope()
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* proceeds either way — the manual check still works via Toast if denied */ }

                androidx.compose.runtime.LaunchedEffect(appWidgetId) {
                    val saved = store.configFlow(appWidgetId).first()
                    if (saved.feeds.isEmpty()) {
                        val opml = runCatching {
                            assets.open("default_feeds.opml").bufferedReader().readText()
                        }.getOrNull()
                        val defaults = opml?.let { OpmlManager.parse(it) }
                            ?.mapIndexed { i, (title, url) ->
                                FeedConfig(feedId = url, displayName = title, feedUrl = url,
                                    accentColor = feedAccentColors(saved.widgetTheme)[i % feedAccentColors(saved.widgetTheme).size])
                            }
                            ?: emptyList()
                        config = saved.copy(feeds = defaults, feedOrder = defaults.map { it.feedId })
                    } else {
                        config = saved.copy(feedOrder = saved.feedOrder.ifEmpty { saved.feeds.map { it.feedId } })
                    }
                }

                val feedOrder = remember(config.feedOrder) {
                    androidx.compose.runtime.mutableStateListOf(*config.feedOrder.toTypedArray())
                }
                val lazyListState = rememberLazyListState()
                val reorderState  = rememberReorderableLazyListState(lazyListState) { from, to ->
                    // The LazyColumn has 3 non-reorderable header items before the feed rows,
                    // so subtract that offset to get feed-relative indices.
                    val offset = 3
                    val fromIdx = from.index - offset
                    val toIdx   = to.index - offset
                    if (fromIdx >= 0 && toIdx >= 0 && fromIdx < feedOrder.size && toIdx < feedOrder.size) {
                        feedOrder.add(toIdx, feedOrder.removeAt(fromIdx))
                    }
                }

                var showSortMenu     by remember { mutableStateOf(false) }
                var showFilterMenu   by remember { mutableStateOf(false) }
                var showRefreshMenu  by remember { mutableStateOf(false) }
                var showExternalMenu by remember { mutableStateOf(false) }
                var showLengthMenu   by remember { mutableStateOf(false) }
                var showThemeMenu    by remember { mutableStateOf(false) }
                var showRetentionMenu by remember { mutableStateOf(false) }

                val refreshOptions = listOf(
                    15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour",
                    120 to "2 hours", 240 to "4 hours", 360 to "6 hours", 720 to "12 hours",
                )
                val externalOptions = listOf(
                    "browser"  to "Browser",
                    "share"    to "Share sheet",
                )
                // 0 = unlimited (only the 300-article accumulation cap applies)
                val retentionOptions = listOf(
                    0 to "Forever", 1 to "1 day", 3 to "3 days", 7 to "1 week",
                    14 to "2 weeks", 30 to "1 month",
                )

                var addFeedUrl    by remember { mutableStateOf("") }
                var isAddingFeed  by remember { mutableStateOf(false) }
                var addFeedError  by remember { mutableStateOf<String?>(null) }
                var statusMessage by remember { mutableStateOf("") }

                var searchQuery    by remember { mutableStateOf("") }
                var isSearching    by remember { mutableStateOf(false) }
                var searchResults  by remember { mutableStateOf<List<com.newsfeed.widget.data.FeedSearchResult>>(emptyList()) }
                var searchError    by remember { mutableStateOf<String?>(null) }
                var searchDone     by remember { mutableStateOf(false) }

                var isCheckingForUpdate by remember { mutableStateOf(false) }

                // Edit-feed dialog state
                var editingFeed   by remember { mutableStateOf<FeedConfig?>(null) }
                var editName      by remember { mutableStateOf("") }
                var editUrl       by remember { mutableStateOf("") }
                var editUrlError  by remember { mutableStateOf<String?>(null) }
                var isEditLoading by remember { mutableStateOf(false) }

                if (editingFeed != null) {
                    AlertDialog(
                        onDismissRequest = { editingFeed = null },
                        title   = { Text("Edit feed") },
                        text    = {
                            Column {
                                OutlinedTextField(
                                    value = editName,
                                    onValueChange = { editName = it },
                                    label = { Text("Name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = editUrl,
                                    onValueChange = { editUrl = it; editUrlError = null },
                                    label = { Text("Feed URL") },
                                    singleLine = true,
                                    isError = editUrlError != null,
                                    supportingText = editUrlError?.let { e -> { Text(e) } },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !isEditLoading,
                                onClick = {
                                    val original = editingFeed ?: return@TextButton
                                    val newUrl   = editUrl.trim().let { if (it.startsWith("http")) it else "https://$it" }
                                    scope.launch {
                                        var updatedName = editName.trim()
                                        if (newUrl != original.feedUrl) {
                                            isEditLoading = true
                                            val fetched = repo.fetchFeedTitle(newUrl)
                                            if (fetched == null) {
                                                editUrlError  = "Could not load feed — check the URL"
                                                isEditLoading = false
                                                return@launch
                                            }
                                            if (updatedName.isBlank()) updatedName = fetched
                                            isEditLoading = false
                                        }
                                        if (updatedName.isBlank()) updatedName = original.displayName
                                        config = config.copy(
                                            feeds = config.feeds.map {
                                                if (it.feedId == original.feedId)
                                                    it.copy(displayName = updatedName, feedUrl = newUrl, feedId = newUrl)
                                                else it
                                            }
                                        )
                                        val idx = feedOrder.indexOf(original.feedId)
                                        if (idx >= 0) feedOrder[idx] = newUrl
                                        editingFeed = null
                                    }
                                },
                            ) {
                                if (isEditLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("Save")
                            }
                        },
                        dismissButton = { TextButton(onClick = { editingFeed = null }) { Text("Cancel") } },
                    )
                }

                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    scope.launch {
                        val xml = withContext(Dispatchers.IO) {
                            runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
                        } ?: run { statusMessage = "Could not read file"; return@launch }
                        val parsed   = OpmlManager.parse(xml)
                        val existing = config.feeds.map { it.feedId }.toSet()
                        val toAdd    = parsed
                            .filter { (_, url) -> url !in existing }
                            .map { (title, url) -> FeedConfig(feedId = url, displayName = title, feedUrl = url) }
                        if (toAdd.isNotEmpty()) {
                            config = config.copy(feeds = config.feeds + toAdd)
                            toAdd.forEach { feedOrder.add(it.feedId) }
                            statusMessage = "Added ${toAdd.size} feed(s)"
                        } else {
                            statusMessage = "No new feeds found"
                        }
                    }
                }

                fun doAddFeed() {
                    val raw = addFeedUrl.trim(); if (raw.isBlank()) return
                    val url = if (raw.startsWith("http")) raw else "https://$raw"
                    scope.launch {
                        isAddingFeed = true; addFeedError = null; statusMessage = ""
                        val title = repo.fetchFeedTitle(url)
                        if (title != null) {
                            config = config.copy(feeds = config.feeds + FeedConfig(feedId = url, displayName = title, feedUrl = url))
                            feedOrder.add(url); addFeedUrl = ""
                        } else { addFeedError = "Could not load feed — check the URL" }
                        isAddingFeed = false
                    }
                }

                fun doExport() {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            val dir = File(cacheDir, "opml").also { it.mkdirs() }
                            File(dir, "feeds.opml").also { it.writeText(OpmlManager.export(config.feeds)) }
                        }
                        val uri = FileProvider.getUriForFile(this@WidgetConfigActivity, "${packageName}.fileprovider", file)
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/xml"; putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Export OPML",
                        ))
                    }
                }

                val fontSizeLabel = when {
                    config.fontSize < 0.75f -> "Tiny"
                    config.fontSize < 1.0f  -> "Small"
                    config.fontSize < 1.5f  -> "Medium"
                    config.fontSize < 2.0f  -> "Large"
                    else                    -> "Huge"
                }
                val articleFontSizeLabel = when {
                    config.articleFontSize < 0.75f -> "Tiny"
                    config.articleFontSize < 1.0f  -> "Small"
                    config.articleFontSize < 1.5f  -> "Medium"
                    config.articleFontSize < 2.0f  -> "Large"
                    else                            -> "Huge"
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Widget settings") },
                            actions = {
                                TextButton(onClick = {
                                    val final = config.copy(feedOrder = feedOrder.toList())
                                    lifecycleScope.launch {
                                        store.save(final)
                                        WidgetWorker.schedule(this@WidgetConfigActivity, final.refreshIntervalMinutes.toLong())
                                        runCatching {
                                            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                                            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                                                prefs[WidgetStateKey.configJson] = Json.encodeToString(final)
                                            }
                                            // updateNewsFeedWidget() initialises the Glance DataStore
                                            // subscription for whichever widget type this instance
                                            // actually is; updateAll() on both classes ensures every
                                            // placed widget of either type re-renders (matches the
                                            // existing "refresh everything, not just this one"
                                            // behavior from before two widget types existed).
                                            updateNewsFeedWidget(this@WidgetConfigActivity, glanceId)
                                            NewsFeedWidget().updateAll(this@WidgetConfigActivity)
                                            NewsFeedFocusWidget().updateAll(this@WidgetConfigActivity)
                                        }
                                        WidgetWorker.refreshNow(this@WidgetConfigActivity)
                                        setResult(RESULT_OK, Intent().apply { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) })
                                        finish()
                                    }
                                }) { Text("Save") }
                            },
                        )
                    },
                ) { paddingValues ->
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize().padding(paddingValues),
                    ) {
                        // ── Sort, Filter, Refresh, External App, Font Size ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("SORT & FILTER", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Spacer(Modifier.height(8.dp))

                                // Sort
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Sort by", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        TextButton(onClick = { showSortMenu = true }) {
                                            Text("${SortOrder.entries.first { it.key == config.sortOrder }.labelRes} ▾", fontSize = 13.sp)
                                        }
                                        DropdownMenu(showSortMenu, { showSortMenu = false }) {
                                            SortOrder.entries.forEach { o ->
                                                DropdownMenuItem(text = { Text(o.labelRes) },
                                                    onClick = { config = config.copy(sortOrder = o.key); showSortMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Filter
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Show", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        TextButton(onClick = { showFilterMenu = true }) {
                                            Text("${FilterMode.entries.first { it.key == config.filter }.labelRes} ▾", fontSize = 13.sp)
                                        }
                                        DropdownMenu(showFilterMenu, { showFilterMenu = false }) {
                                            FilterMode.entries.forEach { m ->
                                                DropdownMenuItem(text = { Text(m.labelRes) },
                                                    onClick = { config = config.copy(filter = m.key); showFilterMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Refresh interval
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Refresh every", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val label = refreshOptions.firstOrNull { it.first == config.refreshIntervalMinutes }?.second
                                            ?: "${config.refreshIntervalMinutes} min"
                                        TextButton(onClick = { showRefreshMenu = true }) { Text("$label ▾", fontSize = 13.sp) }
                                        DropdownMenu(showRefreshMenu, { showRefreshMenu = false }) {
                                            refreshOptions.forEach { (minutes, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(refreshIntervalMinutes = minutes); showRefreshMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Keep articles for (retention — independent of the 300-article
                                // accumulation cap, which always applies regardless of this setting)
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Keep articles for", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val label = retentionOptions.firstOrNull { it.first == config.retentionDays }?.second
                                            ?: "${config.retentionDays} days"
                                        TextButton(onClick = { showRetentionMenu = true }) { Text("$label ▾", fontSize = 13.sp) }
                                        DropdownMenu(showRetentionMenu, { showRetentionMenu = false }) {
                                            retentionOptions.forEach { (days, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(retentionDays = days); showRetentionMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Open article in
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Open article in", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val label = externalOptions.firstOrNull { it.first == config.externalApp }?.second ?: "Browser"
                                        TextButton(onClick = { showExternalMenu = true }) { Text("$label ▾", fontSize = 13.sp) }
                                        DropdownMenu(showExternalMenu, { showExternalMenu = false }) {
                                            externalOptions.forEach { (key, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(externalApp = key); showExternalMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Font size slider
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Font size", style = MaterialTheme.typography.bodyMedium)
                                    Text(fontSizeLabel, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Slider(
                                    value = config.fontSize,
                                    onValueChange = { config = config.copy(fontSize = it) },
                                    valueRange = 0.5f..3.0f,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                // Article (expanded body text) font size — independent of the
                                // headline/meta size above.
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Article font size", style = MaterialTheme.typography.bodyMedium)
                                    Text(articleFontSizeLabel, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Slider(
                                    value = config.articleFontSize,
                                    onValueChange = { config = config.copy(articleFontSize = it) },
                                    valueRange = 0.5f..3.0f,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                // Focus Mode only. How small every row other than the focused
                                // one renders (as a fraction of Font size above) — a standing
                                // preference, so it lives here as a slider, unlike the focused
                                // row's own size, which is a live, on-widget +/- adjustment
                                // (AdjustFocusScaleCallback) because that's meant to be tuned
                                // per article in the moment, not set once in advance.
                                if (BuildConfig.FOCUS_MODE) {
                                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                        Text("Background rows size", style = MaterialTheme.typography.bodyMedium)
                                        Text("${(config.focusBackgroundScale * 100).toInt()}%", fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Slider(
                                        value = config.focusBackgroundScale,
                                        onValueChange = { config = config.copy(focusBackgroundScale = it) },
                                        valueRange = 0.25f..1.0f,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                // Live preview
                                val sampleDesc = "פרטי הכתבה לדוגמה מופיעים כאן לאחר הפתיחה — When you tap a headline, this is the description text that appears below it. The length setting controls how much of this text is shown."
                                val previewDesc = when (config.articleLength) {
                                    "short" -> sampleDesc.take(100).trimEnd() + "…"
                                    "full"  -> sampleDesc
                                    else    -> sampleDesc.take(400).trimEnd()
                                }
                                val previewScheme = WidgetThemes.rawColorSchemeFor(config.widgetTheme, config.themeVariant)
                                // Glamour gets its actual bitmap-rendered font (Playpen Sans Hebrew) here
                                // too — this Activity isn't RemoteViews-constrained like the real widget,
                                // so the real Font resources can be used directly instead of a
                                // FontFamily.Cursive placeholder. Both weights are registered (unlike
                                // Dana Yad, which only had one) so Compose's own FontWeight.Bold request
                                // below picks the font's real bold file instead of synthesizing one.
                                val previewFont = when (WidgetThemes.fontFamilyFor(config.widgetTheme)) {
                                    "serif"   -> FontFamily.Serif
                                    "mono"    -> FontFamily.Monospace
                                    "cursive" -> FontFamily(
                                        Font(R.font.playpen_sans_hebrew, FontWeight.Normal),
                                        Font(R.font.playpen_sans_hebrew_bold, FontWeight.Bold),
                                    )
                                    else      -> FontFamily.SansSerif
                                }
                                // Headline always uses onSurface — the theme's primary/darkest-ink
                                // token — matching FeedItemRow's real rendering for every theme
                                // (this used to special-case Glamour onto onSurfaceVariant, the same
                                // bug that was fixed in the real widget's headline color).
                                val headlineColor = previewScheme.onSurface
                                // Fixed reference width instead of the settings screen's full width:
                                // this is the widget's own verified default size (uiautomator-measured
                                // 303dp elsewhere this session), so text wraps here exactly the way it
                                // wraps on an actually-placed widget, not artificially wider.
                                // Capped: this scales with fontSize like the headline text does,
                                // but the preview card itself is a fixed 303dp — left unclamped,
                                // the thumbnail box and the headline text both grow together at
                                // large fontSize values and the thumbnail wins the space race,
                                // squeezing the headline's column so narrow that a single English
                                // word (e.g. "headline") no longer fits and gets force-broken
                                // mid-word ("headlin"/"e") instead of wrapping at a word boundary.
                                // Capping the thumbnail leaves the headline column enough width to
                                // wrap normally even at the slider's max (3.0x).
                                val previewThumbDp = (52f * config.fontSize).dp.coerceAtMost(64.dp)
                                // Locks this card's physical child order to LTR regardless of
                                // the device's system locale. Compose auto-mirrors Row/Column
                                // child order under an RTL LocalLayoutDirection (unlike Glance,
                                // which never does) — without this, the meta row's "14:30"
                                // element (meant to always sit physically left, matching
                                // FeedItemRow.kt's real widget) would flip to the right on a
                                // Hebrew-locale device even though nothing about the feed's own
                                // RTL/LTR setting changed. Per-element textAlign=End below still
                                // right-aligns the RTL-styled text within its own box either way.
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Column(
                                    modifier = Modifier
                                        .width(303.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(previewScheme.surface)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    // Time stays physically left, feed name grouped to the
                                    // physical right — matches FeedItemRow.kt's real isRtl
                                    // meta-row order, not achievable with one combined string.
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "14:30",
                                            fontSize   = (9f * config.fontSize).sp,
                                            fontFamily = previewFont,
                                            color      = previewScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "ynet מבזקים",
                                            fontSize   = (9f * config.fontSize).sp,
                                            fontFamily = previewFont,
                                            color      = previewScheme.primary,
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.Top) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "כותרת כתבה לדוגמה — Sample article headline",
                                                fontSize   = (13f * config.fontSize).sp,
                                                fontFamily = previewFont,
                                                fontWeight = FontWeight.Bold,
                                                lineHeight = (17f * config.fontSize).sp,
                                                color      = headlineColor,
                                                textAlign  = TextAlign.End,
                                                modifier   = Modifier.fillMaxWidth(),
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        // Stand-in for a real per-article thumbnail — content doesn't
                                        // matter here, only that it occupies the same width so the
                                        // headline next to it wraps at the same width it really would.
                                        androidx.compose.foundation.layout.Box(
                                            modifier = Modifier
                                                .width(previewThumbDp)
                                                .height(previewThumbDp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(previewScheme.surfaceVariant),
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        previewDesc,
                                        // Same color as the headline (headlineColor) and same
                                        // font family (previewFont, already shared) — only
                                        // size differs, scaled by the independent article
                                        // font-size setting rather than the headline's.
                                        fontSize   = (10f * config.articleFontSize).sp,
                                        fontFamily = previewFont,
                                        color      = headlineColor,
                                        lineHeight = (14f * config.articleFontSize).sp,
                                        textAlign  = TextAlign.End,
                                        modifier   = Modifier.fillMaxWidth(),
                                    )
                                }
                                }
                                Spacer(Modifier.height(8.dp))

                                // Article length (controls how much description is shown when expanded)
                                val lengthOptions = listOf(
                                    "short"  to "Subtitle only",
                                    "medium" to "First paragraph",
                                    "full"   to "Full article",
                                )
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Expanded article", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val lengthLabel = lengthOptions.firstOrNull { it.first == config.articleLength }?.second ?: "First paragraph"
                                        TextButton(onClick = { showLengthMenu = true }) { Text("$lengthLabel ▾", fontSize = 13.sp) }
                                        DropdownMenu(showLengthMenu, { showLengthMenu = false }) {
                                            lengthOptions.forEach { (key, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(articleLength = key); showLengthMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Widget theme
                                val themeOptions = listOf(
                                    "auto"       to "Auto (system)",
                                    "lavender"   to "Lavender",
                                    "amethyst"   to "Amethyst",
                                    "glassy"     to "Glassy",
                                    "simple"     to "Simple",
                                    "aerospace"  to "Aerospace",
                                    "silicon"    to "Data Science",
                                    "glamer"     to "Glamour",
                                )
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Widget theme", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val themeLabel = themeOptions.firstOrNull { it.first == config.widgetTheme }?.second ?: "Auto (system)"
                                        TextButton(onClick = { showThemeMenu = true }) { Text("$themeLabel ▾", fontSize = 13.sp) }
                                        DropdownMenu(showThemeMenu, { showThemeMenu = false }) {
                                            themeOptions.forEach { (key, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(widgetTheme = key); showThemeMenu = false })
                                            }
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Theme variant", style = MaterialTheme.typography.bodyMedium)
                                    Row {
                                        listOf("light" to "Light", "dark" to "Dark").forEach { (key, lbl) ->
                                            val selected = config.themeVariant == key
                                            TextButton(
                                                onClick = { config = config.copy(themeVariant = key) },
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                                        else androidx.compose.ui.graphics.Color.Transparent
                                                    ),
                                            ) {
                                                Text(lbl, fontSize = 13.sp,
                                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                            else MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Column {
                                        Text("Use theme accent colors", style = MaterialTheme.typography.bodyMedium)
                                        Text("Hides per-feed colors; uses the theme palette",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = config.useThemeColors,
                                        onCheckedChange = { config = config.copy(useThemeColors = it) },
                                    )
                                }
                                Column(Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                        Text("Background opacity", style = MaterialTheme.typography.bodyMedium)
                                        Text("${(config.backgroundAlpha * 100).toInt()}%",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                    Slider(
                                        value = config.backgroundAlpha,
                                        onValueChange = { config = config.copy(backgroundAlpha = it) },
                                        valueRange = 0f..1f,
                                        steps = 19,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }

                        // ── Add Feed ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("ADD FEED", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = addFeedUrl,
                                        onValueChange = { addFeedUrl = it; addFeedError = null },
                                        label = { Text("RSS or Atom feed URL") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        isError = addFeedError != null,
                                        supportingText = addFeedError?.let { e -> { Text(e, color = MaterialTheme.colorScheme.error) } },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { doAddFeed() }),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    if (isAddingFeed) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    else TextButton(onClick = { doAddFeed() }, enabled = addFeedUrl.isNotBlank()) { Text("Add") }
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import OPML") }
                                    TextButton(onClick = { doExport() }, enabled = config.feeds.isNotEmpty()) { Text("Export OPML") }
                                }
                                if (statusMessage.isNotEmpty()) {
                                    Text(statusMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            HorizontalDivider()
                        }

                        // ── Find feeds by search ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("FIND FEEDS", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it; searchDone = false; searchError = null },
                                        label = { Text("Topic, site name, keyword…") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = {
                                            if (searchQuery.isNotBlank() && !isSearching) {
                                                scope.launch {
                                                    isSearching = true; searchError = null; searchDone = false
                                                    val results = repo.searchFeeds(searchQuery)
                                                    searchResults = results
                                                    searchError   = if (results.isEmpty()) "No feeds found" else null
                                                    isSearching   = false; searchDone = true
                                                }
                                            }
                                        }),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    if (isSearching) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    else TextButton(
                                        onClick = {
                                            if (searchQuery.isNotBlank()) scope.launch {
                                                isSearching = true; searchError = null; searchDone = false
                                                val results = repo.searchFeeds(searchQuery)
                                                searchResults = results
                                                searchError   = if (results.isEmpty()) "No feeds found" else null
                                                isSearching   = false; searchDone = true
                                            }
                                        },
                                        enabled = searchQuery.isNotBlank(),
                                    ) { Text("Search") }
                                }
                                if (searchError != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(searchError!!, fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (searchDone && searchResults.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                        searchResults.forEach { result ->
                                            val alreadyAdded = config.feeds.any { it.feedId == result.feedUrl }
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(result.title,
                                                        style = MaterialTheme.typography.bodyMedium)
                                                    if (result.description.isNotBlank()) {
                                                        Text(result.description.take(80),
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    if (result.subscribers > 0) {
                                                        Text("${result.subscribers} subscribers",
                                                            fontSize = 10.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                                    }
                                                }
                                                TextButton(
                                                    onClick = {
                                                        if (!alreadyAdded) {
                                                            val newFeed = FeedConfig(
                                                                feedId      = result.feedUrl,
                                                                displayName = result.title,
                                                                feedUrl     = result.feedUrl,
                                                                accentColor = feedAccentColors(config.widgetTheme)[config.feeds.size % feedAccentColors(config.widgetTheme).size],
                                                            )
                                                            config = config.copy(feeds = config.feeds + newFeed)
                                                            feedOrder.add(result.feedUrl)
                                                        }
                                                    },
                                                    enabled = !alreadyAdded,
                                                ) { Text(if (alreadyAdded) "Added" else "+ Add") }
                                            }
                                            HorizontalDivider(thickness = 0.5.dp)
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }

                        // ── Feed order header ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("FEED ORDER & STYLE", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Text("Tap name to edit  ·  Drag to reorder  ·  × to remove", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }

                        // ── Per-feed rows ──
                        items(count = feedOrder.size, key = { feedOrder[it] }) { index ->
                            val feedId     = feedOrder[index]
                            val feedConfig = config.feeds.firstOrNull { it.feedId == feedId } ?: return@items

                            ReorderableItem(reorderState, key = feedId) {
                                Column {
                                    FeedConfigRow(
                                        feedConfig    = feedConfig,
                                        useThemeColors = config.useThemeColors,
                                        theme         = config.widgetTheme,
                                        onUpdate      = { updated ->
                                            config = config.copy(feeds = config.feeds.map { if (it.feedId == updated.feedId) updated else it })
                                        },
                                        onRemove      = {
                                            feedOrder.remove(feedId)
                                            config = config.copy(feeds = config.feeds.filter { it.feedId != feedId })
                                        },
                                        onEditRequest = {
                                            editName     = feedConfig.displayName
                                            editUrl      = feedConfig.feedUrl
                                            editUrlError = null
                                            editingFeed  = feedConfig
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }

                        // ── App update ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("APP UPDATE", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Check for updates", style = MaterialTheme.typography.bodyMedium)
                                        Text("Currently on build ${BuildConfig.VERSION_CODE}", fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isCheckingForUpdate) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    else TextButton(onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                this@WidgetConfigActivity, Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        isCheckingForUpdate = true
                                        scope.launch {
                                            UpdateManager.checkAndUpdate(this@WidgetConfigActivity, notifyOnly = false)
                                            isCheckingForUpdate = false
                                        }
                                    }) { Text("Check now") }
                                }
                                Text(
                                    "The first time you install an update you may see an “install unknown apps” " +
                                        "or Google Play Protect prompt — that's expected for an app outside the Play Store.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
