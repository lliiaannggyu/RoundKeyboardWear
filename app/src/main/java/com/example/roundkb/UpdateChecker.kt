package com.example.roundkb

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内检查更新：拉取 GitHub Releases 最新版，比较 versionCode，
 * 下载 APK 到缓存目录后经 FileProvider 拉起系统安装器。
 *
 * 流程（设置页「检查更新」按钮）：
 *  1. GET releases/latest → tag_name / assets；
 *  2. 最新 tag 的 versionCode > 本机 → 提示「发现新版本 vX.Y，点击下载」；
 *  3. 再次点击 → 后台下载 APK（实时回调百分比）→ 完成后回调「安装更新」；
 *  4. 安装：未授权「安装未知应用」时先跳系统授权页，否则直接拉起安装器。
 */
object UpdateChecker {

    private const val API_LATEST =
        "https://api.github.com/repos/lliiaannggyu/RoundKeyboardWear/releases/latest"

    /** 当前状态：null = 空闲 / 值 = 待下载的 APK 下载地址（按钮文案随之变化） */
    @Volatile var pendingUrl: String? = null

    /**
     * 状态机入口（设置页按钮点击），回调在主线程：
     * 检查更新 → 下载更新 → 安装更新。
     */
    fun tap(activity: ComponentActivity, onResult: (String) -> Unit) {
        val f = apkFile
        val url = pendingUrl
        when {
            f != null && url == null -> {
                val err = install(activity)
                onResult(err ?: "正在打开安装器…")
            }
            url != null -> download(activity, url, onResult)
            else -> check(activity, onResult)
        }
    }

    /** 按钮文案随状态变化 */
    fun buttonLabel(): String = when {
        apkFile != null && pendingUrl == null -> "安装更新"
        pendingUrl != null -> "下载更新"
        else -> "检查更新"
    }

    /** 第 1 步：查询最新版本 */
    private fun check(activity: ComponentActivity, onResult: (String) -> Unit) {
        Thread {
            var msg = "检查更新失败：无网络连接"
            try {
                val (code, body) = httpGet(API_LATEST)
                if (code == 200) {
                    val j = JSONObject(body)
                    val tag = j.optString("tag_name", "")
                    val remoteVer = tag.removePrefix("v").toDoubleOrNull()
                    val local = activity.packageManager
                        .getPackageInfo(activity.packageName, 0).longVersionCode
                    var apkUrl: String? = null
                    val assets = j.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            val name = a.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = a.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    when {
                        remoteVer == null || apkUrl == null ->
                            msg = "仓库中没有可用的新版本"
                        remoteVer > local -> {
                            pendingUrl = apkUrl
                            msg = "发现新版本 v$remoteVer\n点击「下载更新」开始下载"
                        }
                        else -> msg = "已是最新版本 v$local"
                    }
                } else {
                    msg = "检查更新失败：服务返回 $code"
                }
            } catch (e: Exception) {
                msg = "检查更新失败：${e.message ?: "网络错误"}"
            }
            activity.runOnUiThread { onResult(msg) }
        }.start()
    }

    /** 第 2 步：下载 APK 到缓存目录 */
    private fun download(activity: ComponentActivity, url: String, onResult: (String) -> Unit) {
        Thread {
            var msg = "下载失败：无网络连接"
            try {
                val tmp = File(activity.cacheDir, "update.apk")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.connect()
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct % 10 == 0) {
                                    activity.runOnUiThread { onResult("下载中 $pct%") }
                                }
                            }
                        }
                    }
                }
                pendingUrl = null
                apkFile = tmp
                msg = "下载完成\n点击「安装更新」立即安装"
            } catch (e: Exception) {
                msg = "下载失败：${e.message ?: "网络错误"}"
            }
            activity.runOnUiThread { onResult(msg) }
        }.start()
    }

    /** 下载完成的 APK（等待用户点「安装更新」） */
    @Volatile var apkFile: File? = null

    /** 第 3 步：拉起系统安装器（未授权安装未知应用时先跳授权页） */
    fun install(activity: ComponentActivity): String? {
        val f = apkFile ?: return "请先下载更新包"
        if (!f.exists() || f.length() < 1024) {
            apkFile = null
            return "更新包损坏，请重新检查更新"
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            try {
                activity.startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")))
                return "请先允许「安装未知应用」，返回后再点安装"
            } catch (_: Exception) {
                return "请在系统设置中允许本应用安装未知应用后重试"
            }
        }
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", f)
        val it = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            activity.startActivity(it)
            null
        } catch (e: ActivityNotFoundException) {
            "找不到可用的安装器"
        }
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "RoundKeyboardWear")
        try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return conn.responseCode to body
        } finally {
            conn.disconnect()
        }
    }
}
