# Merge Focus Mode into one app (two widgets) — design spec
**Date:** 2026-09-05
**Project:** NewsFeed widget
**Status:** Approved

---

## Overview

Today, "standard" and "focusMode" are two separate Gradle product flavors producing two separate APKs with different `applicationId`s (`com.newsfeed.widget` and `com.newsfeed.widget.focus`), installed side by side. Both currently display the same app name ("NewsFeed"/"NewsFeed Focus" is only distinguishable once installed, not from the widget picker if a user hasn't already got both installed) — this was always meant as a temporary testing setup: `build.gradle.kts`'s own comment on the `focusMode` flavor says it exists "for testing the tap-to-focus / shrink-the-rest browsing idea **before deciding whether to fold it into the real app**."

This spec folds it in: one app (`com.newsfeed.widget`), offering **two** widgets from the same "Add widget" picker — "NewsFeed" (standard) and "NewsFeed Focus" — either or both placeable simultaneously. `BuildConfig.FOCUS_MODE` (a compile-time flag, since it came from a Gradle flavor) becomes a runtime `isFocusWidget: Boolean`, determined per widget instance by which of the two `GlanceAppWidgetReceiver`s that instance belongs to.

This also simplifies the self-update feature landed just before this spec: one app now means one APK per release instead of two flavor-named ones.

---

## Part 1 — Two widget classes, one shared rendering path

### New class structure
- `NewsFeedWidget` / `NewsFeedWidgetReceiver` — existing names, unchanged behavior, now explicitly "the standard widget."
- `NewsFeedFocusWidget` / `NewsFeedFocusWidgetReceiver` — new, structurally identical to the standard pair.

Both `GlanceAppWidget` subclasses stay tiny (`override val sizeMode`, `provideGlance()`), calling a **shared** top-level `WidgetContent(isFocusWidget: Boolean)` composable (currently a private member of the `NewsFeedWidget` class — becomes a top-level function so both classes can call it). `WidgetHeader(...)` gains the same `isFocusWidget: Boolean` parameter; `WidgetFooter`/`Divider` are unaffected (they don't reference Focus Mode).

### Every real `BuildConfig.FOCUS_MODE` code site becomes `isFocusWidget`
(Six real code sites found via search — the rest of the `BuildConfig.FOCUS_MODE` matches in the codebase are comments, which should be updated for accuracy but don't affect behavior):

| File | Line | Current | Becomes |
|---|---|---|---|
| `FeedItemRow.kt` | 78 | `BuildConfig.FOCUS_MODE && article.id == focusedArticleId` | `isFocusWidget && article.id == focusedArticleId` |
| `FeedItemRow.kt` | 95 | `if (BuildConfig.FOCUS_MODE && focusedArticleId.isNotBlank())` | `if (isFocusWidget && focusedArticleId.isNotBlank())` |
| `FeedItemRow.kt` | 169 | `if (BuildConfig.FOCUS_MODE)` (which callback a tap routes to) | `if (isFocusWidget)` |
| `NewsFeedWidget.kt` | 143 | `if (BuildConfig.FOCUS_MODE)` (`worstCaseRowScale`) | `if (isFocusWidget)` |
| `NewsFeedWidget.kt` | 382 | `if (BuildConfig.FOCUS_MODE) {` (header step buttons) | `if (isFocusWidget) {` |
| `WidgetConfigActivity.kt` | 490 | `if (BuildConfig.FOCUS_MODE) {` ("Background rows size" slider) | `if (isFocusWidget) {` (see Part 3 for how this Activity determines it) |

`FeedItemRow(...)` gains an `isFocusWidget: Boolean` parameter, threaded in from `WidgetContent`'s per-article call site — same shape as its existing `focusedArticleId`/`focusScale`/`focusBackgroundScale` parameters, which stay as-is (they're already just inert/never-written values for a non-focus widget instance, same as today's "standard flavor" case — nothing about *those* needs to change, only the boolean that gates using them).

`AdjustFocusScaleCallback`, `FocusStepCallback`, `SetFocusArticleCallback`, `ClearFocusCallback` need **no internal changes** — they're only ever wired up (via `actionRunCallback<...>()`) from inside the `isFocusWidget == true` branches of `FeedItemRow`/`WidgetHeader`, exactly as they were only ever wired up under `BuildConfig.FOCUS_MODE == true` before. Their own logic doesn't reference the flag.

---

## Part 2 — Shared background work must survive either widget type alone

**The bug this refactor must not introduce:** `WidgetWorker`'s periodic refresh and `UpdateCheckWorker`'s daily check are singleton jobs (`enqueueUniquePeriodicWork` under a fixed name), started in `onEnabled()`/stopped in `onDisabled()`. Android calls a receiver's `onDisabled()` when *that receiver's own* widget count hits zero — not when all widgets across the whole app are gone. With two receivers now, removing only the standard widget (while a Focus widget remains placed) would fire `NewsFeedWidgetReceiver.onDisabled()`, which — unless fixed — would cancel the shared jobs out from under the still-placed Focus widget.

**Fix — each receiver's `onDisabled()` checks the other provider before cancelling shared work:**

```kotlin
// NewsFeedWidgetReceiver.onDisabled()
override fun onDisabled(context: Context) {
    super.onDisabled(context)
    val otherStillPlaced = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, NewsFeedFocusWidgetReceiver::class.java))
        .isNotEmpty()
    if (!otherStillPlaced) {
        WidgetWorker.cancel(context)
        UpdateCheckWorker.cancel(context)
    }
    cancelClockTick(context)
}
```
(and the mirror image in `NewsFeedFocusWidgetReceiver`, checking `NewsFeedWidgetReceiver`'s id count instead).

`onEnabled()` calling `WidgetWorker.schedule()`/`UpdateCheckWorker.schedule()` from either receiver needs no such guard — `enqueueUniquePeriodicWork` is idempotent by work name, so both receivers calling it is harmless.

**`WidgetWorker.doWork()` must enumerate both widget classes**, not just `NewsFeedWidget::class.java`:
```kotlin
val widgetIds = manager.getGlanceIds(NewsFeedWidget::class.java) +
                manager.getGlanceIds(NewsFeedFocusWidget::class.java)
```
and its final `NewsFeedWidget().updateAll(context)` needs a matching `NewsFeedFocusWidget().updateAll(context)` alongside it, or a placed Focus widget would never get refreshed article data.

**The `CLOCK_TICK` footer-countdown alarm is per-receiver already, no shared-state bug — but needs duplicating.** `NewsFeedWidgetReceiver` schedules its own `AlarmManager` alarm (action `"com.newsfeed.widget.CLOCK_TICK"`) targeting itself, updating only `NewsFeedWidget`'s instances. `NewsFeedFocusWidgetReceiver` needs the identical mechanism under its own action string (e.g. `"com.newsfeed.widget.CLOCK_TICK_FOCUS"`, to avoid colliding `PendingIntent`s) targeting itself and calling `NewsFeedFocusWidget().updateAll(context)` — otherwise a placed Focus widget's footer countdown would never tick.

---

## Part 3 — Settings screen determines widget type per-instance

`WidgetConfigActivity` already resolves `appWidgetId` once in `onCreate()` before `setContent {}`. Add, right after that resolution:
```kotlin
val isFocusWidget = AppWidgetManager.getInstance(this)
    .getAppWidgetInfo(appWidgetId)?.provider
    ?.className == NewsFeedFocusWidgetReceiver::class.java.name
```
This becomes a plain `val` on the Activity, captured by the Composable content below it (same pattern as `appWidgetId` itself already is). Replaces `BuildConfig.FOCUS_MODE` at line 490 (the "Background rows size" slider) with `isFocusWidget`. One shared `WidgetConfigActivity`, one `android:configure` target in both widgets' `appwidget_info.xml` — no duplication needed here.

---

## Part 4 — Build, manifest, resources, and self-update cleanup

- **`app/build.gradle.kts`**: remove `flavorDimensions`/`productFlavors` entirely. One `applicationId`, one `versionCode`/`versionName`/`minSdk`/`targetSdk`. Delete `app/src/focusMode/` entirely (its `strings.xml` override folds into `app/src/main/res/values/strings.xml` as a second string: `widget_label_focus = "NewsFeed Focus"`, alongside the existing `app_name`/`widget_label = "NewsFeed"`). Same for the Hebrew translation if one exists for the focus flavor (it doesn't currently — `app/src/focusMode/res/values/strings.xml` has no `values-iw` sibling, so nothing to migrate there).
- **`AndroidManifest.xml`**: two `<receiver>` entries (existing `NewsFeedWidgetReceiver` + new `NewsFeedFocusWidgetReceiver`, each with its own `<intent-filter>` for `APPWIDGET_UPDATE` + its own `CLOCK_TICK` action string), two `appwidget_info.xml` resources (`appwidget_info.xml` unchanged for standard, new `appwidget_info_focus.xml` for Focus — identical content and shared `android:description="@string/widget_description"`, both keep `android:configure="com.newsfeed.widget.config.WidgetConfigActivity"`; the picker label comes from the `<receiver>`'s own `android:label` in the manifest, not from `appwidget_info.xml` — that's where `@string/widget_label` vs the new `@string/widget_label_focus` actually applies). One `WidgetConfigActivity` entry, unchanged.
- **CI (`.github/workflows/build.yml`)**: `gradle assembleDebug` now produces one variant's output (`app/build/outputs/apk/debug/*.apk` — no per-flavor subdirectory once flavors are removed), so the `Rename APK`/`Prepare latest-release assets` steps drop their per-flavor loop/two-asset-copy, producing one `NewsFeed-latest.apk`.
- **`UpdateManager.kt`**: `apkUrlForThisFlavor()` (and its `BuildConfig.FLAVOR` reference) is replaced by a fixed `$RELEASE_BASE/NewsFeed-latest.apk` URL — there's only one APK now, no flavor to pick between.
- **Device migration**: uninstall `com.newsfeed.widget.focus` from the test device once the merged app is verified working. Any Focus widget placed from the old separate app is removed with it; a fresh Focus widget gets re-added from the merged app afterward. No data migration needed — each placed widget's own state (per-`appWidgetId` DataStore file) is independent of which app/package it came from, and there's no expectation of preserving the old focus app's accumulated articles across this switch.

---

## Testing

- Both "NewsFeed" and "NewsFeed Focus" appear as two distinct entries in the system's "Add widget" picker for this one app, correctly labeled.
- Place one of each simultaneously — both render and refresh independently and correctly (standard: no focus UI at all; Focus: tap-to-focus, header step buttons, per-row scale, all working exactly as the old separate focusMode app did).
- Open each placed widget's Settings (gear icon) independently — confirm the Focus-only "Background rows size" slider appears only for the Focus widget's config screen, not the standard one, and vice versa for anything standard-only (there isn't anything standard-only today, but this establishes the per-instance detection is real, not accidentally inverted or always-true/always-false).
- Remove only the standard widget while a Focus widget remains placed — confirm (via `adb shell dumpsys jobscheduler`/`adb shell dumpsys alarm`) that `WidgetWorker`'s periodic refresh, `UpdateCheckWorker`'s daily check, and the Focus widget's own `CLOCK_TICK` alarm are all still scheduled and that the Focus widget keeps refreshing/counting down. Repeat in the other order (remove Focus, keep standard).
- Remove all widgets of both types — confirm the shared periodic jobs actually get cancelled (no orphaned `WidgetWorker`/`UpdateCheckWorker` work left running forever) and both `CLOCK_TICK` alarms are cancelled.
- Self-update: confirm CI publishes exactly one `NewsFeed-latest.apk` (no more `-standard-`/`-focusMode-` split) and that "Check for updates" from either placed widget's Settings screen finds and installs it correctly on the merged app.
- Full regression pass on Focus Mode behavior specifically (tap-to-focus, ▲▼ step, ✕ clear, −/+ scale, "N/M" indicator, auto-expand-into-description from the recently-added fix) — since this is a structural move of the same logic, not a rewrite, the goal is confirming nothing broke in the move, not re-designing the feature.
