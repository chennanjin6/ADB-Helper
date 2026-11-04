package com.yangyx.adbhelper.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.yangyx.adbhelper.MainActivity
import com.yangyx.adbhelper.R
import com.yangyx.adbhelper.adb.AdbConnection
import com.yangyx.adbhelper.ui.AdbViewModel
import com.yangyx.adbhelper.widget.WidgetStateHolder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BridgeService : Service() {
    companion object {
        private const val BASE = "https://huawei-adb-bridge.chennanjin6.workers.dev"
        private const val CHANNEL = "cloudflare_adb_bridge"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, BridgeService::class.java))
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(CHANNEL, "Cloudflare ADB 桥接", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(62110, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ADB Helper 云端桥接运行中").setContentText("复用当前 ADB 会话")
            .setContentIntent(pi).setOngoing(true).build())
        if (pollJob?.isActive != true) pollJob = scope.launch { loop() }
        return START_STICKY
    }
    private suspend fun loop() {
        while (isActive) {
            try {
                val token = BridgePrefs.getToken(this)
                val conn = WidgetStateHolder.activeConnection ?: AdbViewModel.currentInstance?.activeConnection
                if (!token.isNullOrBlank() && conn?.isConnected == true) {
                    val taskText = request("$BASE/phone/poll", token, null)
                    if (!taskText.isNullOrBlank()) {
                        val envelope = JSONObject(taskText)
                        val task = envelope.optJSONObject("task") ?: envelope
                        val id = task.optString("id")
                        val action = task.optString("action")
                        if (id.isNotBlank()) executeTask(id, action, conn, token)
                    }
                }
            } catch (_: CancellationException) { throw CancellationException() }
            catch (_: Exception) { }
            delay(2500)
        }
    }
    private fun executeTask(id: String, action: String, conn: AdbConnection, token: String) {
        val cmd = when (action) {
            "device_info" -> "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk"
            "root_check" -> "id; su -c id 2>&1"
            "packages_list" -> "pm list packages -3"
            "battery_info" -> "dumpsys battery"
            else -> null
        }
        val output = if (cmd == null) "拒绝执行：不支持的操作 $action" else try {
            conn.executeShell(cmd, 15000).take(30000)
        } catch (e: Exception) { "执行失败：${e.message}" }
        request("$BASE/phone/result", token, JSONObject().put("id", id).put("result", output)
            .put("success", cmd != null && !output.startsWith("执行失败：")).toString())
    }
    private fun request(url: String, token: String, body: String?): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = if (body == null) "GET" else "POST"
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally { c.disconnect() }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
