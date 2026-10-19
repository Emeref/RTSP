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
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class StreamingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Poziom logowania AV_LOG_INFO dla lepszej diagnostyki
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_INFO)
        FFmpegKitConfig.enableLogCallback { ffmpegLog ->
            val msg = ffmpegLog.message
            // Logujemy wszystkie istotne informacje, w tym błędy i ostrzeżenia
            if (msg.contains("error", ignoreCase = true) || 
                msg.contains("failed", ignoreCase = true) || 
                msg.contains("invalid", ignoreCase = true) ||
                msg.contains("connection", ignoreCase = true) ||
                msg.contains("handshake", ignoreCase = true)) {
                addLog(this, "FFmpeg: ${msg.trim()}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        addLog(this, "onStartCommand: action=$action, flags=$flags, startId=$startId")
        
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
                
                // Uruchomienie powiadomienia musi nastąpić natychmiast, aby uniknąć crasha ForegroundServiceDidNotStartInTimeException
                acquireLocks()
                startForegroundServiceWithNotification()

                serviceScope.launch {
                    val app = application as App
                    addLog(this@StreamingService, "Service: Próba odświeżenia tokena przed startem...")
                    val success = app.authManager.refreshAccessToken()
                    
                    val isTokenValid = app.tokenManager.isTokenExpired().not()
                    
                    if (success || isTokenValid) {
                        if (success) {
                            addLog(this@StreamingService, "Service: Token odświeżony pomyślnie.")
                        } else {
                            addLog(this@StreamingService, "Service: Refresh nie udał się, ale stary token jest nadal ważny. Kontynuuję...")
                        }
                        
                        // Uruchomienie streamu musi być na Main Thread
                        withContext(Dispatchers.Main) {
                            startStreamingTask(rtsp, rtmp, duration)
                        }
                    } else {
                        addLog(this@StreamingService, "Service: BRAK ważnego tokena. Nie można rozpocząć streamu.")
                        withContext(Dispatchers.Main) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
            return START_NOT_STICKY
        }
        
        return START_NOT_STICKY
    }

    private fun startStreamingTask(rtsp: String, rtmp: String, duration: Long) {
        // Wyczyść wszelkie stare timery/callbacks przed rozpoczęciem nowego streamu
        handler.removeCallbacksAndMessages(null)

        if (isStreaming) {
            addLog(this, "Service: Stream już działa (isStreaming=true), ignoruję żądanie.")
            return
        }
        
        isStreaming = true
        sendStateBroadcast(true)
        addLog(this, "START: Rozpoczynam stream, czas trwania: ${duration / 60000} min")
        startFFmpeg(rtsp, rtmp)
        
        if (duration > 0) {
            handler.postDelayed({
                addLog(this, "STOP: Zakończono sesję zgodnie z harmonogramem ($duration ms)")
                endSession(false)
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
            "-loglevel", "info",
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
            endSession(false)
        }
        currentSessionId = session.sessionId
    }

    private fun endSession(wasManual: Boolean) {
        if (!isStreaming && currentSessionId == null) return
        
        addLog(this, "EndSession: Rozpoczynam kończenie sesji (manualna=${wasManual})...")
        
        // 1. Zatrzymaj FFmpeg, jeśli jeszcze działa
        currentSessionId?.let {
            addLog(this, "EndSession: Anulowanie sesji FFmpeg ID: $it")
            FFmpegKit.cancel(it)
        }
        FFmpegKit.cancel()
        
        // 2. Wyczyść wszelkie timery i locki
        handler.removeCallbacksAndMessages(null)
        releaseLocks()
        
        isStreaming = false
        sendStateBroadcast(false)
        
        // 3. Zakończ transmisję na YouTube (wykorzystujemy NonCancellable, aby ukończyć nawet po onDestroy)
        serviceScope.launch(NonCancellable) {
            val app = application as App
            val youtubeManager = app.youtubeServiceManager
            
            // Najpierw próbujemy znaleźć aktywną transmisję, by ją zakończyć
            val activeBroadcastId = youtubeManager.findActiveBroadcastId()
            if (activeBroadcastId != null) {
                addLog(this@StreamingService, "EndSession: Znaleziono aktywną transmisję: $activeBroadcastId. Kończenie...")
                val success = youtubeManager.completeBroadcast(activeBroadcastId)
                if (success) {
                    addLog(this@StreamingService, "EndSession: Transmisja na YT zakończona pomyślnie.")
                } else {
                    addLog(this@StreamingService, "EndSession: Błąd zakończenia transmisji na YT.")
                }
            } else {
                addLog(this@StreamingService, "EndSession: Brak aktywnej transmisji do zakończenia.")
            }
            
            // 4. Wyłącz usługę na samym końcu
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopStreaming() {
        addLog(this, "STOP: Zatrzymano ręcznie")
        endSession(true)
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

    private fun sendStateBroadcast(streaming: Boolean) {
        val intent = Intent("com.example.rtsptoyoutubertmp.STREAM_STATE")
        intent.putExtra("is_streaming", streaming)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
