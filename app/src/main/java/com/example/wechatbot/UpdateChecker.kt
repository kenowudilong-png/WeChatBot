package com.example.wechatbot

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
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
 * 使用 GitHub 镜像加速国内下载
 */
object UpdateChecker {

    // 直连 GitHub API（较小的 JSON 数据，通常还行）
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/kenowudilong-png/WeChatBot/releases/tags/latest"

    // GitHub 镜像加速列表（用于下载 APK 大文件）
    private val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://ghproxy.cc/",
        "https://gh-proxy.com/",
        "" // 最后用直连兜底
    )

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
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

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

                val releaseName = release.name ?: "unknown"
                postLog("最新版本: $releaseName")
                postLog("APK 大小: ${(apkAsset.size ?: 0) / 1024 / 1024}MB")

                postStatus(UpdateStatus.UPDATE_AVAILABLE)

                // 尝试用镜像加速下载
                val originalUrl = apkAsset.downloadUrl!!
                downloadWithMirror(context, originalUrl, apkAsset.name!!)

            } catch (e: Exception) {
                postLog("检查更新异常: ${e.message}")
                postStatus(UpdateStatus.ERROR)
            }
        }.start()
    }

    /**
     * 依次尝试镜像下载，哪个能连上用哪个
     */
    private fun downloadWithMirror(context: Context, originalUrl: String, fileName: String) {
        Thread {
            for (mirror in MIRROR_PREFIXES) {
                val proxyUrl = if (mirror.isEmpty()) originalUrl else "$mirror$originalUrl"
                val mirrorName = if (mirror.isEmpty()) "直连" else mirror.trimEnd('/')
                postLog("尝试下载: $mirrorName")

                try {
                    // 先测试连接是否可用（HEAD 请求）
                    val testConn = URL(proxyUrl).openConnection() as HttpURLConnection
                    testConn.requestMethod = "HEAD"
                    testConn.connectTimeout = 8000
                    testConn.readTimeout = 8000
                    testConn.instanceFollowRedirects = true

                    val code = testConn.responseCode
                    testConn.disconnect()

                    if (code in 200..399) {
                        postLog("镜像可用: $mirrorName (HTTP $code)")
                        downloadAndInstall(context, proxyUrl, fileName)
                        return@Thread
                    } else {
                        postLog("镜像不可用: $mirrorName (HTTP $code)")
                    }
                } catch (e: Exception) {
                    postLog("镜像连接失败: $mirrorName (${e.message})")
                }
            }

            postLog("所有下载源均失败")
            postStatus(UpdateStatus.ERROR)
        }.start()
    }

    /**
     * 使用 DownloadManager 下载 APK 并安装
     * 保存到公共 Downloads 目录，卸载 APP 不会被删除
     */
    private fun downloadAndInstall(context: Context, url: String, fileName: String) {
        postStatus(UpdateStatus.DOWNLOADING)
        postLog("开始下载: $url")

        try {
            // 删除旧文件（公共 Downloads 目录）
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val existingFile = File(publicDownloads, fileName)
            if (existingFile.exists()) existingFile.delete()

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("WeChatBot 更新")
                .setDescription("正在下载新版本...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            // 监听下载完成
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        context.unregisterReceiver(this)
                        postLog("下载完成，文件在: ${publicDownloads.absolutePath}/$fileName")
                        postStatus(UpdateStatus.DOWNLOADED)

                        // 通过 DownloadManager 获取下载文件的 URI 来安装
                        val downloadUri = downloadManager.getUriForDownloadedFile(downloadId)
                        if (downloadUri != null) {
                            installApk(context, downloadUri)
                        } else {
                            // 兜底：用文件路径
                            val file = File(publicDownloads, fileName)
                            installApkFromFile(context, file)
                        }
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
     * 通过 content URI 安装 APK（DownloadManager 返回的 URI）
     */
    private fun installApk(context: Context, uri: Uri) {
        try {
            postLog("正在启动安装...")
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

    /**
     * 通过 File 安装 APK（兜底方案）
     */
    private fun installApkFromFile(context: Context, file: File) {
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
