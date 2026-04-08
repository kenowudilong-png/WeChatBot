package com.example.wechatbot

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用更新检查器
 * 从 GitHub Releases 获取最新版本并下载安装
 */
object UpdateChecker {

    private const val GITHUB_API_URL =
        "https://api.github.com/repos/kenowudilong-png/WeChatBot/releases/tags/latest"

    var onLog: ((String) -> Unit)? = null
    var onStatus: ((UpdateStatus) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    enum class UpdateStatus {
        IDLE, CHECKING, NO_UPDATE, UPDATE_AVAILABLE, DOWNLOADING, DOWNLOADED, ERROR
    }

    data class ReleaseInfo(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("assets") val assets: List<Asset>?
    )

    data class Asset(
        @SerializedName("name") val name: String?,
        @SerializedName("browser_download_url") val downloadUrl: String?,
        @SerializedName("size") val size: Long?
    )

    /**
     * 检查更新
     */
    fun checkUpdate(context: Context, currentVersionCode: Int) {
        postStatus(UpdateStatus.CHECKING)
        onLog?.invoke("正在检查更新...")

        Thread {
            try {
                val url = URL(GITHUB_API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode != 200) {
                    postLog("检查更新失败: HTTP ${conn.responseCode}")
                    postStatus(UpdateStatus.ERROR)
                    return@Thread
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val release = gson.fromJson(response, ReleaseInfo::class.java)
                val apkAsset = release.assets?.firstOrNull { it.name?.endsWith(".apk") == true }

                if (apkAsset == null) {
                    postLog("未找到 APK 文件")
                    postStatus(UpdateStatus.ERROR)
                    return@Thread
                }

                // 比较版本：从 release notes 中提取 commit sha，简单比较
                // 因为我们用 "latest" tag 覆盖发布，只要有新 release 就认为有更新
                val releaseName = release.name ?: "unknown"
                postLog("最新版本: $releaseName")
                postLog("APK 大小: ${(apkAsset.size ?: 0) / 1024 / 1024}MB")
                postLog("下载地址: ${apkAsset.downloadUrl}")

                postStatus(UpdateStatus.UPDATE_AVAILABLE)

                // 直接开始下载
                downloadAndInstall(context, apkAsset.downloadUrl!!, apkAsset.name!!)

            } catch (e: Exception) {
                postLog("检查更新异常: ${e.message}")
                postStatus(UpdateStatus.ERROR)
            }
        }.start()
    }

    /**
     * 使用 DownloadManager 下载 APK 并安装
     */
    private fun downloadAndInstall(context: Context, url: String, fileName: String) {
        postStatus(UpdateStatus.DOWNLOADING)
        postLog("开始下载更新...")

        try {
            // 删除旧文件
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val existingFile = File(downloadsDir, fileName)
            if (existingFile.exists()) existingFile.delete()

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("WeChatBot 更新")
                .setDescription("正在下载新版本...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            // 监听下载完成
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        context.unregisterReceiver(this)
                        postLog("下载完成")
                        postStatus(UpdateStatus.DOWNLOADED)

                        // 安装 APK
                        val file = File(downloadsDir, fileName)
                        installApk(context, file)
                    }
                }
            }

            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )

        } catch (e: Exception) {
            postLog("下载失败: ${e.message}")
            postStatus(UpdateStatus.ERROR)
        }
    }

    /**
     * 启动安装 APK
     */
    private fun installApk(context: Context, file: File) {
        try {
            postLog("正在启动安装: ${file.absolutePath}")

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)

        } catch (e: Exception) {
            postLog("安装失败: ${e.message}")
            postStatus(UpdateStatus.ERROR)
        }
    }

    private fun postLog(msg: String) {
        handler.post { onLog?.invoke(msg) }
    }

    private fun postStatus(status: UpdateStatus) {
        handler.post { onStatus?.invoke(status) }
    }
}
