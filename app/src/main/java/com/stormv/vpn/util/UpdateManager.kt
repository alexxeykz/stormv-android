package com.stormv.vpn.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stormv.vpn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String
)

object UpdateManager {
    private const val API_URL = "https://api.github.com/repos/alexxeykz/stormv-android/releases/latest"

    /** Проверяет наличие новой версии. Возвращает null если обновления нет или ошибка. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "StormV/${BuildConfig.VERSION_NAME}")
            conn.connectTimeout = 10_000
            conn.readTimeout   = 10_000

            val type = object : TypeToken<Map<String, Any>>() {}.type
            val json: Map<String, Any> = Gson().fromJson(
                conn.inputStream.bufferedReader().readText(), type
            )

            val tagName = json["tag_name"] as? String ?: return@runCatching null
            // Tag format: "v2026.04.25" → versionName "2026.04.25"
            val versionName = tagName.trimStart('v')
            // versionCode = digits only: "2026.04.25" → 20260425
            val versionCode = versionName.replace(".", "").toIntOrNull() ?: 0

            if (versionCode <= BuildConfig.VERSION_CODE) return@runCatching null

            @Suppress("UNCHECKED_CAST")
            val assets = json["assets"] as? List<Map<String, Any>> ?: return@runCatching null
            val apkUrl = assets.firstOrNull { (it["name"] as? String)?.endsWith(".apk") == true }
                ?.get("browser_download_url") as? String ?: return@runCatching null

            AppLogger.i("Update", "Доступна версия $versionName (code $versionCode)")
            UpdateInfo(versionName, versionCode, apkUrl)
        }.getOrElse {
            AppLogger.w("Update", "Ошибка проверки: ${it.message}")
            null
        }
    }

    /** Скачивает APK и запускает установку. onProgress: 0-100. */
    suspend fun downloadAndInstall(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val apkFile = File(context.cacheDir, "stormv-update.apk")

        try {
            AppLogger.i("Update", "Скачиваю ${updateInfo.apkUrl}")
            val conn = URL(updateInfo.apkUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "StormV/${BuildConfig.VERSION_NAME}")
            val total = conn.contentLength.toLong()
            val input = conn.inputStream
            val output = FileOutputStream(apkFile)

            val buffer = ByteArray(65536)
            var downloaded = 0L
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                downloaded += read
                if (total > 0) onProgress((downloaded * 100 / total).toInt())
            }
            output.close()
            input.close()
            onProgress(100)
            AppLogger.i("Update", "Скачано ${apkFile.length() / 1024} KB")

            withContext(Dispatchers.Main) {
                installApk(context, apkFile)
            }
        } catch (e: Exception) {
            AppLogger.e("Update", "Ошибка скачивания: ${e.message}")
            throw e
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        // Android 8+: нужно разрешение REQUEST_INSTALL_PACKAGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                // Открываем настройки для выдачи разрешения
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
