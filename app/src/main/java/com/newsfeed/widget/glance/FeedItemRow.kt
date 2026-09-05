package com.newsfeed.widget.glance

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.LocalSize
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FaviconHelper
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.ThumbnailHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun FeedItemRow(
    article: ArticleItem,
    feedConfig: FeedConfig,
    expandedArticleId: String,
    fontSize: Float,
    articleFontSize: Float = 1.0f,
    articleLength: String = "medium",
    fullArticleId: String = "",
    fullArticleText: String = "",
    fullArticleShown: Int = FetchFullArticleCallback.CHUNK_CHARS,
    useThemeColors: Boolean = false,
    widgetTheme: String = "auto",
    themeVariant: String = "light",
    externalApp: String = "browser",
    focusedArticleId: String = "",
    focusScale: Float = AdjustFocusScaleCallback.DEFAULT_SCALE,
    focusBackgroundScale: Float = 0.5f,
    isFocusWidget: Boolean = false,
) {
    val context        = LocalContext.current
    // Focus Mode only. Hoisted above isExpanded: Focus Mode has no separate expand/collapse
    // gesture (a tap always sets/clears focus, see toggleAction below) so the focused row —
    // already the sole thing enlarged and the visual center of attention — auto-expands into
    // its description/full-article controls instead of requiring a second, different action
    // the user has no way to trigger. Reported and confirmed: without this, Focus Mode had no
    // way at all to read an article's paragraph or full text, only to resize its headline.
    val isFocused      = isFocusWidget && article.id == focusedArticleId
    val isExpanded     = article.id == expandedArticleId || isFocused
    // Focus widget only (isFocusWidget — see NewsFeedFocusWidget vs. NewsFeedWidget).
    // Shadows the fontSize parameter so every size derived from it below (headlineSize,
    // articleSize, thumbWidth, metaFontSize, ...) picks up the adjustment automatically, with
    // no further changes needed through the rest of this function. Inactive (focusedArticleId
    // blank, or a standard widget instance where isFocusWidget is false) is a
    // complete no-op — every row renders at the widget's normal configured font size, exactly
    // as before this feature existed. focusScale (the focused row's own multiplier) is live,
    // on-widget adjustable via the +/- buttons below; focusBackgroundScale (every other row)
    // is a Settings-screen slider (WidgetConfigActivity.kt) — deliberately different controls
    // for different reasons: focus size is a per-article, in-the-moment adjustment, background
    // size is a standing preference.
    // Captured before the shadow below reassigns fontSize — needed so metaScaleFontSize (right
    // after) can still see the pre-focus-scale value.
    val baseFontSize = fontSize
    @Suppress("NAME_SHADOWING")
    val fontSize = if (isFocusWidget && focusedArticleId.isNotBlank()) {
        if (article.id == focusedArticleId) fontSize * focusScale else fontSize * focusBackgroundScale
    } else fontSize
    // The meta row (timestamp/feed name/favicon circle) uses this instead of fontSize directly.
    // fontSize can grow up to 2.5x on the focused row (focusScale), but the row's physical
    // width does not grow with it — the widget's own width is fixed. Reported and confirmed:
    // at high focus scale the meta row's content (already sized off fontSize before this fix)
    // needed more horizontal space than the row had, spilling the feed name/icon off the left
    // edge instead of wrapping or clipping cleanly. Coercing to baseFontSize caps the meta row
    // at its normal size regardless of focus scale — it's supporting metadata, not the primary
    // content being zoomed into, so it has no reason to grow past normal. Background rows'
    // *shrinking* (focusBackgroundScale, e.g. 0.5x) is unaffected by this — coerceAtMost only
    // clamps the upper end, so those rows still shrink their meta row along with everything
    // else, exactly as before.
    val metaScaleFontSize = fontSize.coerceAtMost(baseFontSize)
    val accentProvider = if (useThemeColors) {
        GlanceTheme.colors.primary
    } else {
        val parsed = runCatching { android.graphics.Color.parseColor(feedConfig.accentColor) }
            .getOrDefault(android.graphics.Color.parseColor("#9B72E3"))
        ColorProvider(Color(parsed))
    }
    // Each feed's direction is an explicit, absolute per-feed setting (the config screen's
    // RTL/LTR toggle) — it must NOT depend on the device's system locale. This used to XOR
    // against context.resources.configuration.layoutDirection, which silently inverted every
    // feed's direction on a device with its OS language set to Hebrew (or any other RTL
    // system locale): a feed explicitly configured as RTL would compute
    // `true xor true = false` and render LTR, and vice versa for an LTR feed. Invisible in
    // this project's testing since the dev AVD's system locale was always English
    // (`false xor anything` is a no-op) — reported by the user only after testing on a
    // Hebrew-locale device, where justification (and alignment generally) came out backwards.
    val isRtl          = feedConfig.layoutDirection == "rtl"
    // Bumped from 9f/10f — reported and confirmed too light/thin to read comfortably at the
    // default font size, on top of already being the smallest, most muted text on the row
    // (onSurfaceVariant, no bold). The size bump applies everywhere; the meta row also gets a
    // slightly heavier weight below (still lighter than the headline's Bold) since size alone
    // doesn't fix strokes that were also just thin.
    val metaFontSize   = (10f * metaScaleFontSize).sp
    val headlineSize   = (13f * fontSize).sp
    val articleSize    = (11f * articleFontSize).sp
    // Thumbnail width: square based on font scale (independent of row height). Capped —
    // uncapped, this scales linearly with fontSize with no ceiling: already a latent risk on
    // the standard flavor at the Font size slider's own top end (52 * 3.0 = 156dp), and made
    // far more likely to actually be hit by Focus Mode, where fontSize is additionally
    // multiplied by focusScale (up to 2.5x) on the focused row — e.g. base 3.0 * focus 2.5 =
    // 7.5x, a 390dp thumbnail alone wider than the entire widget column, breaking the row
    // layout (headline squeezed into a near-zero-width remainder, or thumbnail overflowing/
    // clipping unpredictably). 120dp matches the expanded-image header's own fixed height
    // elsewhere in this file, an already-established "large enough to be a real photo" ceiling.
    val thumbWidth     = (52f * fontSize).dp.coerceAtMost(120.dp)

    // Headline color, hoisted so the expanded-article body text below can reuse the exact
    // same source and always match it (per explicit request: article text should be the
    // same color as the heading, just smaller — previously body text used a separate,
    // intentionally muted color).
    val glamerHeadlineColorArgb = if (themeVariant == "dark") 0xFFEDE4D4.toInt() else 0xFF4A2E14.toInt()
    val nonGlamerHeadlineColor: ColorProvider = when {
        (widgetTheme == "silicon" || widgetTheme == "data_science") && themeVariant != "dark" ->
            ColorProvider(Color(0xFF007870))
        widgetTheme == "aerospace" && themeVariant == "dark" ->
            ColorProvider(Color(0xFFFFE5B4))
        else -> GlanceTheme.colors.onSurface
    }

    // Articles with no <description> at all (see NoOpTapFeedbackCallback) have nothing to
    // expand into — the expanded block below only ever shows description text or, failing
    // that, an "Open article" link, and both are conditioned on content this article simply
    // doesn't have. Routing the tap to a no-op keeps the native press ripple (so the tap
    // still feels acknowledged) without expanding an empty state or offering a link that,
    // for these articles specifically, was the sole reason to expand in the first place.
    // Focus Mode replaces expand-on-tap entirely: a tap sets/clears the focus target instead
    // (see SetFocusArticleCallback) — expanding into description text doesn't make sense
    // alongside shrinking every other row to browse by size, so this branch is checked first
    // and, when isFocusWidget is true, wins regardless of description content.
    val toggleAction = if (isFocusWidget)
        actionRunCallback<SetFocusArticleCallback>(
            actionParametersOf(SetFocusArticleCallback.ARTICLE_ID_KEY to article.id)
        )
    else if (article.description.isNotBlank())
        actionRunCallback<ToggleExpandCallback>(
            actionParametersOf(ToggleExpandCallback.ARTICLE_ID_KEY to article.id)
        )
    else
        actionRunCallback<NoOpTapFeedbackCallback>(
            actionParametersOf(NoOpTapFeedbackCallback.ARTICLE_ID_KEY to article.id)
        )

    // isFocused itself is hoisted above isExpanded now (see that val's own comment). The size
    // difference (1.25x vs 0.5x, see the fontSize shadowing above) is the main "which row is
    // focused" signal, but relying on relative size alone asks the eye to compare against
    // neighbors instead of just recognizing the one row directly — a flat background tint
    // answers that at a glance, independent of what's next to it.

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            // Always call .background(), never conditionally omit it. Reported and confirmed
            // on-device: stepping focus to a different row could leave the PREVIOUS row's
            // highlight visibly on even though its own fontSize correctly reflected "no longer
            // focused" — i.e. isFocused really was false for it, but the tan background stuck
            // around anyway. Root cause: `.let { if (isFocused) it.background(...) else it }`
            // only emits a "set this background color" instruction when isFocused is true: the
            // non-focused case doesn't clear anything, it just never mentions a background at
            // all. If the launcher recycles that row's underlying Android View from a previous
            // update where it WAS the highlighted one, nothing in the new update ever tells it
            // to go back to normal. Explicitly setting a transparent background for the
            // non-focused case (rather than omitting the call) means every update always
            // carries an explicit instruction either way, so a recycled view can never keep a
            // stale color from a previous bind.
            .background(if (isFocused) GlanceTheme.colors.primaryContainer else ColorProvider(Color.Transparent))
            .clickable(toggleAction),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isRtl) {
            Box(modifier = GlanceModifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accentProvider)) {}
        }

        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .clickable(toggleAction),
            horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
        ) {
            // Meta row: favicon circle + feed name + timestamp
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val circleSize    = (14f * metaScaleFontSize).dp
                val faviconFile   = FaviconHelper.file(context, feedConfig.feedId)
                val faviconBmp    = if (faviconFile.exists()) BitmapFactory.decodeFile(faviconFile.absolutePath) else null

                @Composable
                fun FeedCircle() {
                    if (faviconBmp != null) {
                        Image(
                            provider = ImageProvider(faviconBmp),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier
                                .width(circleSize).height(circleSize)
                                .cornerRadius(circleSize / 2),
                        )
                    } else {
                        val initial = (feedConfig.displayName.firstOrNull()?.uppercaseChar() ?: '?').toString()
                        Box(
                            modifier = GlanceModifier
                                .width(circleSize).height(circleSize)
                                .background(accentProvider)
                                .cornerRadius(circleSize / 2),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(initial, style = TextStyle(
                                fontSize   = (8f * metaScaleFontSize).sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                color      = ColorProvider(Color.White),
                            ))
                        }
                    }
                }

                val tsStyle = TextStyle(
                    fontSize    = metaFontSize,
                    fontFamily  = FontFamily.SansSerif,
                    fontWeight  = FontWeight.Medium,
                    color       = GlanceTheme.colors.onSurfaceVariant,
                )
                val nameStyle = TextStyle(
                    fontSize   = metaFontSize,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    color      = accentProvider,
                    // RTL's name box is a fixed width (nameMaxWidth) so it can't push the
                    // circle off the row — without End alignment, a short name sits flush at
                    // the box's start (left) instead of hugging the circle beside it, leaving
                    // a gap whose size varies with how much shorter than nameMaxWidth the name
                    // is. LTR's box is defaultWeight() (starts exactly where the circle ends),
                    // so Start alignment there never produces a gap either way.
                    textAlign  = if (isRtl) androidx.glance.text.TextAlign.End
                                 else       androidx.glance.text.TextAlign.Start,
                )

                // Feed name is grouped on this same line with the favicon circle, right-
                // justified as a unit. This row is now always the column's full width — the
                // thumbnail (further down) only sits beside the headline lines below, not
                // this meta row — so the name's available space, and thus its position, is
                // consistent whether or not this article has a thumbnail.
                if (isRtl) {
                    // Time always on physical LEFT regardless of RTL direction; the dot+name+
                    // circle group is pushed to hug the physical right edge by the weighted
                    // spacer. Name gets a fixed max width so it can't push the circle off
                    // the row for very long feed names.
                    val nameMaxWidth = (70f * metaScaleFontSize).dp
                    Text(formatDateTime(article.publishedAt), style = tsStyle, maxLines = 1)
                    Spacer(GlanceModifier.width(6.dp))
                    Spacer(GlanceModifier.defaultWeight())
                    if (!article.isRead) {
                        Box(modifier = GlanceModifier.width(5.dp).height(5.dp).background(accentProvider)) {}
                        Spacer(GlanceModifier.width(3.dp))
                    }
                    Text(feedConfig.displayName, style = nameStyle, maxLines = 1,
                        modifier = GlanceModifier.width(nameMaxWidth))
                    Spacer(GlanceModifier.width(4.dp))
                    FeedCircle()
                } else {
                    // Time on the left, then circle + name (name absorbs remaining space).
                    Text(formatDateTime(article.publishedAt), style = tsStyle, maxLines = 1)
                    Spacer(GlanceModifier.width(6.dp))
                    FeedCircle()
                    Spacer(GlanceModifier.width(4.dp))
                    if (!article.isRead) {
                        Box(modifier = GlanceModifier.width(5.dp).height(5.dp).background(accentProvider)) {}
                        Spacer(GlanceModifier.width(3.dp))
                    }
                    Text(feedConfig.displayName, style = nameStyle, maxLines = 1,
                        modifier = GlanceModifier.defaultWeight())
                }
            }

            Spacer(GlanceModifier.height(3.dp))

            // Headline — Glamour theme uses a custom Hebrew handwriting font (Playpen Sans Hebrew, bold)
            // rendered to a Bitmap, since Glance/RemoteViews only supports system font families.
            val headlineFontStr = if (feedConfig.fontFamily == "serif" || feedConfig.fontFamily == "mono")
                feedConfig.fontFamily else WidgetThemes.fontFamilyFor(widgetTheme)
            val headlineFontFamily = when (headlineFontStr) {
                "serif"   -> FontFamily.Serif
                "mono"    -> FontFamily.Monospace
                "cursive" -> FontFamily.Cursive
                else      -> FontFamily.SansSerif
            }
            val showSideThumb = feedConfig.displayMode == "image" && !isExpanded
            val sideThumbBmp = if (showSideThumb) {
                val thumbFile = ThumbnailHelper.file(context, article.id)
                if (thumbFile.exists()) BitmapFactory.decodeFile(thumbFile.absolutePath) else null
            } else null
            // Set below once the headline bitmap is actually rendered, so the thumbnail can
            // be sized off its real height rather than an unrelated fillMaxHeight() that only
            // matches whatever the Row happens to stretch to — a 1-line headline made the
            // thumbnail comically thin instead of the requested "at least 2 rows tall."
            var headlineBmpHeightPx = 0

            // Thumbnail lives beside the headline only — not the meta row above it — so the
            // meta row (feed name + circle) is always this Column's full width and stays
            // right-justified consistently whether or not this article has a thumbnail.
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(modifier = GlanceModifier.defaultWeight()) {
            if (widgetTheme == "glamer") {
                val density       = context.resources.displayMetrics.density
                val scaledDensity = context.resources.displayMetrics.scaledDensity
                // +8f: the Spacer between the headline and the thumbnail, so the bitmap's
                // width matches its actual available column instead of running under it.
                // Gated on showSideThumb && sideThumbBmp != null (whether an image will
                // ACTUALLY render for this specific article), not just feedConfig.displayMode
                // == "image" (the feed's setting) — an image-mode feed can still have
                // individual articles with no thumbnail file downloaded, and reserving this
                // width for them left their headlines visibly narrower than the row, not
                // reaching the far edge, with no image ever appearing to justify the gap.
                // Capped consistently with thumbWidth above (120dp) — otherwise, at combined
                // extreme scale (Focus Mode's focusScale on top of the Font size slider), an
                // uncapped thumbDp could exceed the row's entire available width before the
                // headline's own widthPx.coerceAtLeast(50) floor below ever kicks in, starving
                // the headline bitmap down to that 50px minimum regardless of how much real
                // space is actually available — a handful of characters per line, not a crash
                // but a badly broken-looking headline.
                val thumbDp       = if (showSideThumb && sideThumbBmp != null) (52f * fontSize).coerceAtMost(120f) + 8f else 0f
                // Margin was originally estimated at 19dp (3dp accent stripe + 8dp*2 column
                // padding) but measured ~10dp too conservative on a real device: a uiautomator
                // bounds comparison on a properly-sized widget (303dp total) showed the text
                // column actually gets 242dp (303 - 52 thumbnail - 9 true overhead), while this
                // formula was leaving headlines ~16dp narrower than their available column,
                // visibly not reaching the row's edge.
                // coerceAtMost(350f): since LocalSize.current now reports the widget's real
                // size (SizeMode.Exact, fixed elsewhere this session) instead of a frozen
                // 130dp, a widely-resized widget (up to maxResizeWidth=500dp) or a high-density
                // device could make each headline bitmap large enough that up to 15 of them
                // together exceed RemoteViews' total bitmap-memory budget — hit for real
                // ("Can't show content", `IllegalArgumentException: ... exceeds maximum bitmap
                // memory usage`) at the config screen's wider 583dp preview panel. Capping the
                // dp width this formula uses (not the final px, so device density still applies
                // normally up to that cap) bounds worst-case memory while leaving every tested,
                // realistic widget size (303dp default, moderate resizes) completely unaffected.
                val widthPx       = ((LocalSize.current.width.value.coerceAtMost(350f) - 9f - thumbDp) * density)
                                        .toInt().coerceAtLeast(50)
                // Explicit design values for the Glamour headline. Light matches
                // WidgetThemes.kt's GLAMER_LIGHT.onSurface (#4A2E14 — a visibly brown dark
                // ink, not the near-black #2C1A0A this used to be) so the config-screen
                // preview and the real widget agree; dark is picked independently and
                // doesn't match GLAMER_DARK.onSurface. Hoisted (glamerHeadlineColorArgb)
                // so the expanded-article body text can reuse the identical value.
                val colorArgb     = glamerHeadlineColorArgb
                // Below this, there isn't enough room for the bitmap's internal wrapping to stay
                // proportionate to how wide it actually gets displayed (fillMaxWidth() + Fit scale
                // up a too-narrow bitmap into huge, clipped, single-word lines). A plain Text()
                // degrades far more gracefully at extreme widths (native wrap/ellipsis) than the
                // custom bitmap layout does — kept as a guard for genuinely tiny placements now
                // that NewsFeedWidget.sizeMode = Exact reports real widths instead of always 130dp.
                // Focus Mode only: the focused row is the one place on the whole widget where
                // showing more of a long headline is worth its extra bitmap height — it's a
                // single row, not all of them, so the memory cost stays bounded (also see
                // NewsFeedWidget.kt's worstCaseRowScale, which already reserves budget for a
                // taller focused-row bitmap).
                // Tried and empirically disproved on real hardware (both flavors, confirmed via
                // screenshot, not just theory): RemoteViews.setTextViewText(CharSequence) with a
                // Glance FontFamily("Playpen Sans Hebrew") DOES genuinely cross the process
                // boundary as a android.text.style.TypefaceSpan(String) (traced via javap on the
                // real glance-appwidget:1.1.0 AAR — that part of the mechanism is real), but the
                // family-name lookup runs in the LAUNCHER's process at draw time via
                // Typeface.create(String, int), and this font was never installed as a system
                // font the launcher can see — it silently fell back to the launcher's default
                // typeface (no crash, no error, just the wrong font). A bitmap rendered in this
                // app's own process, where the font asset genuinely is available, remains the
                // only way to get the real Playpen Sans Hebrew face onto a RemoteViews widget.
                val bmp = if (widthPx >= 120) TextBitmapHelper.headline(
                    context    = context,
                    text       = article.title,
                    textSizePx = headlineSize.value * scaledDensity,
                    widthPx    = widthPx,
                    isRtl      = isRtl,
                    maxLines   = AdjustFocusScaleCallback.HEADLINE_MAX_LINES,
                ) else null
                if (bmp != null) {
                    headlineBmpHeightPx = bmp.height
                    Image(
                        provider           = ImageProvider(bmp),
                        contentDescription = article.title,
                        modifier           = GlanceModifier.fillMaxWidth(),
                        contentScale       = ContentScale.Fit,
                        // The bitmap itself is colorless now (ALPHA_8 — see TextBitmapHelper's
                        // render()); this is where the actual color gets applied, at display
                        // time, instead of being baked into the bitmap's pixels.
                        colorFilter        = ColorFilter.tint(ColorProvider(Color(colorArgb))),
                    )
                } else {
                    // Font load failed — render as Text so the headline is never blank
                    Text(
                        text = article.title,
                        style = TextStyle(
                            fontSize   = headlineSize,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Cursive,
                            color      = ColorProvider(Color(colorArgb)),
                            textAlign  = if (isRtl) androidx.glance.text.TextAlign.End
                                         else       androidx.glance.text.TextAlign.Start,
                        ),
                        maxLines = 3,
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            } else {
                Text(
                    text = article.title,
                    style = TextStyle(
                        fontSize   = headlineSize,
                        fontWeight = if ("normal" in feedConfig.textStyle) FontWeight.Normal else FontWeight.Bold,
                        fontStyle  = if ("italic" in feedConfig.textStyle) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if ("underline" in feedConfig.textStyle) TextDecoration.Underline else TextDecoration.None,
                        fontFamily = headlineFontFamily,
                        color = if (article.isRead) GlanceTheme.colors.onSurfaceVariant
                                else nonGlamerHeadlineColor,
                        textAlign = if (isRtl) androidx.glance.text.TextAlign.End
                                    else      androidx.glance.text.TextAlign.Start,
                    ),
                    maxLines = 3,
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
            }
            if (showSideThumb && sideThumbBmp != null) {
                Spacer(GlanceModifier.width(8.dp))
                // Sized off the headline's own real rendered height (via headlineBmpHeightPx,
                // set above when its bitmap is created) rather than fillMaxHeight() — that
                // just matched whatever the Row happened to stretch to, so a genuinely
                // 1-line headline made the thumbnail comically thin. Floored at 2 lines'
                // worth so a short headline still gets a reasonably sized image; for
                // non-Glamour themes (plain Text headline, no bitmap to measure) this floor
                // is also the only signal available, so it's used as-is.
                val thumbDensity       = context.resources.displayMetrics.density
                val thumbScaledDensity = context.resources.displayMetrics.scaledDensity
                val minThumbHeightPx   = 2 * headlineSize.value * thumbScaledDensity * 1.2f
                val thumbHeightPx      = maxOf(headlineBmpHeightPx.toFloat(), minThumbHeightPx)
                val thumbHeightDp      = (thumbHeightPx / thumbDensity).dp
                Image(
                    provider = ImageProvider(sideThumbBmp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.width(thumbWidth).height(thumbHeightDp).padding(vertical = 6.dp),
                )
            }
            }

            // Both the thumbnail header and the description/buttons below used to be two
            // separate `if (isExpanded)` blocks emitting directly into this row's outer
            // Column — meaning every element in both (plus however many full-article chunks,
            // each themselves multiple elements) was a direct sibling there. RemoteViews caps
            // a Column at 10 direct children (hit for real once full-article chunks grew past
            // it — see memorySafeMaxChunks' comment), so this whole expanded-state block is
            // now wrapped in one Column, collapsing it to a single child of the outer one
            // regardless of how much is inside.
            if (isExpanded) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
            // Expanded: show thumbnail as a header image (if feed is in image mode)
            if (feedConfig.displayMode == "image") {
                val thumbFile = ThumbnailHelper.file(context, article.id)
                if (thumbFile.exists()) {
                    val bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                    if (bmp != null) {
                        Spacer(GlanceModifier.height(6.dp))
                        Image(
                            provider = ImageProvider(bmp),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .cornerRadius(6.dp),
                        )
                    }
                }
            }

            // Expanded: description + Open article button
            run {
                val resolvedFont = if (feedConfig.fontFamily == "serif" || feedConfig.fontFamily == "mono")
                    feedConfig.fontFamily else WidgetThemes.fontFamilyFor(widgetTheme)
                val feedFontFamily = when (resolvedFont) {
                    "serif"   -> FontFamily.Serif
                    "mono"    -> FontFamily.Monospace
                    "cursive" -> FontFamily.Cursive
                        else      -> FontFamily.SansSerif
                }
                // Article/body text matches the headline's color exactly (same source val,
                // including the isRead dimming) — only font size differs, per explicit
                // request. feedFontFamily is already identical to headlineFontFamily (both
                // derive from the same resolvedFont/headlineFontStr logic), so type already
                // matched; color previously didn't (this used to be a deliberately muted
                // onSurfaceVariant/secondary shade).
                val descStyle = TextStyle(
                    fontSize   = articleSize,
                    fontFamily = feedFontFamily,
                    // Medium, not the default Normal — reported and confirmed too thin/light
                    // to read comfortably at the default article font size. Still visibly
                    // lighter than the headline's Bold, just no longer the thinnest weight
                    // available.
                    fontWeight = FontWeight.Medium,
                    color      = if (article.isRead) GlanceTheme.colors.onSurfaceVariant
                                 else nonGlamerHeadlineColor,
                    textAlign  = if (isRtl) androidx.glance.text.TextAlign.End
                                 else      androidx.glance.text.TextAlign.Start,
                )

                // Hoisted so both the per-chunk "Open in browser" links (full-article mode —
                // each break gets its own, so reaching the source doesn't require scrolling
                // all the way down first) and the final "Open article" button below share one
                // Intent. See that button's own comment for why this goes through
                // actionStartActivity()/ShareRelayActivity rather than a plain callback.
                val openIntent = if (article.articleUrl.isNotBlank()) {
                    if (externalApp == "share") {
                        Intent(context, ShareRelayActivity::class.java)
                            .setData(Uri.parse(article.articleUrl))
                            .putExtra(ShareRelayActivity.EXTRA_ARTICLE_URL, article.articleUrl)
                    } else {
                        Intent(Intent.ACTION_VIEW, Uri.parse(article.articleUrl))
                    }
                } else null

                @Composable
                fun OpenInBrowserLink() {
                    if (openIntent != null) {
                        Text(
                            text = "Open in browser ↗",
                            style = TextStyle(
                                fontSize   = (8f * fontSize).sp,
                                fontFamily = FontFamily.SansSerif,
                                color      = accentProvider,
                            ),
                            modifier = GlanceModifier
                                .clickable(actionStartActivity(openIntent)),
                        )
                    }
                }

                // Glamour renders body text with the same handwriting font as the headline
                // (regular weight, not bold — the headline is bold specifically to stand out
                // from the body), for short snippets AND the unbounded "full article" fetch —
                // converting to a bitmap trades RemoteViews' cheap TextView rendering for a
                // Bitmap, whose memory cost scales with width × height × 4 bytes, so both the
                // input length (maxChars) and the rendered line count are bounded regardless
                // of the caller's request. Line count specifically is derived from a fixed
                // pixel-height budget divided by the actual line height (which already
                // accounts for articleFontSize and device density) rather than a flat number,
                // so it stays safe at every combination of those settings instead of only the
                // ones this was tested at — a fixed line count could still blow the budget at
                // a high articleFontSize + high-density combination a flat cap wouldn't catch.
                @Composable
                fun DescriptionText(text: String, maxLines: Int, maxChars: Int = 400, heightBudgetPx: Float = 600f) {
                    if (widgetTheme == "glamer") {
                        val density       = context.resources.displayMetrics.density
                        val scaledDensity = context.resources.displayMetrics.scaledDensity
                        // Same 350dp cap as the headline bitmap above, and for the same
                        // reason — bounds worst-case RemoteViews bitmap memory at wide/resized
                        // widths without affecting any tested realistic widget size.
                        val widthPx       = ((LocalSize.current.width.value.coerceAtMost(350f) - 9f) * density)
                                                .toInt().coerceAtLeast(50)
                        // heightBudgetPx defaults to 600px for short/medium mode (unaffected
                        // either way since its own maxLines=10 was already the tighter
                        // constraint). The "full" article chunk path passes its own larger,
                        // content-sized budget (see below) so a CHUNK_CHARS-sized chunk
                        // doesn't get silently ellipsis-truncated mid-chunk — see the "full"
                        // block's own comment for why that was actually the dominant cause of
                        // "Load more" seeming to stop short of the article's real end.
                        // 11f, not 10f — matches articleSize's own bump above (reported too
                        // thin/light to read comfortably); kept in sync with the textSizePx
                        // passed to TextBitmapHelper.paragraph() below, or safeMaxLines would
                        // be sized for a different line height than what's actually rendered.
                        val lineHeightPx  = 11f * articleFontSize * scaledDensity * 1.2f
                        val safeMaxLines  = (heightBudgetPx / lineHeightPx).toInt().coerceIn(4, maxLines)
                        // Same color as the headline (glamerHeadlineColorArgb, hoisted above)
                        // — only size differs, per explicit request. Previously body text
                        // used a separate, deliberately lighter/warmer color.
                        val safeText = text.take(maxChars)
                        val bmp = if (widthPx >= 120) TextBitmapHelper.paragraph(
                            context    = context,
                            text       = safeText,
                            textSizePx = 11f * articleFontSize * scaledDensity,
                            widthPx    = widthPx,
                            isRtl      = isRtl,
                            maxLines   = safeMaxLines,
                        ) else null
                        if (bmp != null) {
                            Image(
                                provider           = ImageProvider(bmp),
                                contentDescription = safeText,
                                modifier           = GlanceModifier.fillMaxWidth(),
                                contentScale       = ContentScale.Fit,
                                // See the headline Image() above — same colorless-bitmap,
                                // tint-at-display-time model.
                                colorFilter        = ColorFilter.tint(ColorProvider(Color(glamerHeadlineColorArgb))),
                            )
                            return
                        }
                    }
                    // Non-Glamour themes (or a failed bitmap render) bear no bitmap-memory
                    // cost, so they get the full, uncapped text/line count here regardless of
                    // maxChars — that cap only exists to bound the Glamour bitmap above.
                    Text(text = text, style = descStyle, maxLines = maxLines, modifier = GlanceModifier.fillMaxWidth())
                }

                if (articleLength == "full") {
                    if (fullArticleId == article.id && fullArticleText.isNotBlank()) {
                        Spacer(GlanceModifier.height(4.dp))
                        if (widgetTheme == "glamer") {
                            val density2       = context.resources.displayMetrics.density
                            val scaledDensity2 = context.resources.displayMetrics.scaledDensity
                            val widthPx2       = ((LocalSize.current.width.value.coerceAtMost(350f) - 9f) * density2)
                                                    .toInt().coerceAtLeast(50)
                            // 11f, matching the short/medium-mode paragraph size bump above.
                            val textSizePx2    = 11f * articleFontSize * scaledDensity2
                            val lineHeightPx2  = textSizePx2 * 1.2f
                            // Each chunk must render ALL of its CHUNK_CHARS, or the "shown"
                            // character count (which the button advances by a flat
                            // CHUNK_CHARS per tap) silently outruns what's actually visible —
                            // the old fixed 600px/~18-line budget ellipsized roughly the back
                            // half of every 1200-char chunk, which was the real reason "Load
                            // more" seemed to stall well short of the article's true end, not
                            // just the chunk-count cap below. estCharsPerLine is deliberately
                            // conservative (assumes narrower average glyphs than typical, so
                            // it allocates MORE lines than strictly needed rather than risking
                            // under-provisioning and re-introducing ellipsis truncation).
                            val estCharsPerLine     = (widthPx2 / (textSizePx2 * 0.55f)).toInt().coerceAtLeast(10)
                            val chunkHeightBudgetPx = ((FetchFullArticleCallback.CHUNK_CHARS / estCharsPerLine) + 2) * lineHeightPx2
                            // Bitmap memory for this one expanded row's chunks, computed from
                            // their real height instead of a guessed flat chunk count — this
                            // project has hit the ~15.5MB total RemoteViews bitmap-memory
                            // ceiling once before (see FetchFullArticleCallback's history).
                            // 1 byte/pixel, not 4 — TextBitmapHelper.paragraph() now renders these
                            // chunk bitmaps as ALPHA_8 (colorless coverage mask, tinted at display
                            // time via ColorFilter.tint()), not ARGB_8888. Matches the same fix in
                            // NewsFeedWidget.kt's headlineBytes calculation.
                            val bytesPerChunk    = (widthPx2 * chunkHeightBudgetPx * 1f).coerceAtLeast(1f)
                            // Rebalanced from the original 9MB/7.5MB split (down to 6.5MB here,
                            // NewsFeedWidget.kt's rowBudgetBytes up to 10MB — same ~16.5MB total,
                            // just reallocated). 9MB was sized generously back when this rendered
                            // ARGB_8888; now that it's ALPHA_8 too (~4x cheaper per chunk, same as
                            // the row-budget rationale), that much headroom was mostly wasted here
                            // while capping the row list far more than necessary — only one
                            // article is ever expanded to full-text at a time, so this budget
                            // never needs to be as large as the one covering the WHOLE visible
                            // list simultaneously.
                            val chunkBudgetBytes = 6_500_000f
                            // Upper bound 10 (not just a memory-derived number): each chunk
                            // is wrapped in its own Column below specifically so N chunks
                            // stay as N single-child contributions to their parent rather
                            // than 3×N siblings — but that parent Column can itself only
                            // hold 10 direct children (a real RemoteViews limit, hit for
                            // real as "IllegalArgumentException: Column container cannot
                            // have more than 10 elements" once the per-chunk "Open in
                            // browser" link pushed a 2-chunk expansion past it), so this
                            // caps chunk count at that same ceiling regardless of memory.
                            val memorySafeMaxChunks = (chunkBudgetBytes / bytesPerChunk).toInt().coerceIn(1, 10)
                            // Never allocate more chunks than the article actually has —
                            // a short article should reach its true end (no dangling "Load
                            // more" past the last real chunk), while a long one is capped by
                            // memorySafeMaxChunks, the real memory ceiling, not a guessed flat
                            // number — so "Load more" walks as far into any article as it
                            // safely can, all the way to the end for most real articles.
                            val chunksNeededForArticle = (fullArticleText.length + FetchFullArticleCallback.CHUNK_CHARS - 1) /
                                                              FetchFullArticleCallback.CHUNK_CHARS
                            val maxChunksAllowed = memorySafeMaxChunks.coerceAtMost(chunksNeededForArticle.coerceAtLeast(1))

                            // Chunked pagination: each already-revealed CHUNK_CHARS-sized
                            // slice renders as its own independently-bounded bitmap (same
                            // font/color as the headline), rather than one bitmap sized to
                            // the whole unbounded fetch — see FetchFullArticleCallback for
                            // why. "Load more" (LoadMoreArticleCallback) reveals the next
                            // chunk, up to maxChunksAllowed. Clamped here (not just gated by
                            // hiding the button below) so a stale/racing shown-chars value
                            // from prefs can never push rendering past the computed-safe cap.
                            val shown = fullArticleShown.coerceAtMost(fullArticleText.length)
                                            .coerceAtMost(FetchFullArticleCallback.CHUNK_CHARS * maxChunksAllowed)
                            // Both wrapping Columns below exist only to satisfy RemoteViews'
                            // real "max 10 direct children per Column" limit (see
                            // memorySafeMaxChunks' comment) — each chunk's 3 elements
                            // (spacer/text/link) collapse into 1 child of the outer chunks
                            // Column, and however many chunks there are collapse into 1
                            // child of whatever contains this whole block, regardless of
                            // chunk count.
                            var chunkStart = 0
                            Column(modifier = GlanceModifier.fillMaxWidth()) {
                                while (chunkStart < shown) {
                                    val chunkEnd = (chunkStart + FetchFullArticleCallback.CHUNK_CHARS).coerceAtMost(shown)
                                    // Keyed on chunkStart: without a stable per-iteration key,
                                    // this loop's repeated DescriptionText/OpenInBrowserLink
                                    // emissions were only ever showing ONE "Open in browser" link
                                    // total (after the first chunk) even with 2+ chunks rendered —
                                    // Glance's RemoteViews translation was collapsing the later,
                                    // structurally-identical (same text/style/click action) Text
                                    // nodes into the earlier one instead of emitting each as its
                                    // own view. key() forces each iteration a distinct identity.
                                    key(chunkStart) {
                                        Column(modifier = GlanceModifier.fillMaxWidth()) {
                                            if (chunkStart > 0) Spacer(GlanceModifier.height(6.dp))
                                            DescriptionText(
                                                fullArticleText.substring(chunkStart, chunkEnd),
                                                maxLines       = 60,
                                                maxChars       = FetchFullArticleCallback.CHUNK_CHARS,
                                                heightBudgetPx = chunkHeightBudgetPx,
                                            )
                                            Spacer(GlanceModifier.height(3.dp))
                                            OpenInBrowserLink()
                                        }
                                    }
                                    chunkStart = chunkEnd
                                }
                            }
                            val atCap = shown >= FetchFullArticleCallback.CHUNK_CHARS * maxChunksAllowed
                            if (shown < fullArticleText.length && !atCap) {
                                Spacer(GlanceModifier.height(6.dp))
                                Text(
                                    text = "Load more ↓",
                                    style = TextStyle(
                                        fontSize   = (9f * fontSize).sp,
                                        fontFamily = FontFamily.SansSerif,
                                        color      = accentProvider,
                                    ),
                                    modifier = GlanceModifier
                                        .background(GlanceTheme.colors.primaryContainer)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                        .clickable(
                                            actionRunCallback<LoadMoreArticleCallback>(
                                                actionParametersOf(LoadMoreArticleCallback.ARTICLE_ID_KEY to article.id)
                                            )
                                        ),
                                )
                            }
                        } else {
                            // Non-Glamour body text is cheap plain-Text with no bitmap
                            // memory cost, so it renders the whole fetched article at once —
                            // pagination only exists to bound Glamour's bitmap rendering.
                            Text(
                                text = fullArticleText,
                                style = descStyle,
                                maxLines = 200,
                                modifier = GlanceModifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        if (article.description.isNotBlank()) {
                            Spacer(GlanceModifier.height(4.dp))
                            val clipped = article.description.take(400).trimEnd()
                            DescriptionText(clipped, maxLines = 10)
                        }
                        if (article.articleUrl.isNotBlank()) {
                            Spacer(GlanceModifier.height(6.dp))
                            Text(
                                text = "Load full article ↓",
                                style = TextStyle(
                                    fontSize   = (9f * fontSize).sp,
                                    fontFamily = FontFamily.SansSerif,
                                    color      = accentProvider,
                                ),
                                modifier = GlanceModifier
                                    .background(GlanceTheme.colors.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                    .clickable(
                                        actionRunCallback<FetchFullArticleCallback>(
                                            actionParametersOf(
                                                FetchFullArticleCallback.ARTICLE_ID_KEY          to article.id,
                                                FetchFullArticleCallback.ARTICLE_URL_KEY         to article.articleUrl,
                                                FetchFullArticleCallback.ARTICLE_DESCRIPTION_KEY to article.description,
                                            )
                                        )
                                    ),
                            )
                        }
                    }
                } else {
                    if (article.description.isNotBlank()) {
                        val limit   = if (articleLength == "short") 100 else 400
                        val raw     = article.description
                        val clipped = if (raw.length > limit) raw.take(limit).trimEnd() + "…" else raw
                        Spacer(GlanceModifier.height(4.dp))
                        DescriptionText(clipped, maxLines = 10)
                    }
                }

                if (openIntent != null) {
                    Spacer(GlanceModifier.height(6.dp))
                    // Article rows live inside a LazyColumn, so clicks route through Glance's
                    // list-adapter trampoline (InvisibleActionTrampolineActivity). Building the
                    // Intent at compose time and using actionStartActivity() (rather than a custom
                    // ActionCallback manually calling context.startActivity()) is what makes Browser
                    // mode (a plain ACTION_VIEW) work reliably. Share mode does not: an ACTION_SEND
                    // intent is inherently ambiguous (multiple apps can match), and two different
                    // attempts to fix it directly — dropping Intent.createChooser(), then giving each
                    // row's intent a distinct `data` field to dodge Glance's action-conflation — both
                    // still failed (the second differently: the trampoline now fires but silently
                    // self-finishes without ever launching anything). Rather than keep fighting
                    // Glance's handling of ambiguous/chooser intents, Share mode now targets
                    // ShareRelayActivity — a real, single, unambiguous target within our own app —
                    // which then builds and launches the actual chooser from a proper Activity
                    // context that isn't subject to any of this. (openIntent itself is built once,
                    // above, and shared with the per-chunk OpenInBrowserLink links.)
                    Text(
                        text = "Open article →",
                        style = TextStyle(
                            fontSize   = (9f * fontSize).sp,
                            fontFamily = FontFamily.SansSerif,
                            color      = accentProvider,
                        ),
                        modifier = GlanceModifier
                            .background(GlanceTheme.colors.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .clickable(actionStartActivity(openIntent)),
                    )
                }
            }
            }
            }
        }

        if (isRtl) {
            Spacer(GlanceModifier.width(6.dp))
            Box(modifier = GlanceModifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accentProvider)) {}
        }
    }
}

private fun formatDateTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val articleCal = Calendar.getInstance().also { it.timeInMillis = epochMs }
    val nowCal     = Calendar.getInstance()
    val timeStr    = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    return if (articleCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
               articleCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
        timeStr
    } else {
        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
