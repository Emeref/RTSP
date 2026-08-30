package com.example.rtsptoyoutubertmp

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig

class StreamingService : Service() {

    companion object {
        const val CHANNEL_ID = "StreamingServiceChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RTSP = "EXTRA_RTSP"
        const val EXTRA_RTMP = "EXTRA_RTMP"
        
        // Bufor logów dostępny dla MainActivity
        val logBuffer = mutableListOf<String>()
    }

    override fun onCreate() {
        super.onCreate()
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Streaming Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)

        FFmpegKitConfig.enableLogCallback { log ->
            synchronized(logBuffer) {
                logBuffer.add(log.message)
                if (logBuffer.size > 200) logBuffer.removeAt(0) // Ograniczenie pamięci
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rtspUrl = intent.getStringExtra(EXTRA_RTSP) ?: ""
                val rtmpUrl = intent.getStringExtra(EXTRA_RTMP) ?: ""
                startForegroundServiceWithNotification()
                startFFmpeg(rtspUrl, rtmpUrl)
            }
            ACTION_STOP -> {
                FFmpegKit.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startFFmpeg(rtspUrl: String, rtmpUrl: String) {
        synchronized(logBuffer) { logBuffer.clear() }
        val ffmpegCommand = "-loglevel verbose -rtsp_transport tcp -thread_queue_size 1024 -i \"$rtspUrl\" -f lavfi -i anullsrc=r=44100:cl=stereo -map 0:v -map 1:a -c:v libx264 -profile:v main -pix_fmt yuv420p -b:v 8000k -maxrate 8000k -bufsize 16000k -g 50 -preset ultrafast -tune zerolatency -c:a aac -b:a 128k -shortest -f flv \"$rtmpUrl\""
        
        Thread {
            FFmpegKit.execute(ffmpegCommand)
            synchronized(logBuffer) { logBuffer.add("\n--- Streaming zakończony ---") }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
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

    override fun onBind(intent: Intent?): IBinder? = null
}