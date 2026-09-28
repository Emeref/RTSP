package com.example.rtsptoyoutubertmp

import android.R
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class StreamingService : Service() {

    companion object {
        const val CHANNEL_ID = "StreamingServiceChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RTSP = "EXTRA_RTSP"
        const val EXTRA_RTMP = "EXTRA_RTMP"
        const val EXTRA_DURATION = "EXTRA_DURATION"
        const val EXTRA_REQUEST_CODE = "EXTRA_REQUEST_CODE"
        const val EXTRA_TARGET_TIME = "EXTRA_TARGET_TIME"
        
        private const val MAX_LOGS = 50
        val logBuffer = ArrayDeque<String>(MAX_LOGS)

        fun addLog(context: Context, message: String) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val formatted = "[$timestamp] $message"
            
            synchronized(logBuffer) {
                if (logBuffer.size >= MAX_LOGS) {
                    logBuffer.pollFirst()
                }
                logBuffer.addLast(formatted)
            }

            // Zapis do pliku
            val logDir = File(context.filesDir, "logs")
            if (!logDir.exists()) logDir.mkdir()
            val logFile = File(logDir, "log_${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.txt")
            logFile.appendText(formatted + "\n")
        }

        /**
         * Formats a duration in milliseconds into a human-readable format (e.g., "5h 48m" or "30m").
         */
        fun formatDuration(millis: Long): String {
            val hours = millis / 3600000L
            val minutes = (millis % 3600000L) / 60000
            return if (hours > 0) {
                "${hours}h ${minutes}m"
            } else {
                "${minutes}m"
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isStreaming = false
    private var currentSessionId: Long? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun sendStateBroadcast(streaming: Boolean) {
        val intent = Intent("com.example.rtsptoyoutubertmp.STREAM_STATE")
        intent.putExtra("is_streaming", streaming)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Poziom logowania AV_LOG_WARNING
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_WARNING)
        FFmpegKitConfig.enableLogCallback { ffmpegLog ->
            val msg = ffmpegLog.message
            // Zapisujemy tylko błędy z FFmpeg, odrzucając klatki i komunikaty postępu
            if (msg.contains("error", ignoreCase = true) || msg.contains("failed", ignoreCase = true) || msg.contains("invalid", ignoreCase = true)) {
                addLog(this, "FFmpeg Err: ${msg.trim()}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        if (action == ACTION_STOP) {
            stopStreaming()
            return START_NOT_STICKY
        }
        
        if (action == ACTION_START) {
            val rtsp = intent.getStringExtra(EXTRA_RTSP) ?: ""
            val rtmp = intent.getStringExtra(EXTRA_RTMP) ?: ""
            val duration = intent.getLongExtra(EXTRA_DURATION, 0L)
            
            if (rtsp.isNotEmpty() && rtmp.isNotEmpty()) {
                addLog(this, "Service: Otrzymano żądanie startu. RTSP=$rtsp, RTMP=$rtmp, Czas=$duration")
                acquireLocks()
                startForegroundServiceWithNotification()
                startStreamingTask(rtsp, rtmp, duration)
            }
            return START_NOT_STICKY
        }
        
        return START_NOT_STICKY
    }

    private fun startStreamingTask(rtsp: String, rtmp: String, duration: Long) {
        if (isStreaming) {
            addLog(this, "Service: Stream już działa, ignoruję żądanie.")
            return
        }
        
        isStreaming = true
        sendStateBroadcast(true)
        addLog(this, "START: Rozpoczynam stream, czas trwania: ${duration / 60000} min")
        startFFmpeg(rtsp, rtmp)
        
        if (duration > 0) {
            handler.postDelayed({
                addLog(this, "STOP: Zakończono sesję zgodnie z harmonogramem ($duration ms)")
                stopStreamingInternal()
            }, duration)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamingService::Wakelock")
        wakeLock?.acquire()
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL
        }
        wifiLock = wifiManager.createWifiLock(lockType, "StreamingService::WifiLock")
        wifiLock?.acquire()
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun startFFmpeg(rtspUrl: String, rtmpUrl: String) {
        val ffmpegArguments = arrayOf(
            "-nostats",
            "-loglevel", "error",
            "-rtsp_transport", "tcp",
            "-i", rtspUrl,
            "-f", "lavfi",
            "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
            "-c:v", "copy",
            "-bsf:v", "dump_extra",
            "-c:a", "aac",
            "-b:a", "128k",
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-f", "flv",
            "-flvflags", "no_duration_filesize",
            rtmpUrl
        )

        val session = FFmpegKit.executeWithArgumentsAsync(ffmpegArguments) { session ->
            val state = session.state
            val returnCode = session.returnCode
            
            if (returnCode?.isValueSuccess == true) {
                addLog(this, "STOP: Stream zakończony pomyślnie")
            } else if (returnCode?.isValueCancel == true) {
                addLog(this, "STOP: Stream zatrzymany/anulowany")
            } else {
                addLog(this, "BŁĄD: Stream przerwany z błędem (Kod=$returnCode, Stan=$state)")
                val failLogs = session.allLogsAsString
                    .lines()
                    .filter { line -> line.isNotBlank() && !line.contains("frame=") && !line.contains("q=-1.0") }
                    .takeLast(10)
                    .joinToString("\n")
                if (failLogs.isNotEmpty()) {
                    addLog(this, "Szczegóły błędu:\n$failLogs")
                }
            }

            isStreaming = false
            sendStateBroadcast(false)
        }
        currentSessionId = session.sessionId
    }

    private fun stopStreamingInternal() {
        addLog(this, "Zatrzymywanie sesji FFmpeg...")
        currentSessionId?.let {
            FFmpegKit.cancel(it)
        }
        FFmpegKit.cancel() 
        isStreaming = false
        sendStateBroadcast(false)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopStreaming() {
        addLog(this, "STOP: Zatrzymano ręcznie")
        stopStreamingInternal()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(CHANNEL_ID, "Streaming Service Channel", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    private fun startForegroundServiceWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming w toku")
            .setSmallIcon(R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
