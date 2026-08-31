package com.example.rtsptoyoutubertmp

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import java.util.ArrayDeque

class StreamingService : Service() {

    companion object {
        const val CHANNEL_ID = "StreamingServiceChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RTSP = "EXTRA_RTSP"
        const val EXTRA_RTMP = "EXTRA_RTMP"
        
        private const val MAX_LOGS = 50
        val logBuffer = ArrayDeque<String>(MAX_LOGS)

        fun addLog(message: String) {
            synchronized(logBuffer) {
                if (logBuffer.size >= MAX_LOGS) {
                    logBuffer.pollFirst()
                }
                logBuffer.addLast(message)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Logowanie FFmpeg ustawione na błędy, aby nie zaśmiecać bufora
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_ERROR)
        FFmpegKitConfig.enableLogCallback { log ->
            addLog("FFmpeg Error: ${log.message.trim()}")
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rtspUrl = intent.getStringExtra(EXTRA_RTSP) ?: ""
                val rtmpUrl = intent.getStringExtra(EXTRA_RTMP) ?: ""
                acquireLocks()
                startForegroundServiceWithNotification()
                
                addLog("START: Streaming rozpoczęty (Bitrate: 8000k)")
                startFFmpeg(rtspUrl, rtmpUrl)
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
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
        
        Thread {
            val session = FFmpegKit.execute(ffmpegCommand)
            if (session.returnCode.isValueSuccess) {
                addLog("STOP: Streaming zakończony pomyślnie")
            } else {
                addLog("STOP: Błąd streamingu: ${session.failStackTrace?.take(100)}")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    private fun stopStreaming() {
        FFmpegKit.cancel()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        addLog("STOP: Zatrzymano ręcznie")
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(CHANNEL_ID, "Streaming Service Channel", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    private fun startForegroundServiceWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming w toku")
            .setContentText("Przesyłanie strumienia RTSP do RTMP...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
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