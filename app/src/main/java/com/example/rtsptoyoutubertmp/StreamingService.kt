package com.example.rtsptoyoutubertmp

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
        const val EXTRA_LOG_FILE = "EXTRA_LOG_FILE"
        const val EXTRA_TASKS = "EXTRA_TASKS"
        
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
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_INFO)
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rtsp = intent.getStringExtra(EXTRA_RTSP) ?: ""
                val rtmp = intent.getStringExtra(EXTRA_RTMP) ?: ""
                val duration = intent.getLongExtra("EXTRA_DURATION", 0)
                
                acquireLocks()
                startForegroundServiceWithNotification()
                
                startStreamingTask(rtsp, rtmp, duration)
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startStreamingTask(rtsp: String, rtmp: String, duration: Long) {
        isStreaming = true
        sendStateBroadcast(true)
        addLog(this, "START: Rozpoczynam stream, czas trwania: ${duration / 60000} min")
        startFFmpeg(rtsp, rtmp)
        
        handler.postDelayed({
            if (isStreaming) {
                stopStreamingInternal()
                isStreaming = false
                sendStateBroadcast(false)
                addLog(this, "STOP: Zakończono sesję zgodnie z harmonogramem")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, duration)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamingService::Wakelock")
        wakeLock?.acquire()
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "StreamingService::WifiLock")
        wifiLock?.acquire()
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun startFFmpeg(rtspUrl: String, rtmpUrl: String) {
        val ffmpegCommand = "-re -rtsp_transport tcp -i \"$rtspUrl\" -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -c:v libx264 -preset veryfast -b:v 8000k -maxrate 8000k -bufsize 16000k -pix_fmt yuv420p -g 50 -c:a aac -b:a 128k -map 0:v -map 1:a -shortest -f flv \"$rtmpUrl\""
        
        val session = FFmpegKit.executeAsync(ffmpegCommand) { session ->
            addLog(this, "FFmpeg finished with state ${session.state}")
            isStreaming = false
            sendStateBroadcast(false)
        }
        currentSessionId = session.sessionId
        addLog(this, "FFmpeg session started with ID: $currentSessionId")
    }

    private fun stopStreamingInternal() {
        addLog(this, "Zatrzymywanie sesji FFmpeg...")
        currentSessionId?.let {
            FFmpegKit.cancel(it)
        }
        FFmpegKit.cancel() 
    }

    private fun stopStreaming() {
        stopStreamingInternal()
        isStreaming = false
        sendStateBroadcast(false)
        releaseLocks()
        addLog(this, "STOP: Zatrzymano ręcznie")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(CHANNEL_ID, "Streaming Service Channel", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    private fun startForegroundServiceWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming w toku")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
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
