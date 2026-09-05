package com.newsfeed.widget.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.newsfeed.widget.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks the repo's rolling "latest" GitHub Release for a build newer than this one and,
 * unless [checkAndUpdate] is called with `notifyOnly = true`, downloads it and hands it to
 * Android's own package installer. Shared by the daily background check (UpdateCheckWorker,
 * notifyOnly = true), the manual "Check for updates" row (WidgetConfigActivity), and the
 * "Update available" notification's tap target (UpdateRelayActivity) — the latter two both
 * call with notifyOnly = false.
 */
object UpdateManager {
    private const val RELEASE_BASE = "https://github.com/Had-com/NewsFeed-widget/releases/download/latest"
    private const val VERSION_JSON_URL = "$RELEASE_BASE/version.json"
    private const val APK_URL = "$RELEASE_BASE/NewsFeed-latest.apk"
    private const val CHANNEL_ID = "update_available"
    private const val NOTIFICATION_ID = 1001

    @Serializable
    private data class VersionInfo(val versionCode: Int)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun checkAndUpdate(context: Context, notifyOnly: Boolean) {
        val latestVersionCode = withContext(Dispatchers.IO) { fetchLatestVersionCode() }
        if (latestVersionCode == null) {
            if (!notifyOnly) toast(context, "Couldn't check for updates — try again later")
            return
        }
        if (latestVersionCode <= BuildConfig.VERSION_CODE) {
            if (!notifyOnly) toast(context, "You're up to date (build ${BuildConfig.VERSION_CODE})")
            return
        }

        if (notifyOnly) {
            notifyUpdateAvailable(context, latestVersionCode)
            return
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            toast(context, "Allow installing updates from this app, then try again")
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        toast(context, "Downloading update…")
        val apkFile = withContext(Dispatchers.IO) { downloadApk(context) }
        if (apkFile == null) {
            toast(context, "Update download failed — try again later")
            return
        }

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    private fun fetchLatestVersionCode(): Int? = try {
        val request = Request.Builder().url(VERSION_JSON_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.string()?.let { Json.decodeFromString<VersionInfo>(it).versionCode }
        }
    } catch (_: Exception) {
        null
    }

    private fun downloadApk(context: Context): File? = try {
        val request = Request.Builder().url(APK_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "update.apk")
            body.byteStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun toast(context: Context, message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun notifyUpdateAvailable(context: Context, versionCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val tapIntent = Intent(context, UpdateRelayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("NewsFeed update available")
            .setContentText("Build $versionCode is ready — tap to download and install")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
