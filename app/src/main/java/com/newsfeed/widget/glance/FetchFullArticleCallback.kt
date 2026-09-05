package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class FetchFullArticleCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY          = ActionParameters.Key<String>("fetchArticleId")
        val ARTICLE_URL_KEY         = ActionParameters.Key<String>("fetchArticleUrl")
        val ARTICLE_DESCRIPTION_KEY = ActionParameters.Key<String>("fetchArticleDesc")

        // Glamour's full-article body renders through a Bitmap (the only way to use the
        // Playpen Sans Hebrew font in a RemoteViews-hosted widget at all — see TextBitmapHelper), whose
        // memory cost scales with rendered size. Unbounded fetched-article text could reach
        // several KB, so it's revealed in bounded chunks via "Load more" (LoadMoreArticleCallback)
        // instead of one ever-growing bitmap — each already-revealed chunk stays on screen as
        // its own separate, independently-bounded bitmap.
        const val CHUNK_CHARS = 1200
        // Coarse backstop only — the real, precise cap is computed in FeedItemRow from the
        // widget's actual live width/font size/density (see maxChunksAllowed there), since
        // that's what actually determines each chunk's bitmap memory cost. This constant just
        // stops fullArticleShownChars (stored in prefs, read back here without any
        // composition context to do that precise math) from growing unbounded between updates;
        // FeedItemRow's own coerceAtMost against its computed cap is what actually protects
        // the ~15.5MB RemoteViews bitmap-memory ceiling this project has hit once before.
        const val MAX_CHUNKS  = 8

        private val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val articleId   = parameters[ARTICLE_ID_KEY]  ?: return
        val articleUrl  = parameters[ARTICLE_URL_KEY] ?: return
        val description = parameters[ARTICLE_DESCRIPTION_KEY] ?: ""

        // Show the RSS description immediately so users see content right away
        if (description.isNotBlank()) {
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WidgetStateKey.fullArticleId]          = articleId
                prefs[WidgetStateKey.fullArticleText]        = description
                prefs[WidgetStateKey.fullArticleShownChars]  = CHUNK_CHARS
            }
            updateNewsFeedWidget(context, glanceId)
        }

        // Fetch the full article in the background; update again when done
        val content = withContext(Dispatchers.IO) { fetchContent(articleUrl) }

        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetStateKey.fullArticleId]   = articleId
            prefs[WidgetStateKey.fullArticleText] = content
        }
        updateNewsFeedWidget(context, glanceId)
    }

    private fun fetchContent(url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "Could not load article: HTTP ${response.code}"
                val body  = response.body ?: return "Could not load article: empty response"
                val bytes = body.bytes()

                // response.body.string()/contentType()?.charset() only knows a charset when
                // the HTTP Content-Type HEADER declares one — many Hebrew news sites (rotter.net
                // included) instead only declare it via an in-HTML <meta charset> tag (verified
                // directly: rotter.net's article pages send a bare "text/html" header with no
                // charset param at all, but the page itself has
                // <meta http-equiv="Content-Type" content="text/html; charset=windows-1255">).
                // OkHttp has no way to see that, so it silently falls back to UTF-8 regardless —
                // this was the actual root cause of the mojibake, not a missing string()/bytes()
                // distinction. Sniff the meta tag from the raw bytes (decoded as ISO-8859-1,
                // which maps every byte 1:1 to a code point and so can never itself corrupt the
                // plain-ASCII "charset=..." text being searched for, regardless of the page's
                // real encoding) and only fall back to the header/UTF-8 when no meta tag exists.
                val headerCharset = body.contentType()?.charset()
                val charset = headerCharset ?: run {
                    val prefixAscii = String(bytes, 0, bytes.size.coerceAtMost(2048), Charsets.ISO_8859_1)
                    val metaCharset = Regex("""charset=["']?([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
                        .find(prefixAscii)?.groupValues?.get(1)
                    metaCharset?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                }
                String(bytes, charset)
            }

            // Real DOM parser (Jsoup), not regex — lets content be selected by what it actually
            // *is* (paragraph/heading/list elements) rather than by an ever-growing blacklist of
            // noise tags. A regex tag-stripper keeps everything inside <article>/<body> except a
            // few named tags, so an ad slot, a "related articles" grid, or a share-buttons bar
            // sitting *inside* the article markup as a <div> all survived verbatim — regex has no
            // notion of "this div is an ad card, not body text." Jsoup gives a real tree to
            // select and remove things from instead.
            val doc = Jsoup.parse(html, url)
            val contentRoot = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()

            // First pass: whole noise-tag subtrees, same set the old regex version stripped
            // (iframe/object/embed catch ad and widget embeds a bare fallback would otherwise
            // pull in verbatim; <form> is excluded here for the same reason it was excluded
            // before — a single <form> can span nearly the entire body on old-style forum
            // templates, so removing it wholesale risks deleting genuine article text along with
            // the noise).
            contentRoot?.select(
                "script, style, nav, footer, aside, header, iframe, object, embed, svg, select, noscript, button"
            )?.remove()

            // Second pass: elements whose class/id names them as chrome rather than content —
            // ad slots, share/social widgets, related-content rails, newsletter prompts, comment
            // sections, cookie/consent banners. These are almost always *div*-shaped, so the old
            // regex approach (which only knew whole tag names) could never touch them; walking
            // the real DOM and matching on class/id is what actually reaches them. Broad but
            // word-bounded ([\s_-] on each side, not a raw substring match) to avoid false hits
            // like a class named "adjacent-info" matching "ad", or "shared-header" matching
            // "share" — go the other direction (word-bounded) rather than substring, since a
            // false REMOVAL silently deletes real article text with no visible symptom, while a
            // false negative just leaves a little chrome text behind (annoying, not data loss).
            val noisePattern = Regex(
                """(?i)(?:^|[\s_-])(ad|ads|advert|advertisement|banner|promo|promoted|sponsor|sponsored|""" +
                    """outbrain|taboola|share|social|newsletter|related|recommend|comment|widget|popup|""" +
                    """cookie|consent|paywall|subscribe|breadcrumb|byline-share|tag-list)(?:[\s_-]|$)"""
            )
            contentRoot?.select("*")
                ?.filter { el ->
                    val key = "${el.className()} ${el.id()}"
                    key.isNotBlank() && noisePattern.containsMatchIn(key)
                }
                ?.forEach { it.remove() }

            // Extract only paragraph-shaped content (p/h2-4/blockquote/li) rather than the whole
            // container's text. This is the real win over the old approach: an ad card or
            // related-articles grid is essentially never marked up as flowing <p> text — it's
            // div/li-with-links/button chrome — so selecting specifically for paragraph elements
            // excludes most of it for free, without needing to have specifically named it above.
            // Falls back to the container's whole text for minimalist pages that don't wrap body
            // text in <p> at all (rare, but seen on some older forum-style templates).
            val paragraphs = contentRoot?.select("p, h2, h3, h4, blockquote, li")
                ?.map { it.text().trim() }
                // Length floor of 8 filters stray one/two-word UI labels (e.g. a lone "שתף" /
                // "Share" or "הבא" / "Next" button whose markup happens to be a <p> or <li>) that
                // survived the class/id pass above without deleting genuinely short sentences,
                // which real article prose does still contain occasionally.
                ?.filter { it.length > 8 }
                ?: emptyList()
            val extracted = if (paragraphs.isNotEmpty())
                paragraphs.joinToString("\n\n")
            else
                (contentRoot?.text() ?: doc.text())

            // Extra safety net, kept from the previous implementation: ynet's template still
            // places a couple of real <p> lines from its comments widget ("הוספת תגובה" / "שלח
            // תגובה" / "טען תגובות נוספות" — Add/Send comment, Load more comments) right after
            // the genuine article body, which the class/id pass above doesn't reliably catch
            // since that particular widget isn't consistently marked with a matching class name.
            // "מצאתם טעות? כתבו לנו" ("Found a mistake? Write to us") is ynet's standard
            // end-of-article footer line immediately preceding it, so it's a safe, stable
            // truncation point. Deliberately not truncating at the first "הוספת תגובה" instead —
            // that phrase also appears once *before* the real body, as a "jump to comments" link
            // right under the byline, so matching on it would cut off genuine article text.
            val commentsMarker = "מצאתם טעות"
            val markerIdx = extracted.indexOf(commentsMarker)
            if (markerIdx >= 0) extracted.substring(0, markerIdx).trim() else extracted
        } catch (e: Exception) {
            "Could not load article: ${e.message}"
        }
    }
}
