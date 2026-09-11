package com.example.rtsptoyoutubertmp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rtsp = intent.getStringExtra(StreamingService.EXTRA_RTSP) ?: return
        val rtmp = intent.getStringExtra(StreamingService.EXTRA_RTMP) ?: return
        val duration = intent.getLongExtra(StreamingService.EXTRA_DURATION, 0L)
        val requestCode = intent.getIntExtra(StreamingService.EXTRA_REQUEST_CODE, -1)
        val targetTime = intent.getLongExtra(StreamingService.EXTRA_TARGET_TIME, 0L)

        val now = System.currentTimeMillis()
        
        // Zabezpieczenie przed przedwczesnym wywołaniem (tolerancja 30s)
        if (targetTime > 0 && targetTime > now + 30000) {
            StreamingService.addLog(context, "AlarmReceiver: Otrzymano sygnał zbyt wcześnie. Ignoruję. (Target: $targetTime, Now: $now)")
            return
        }

        // Uruchomienie serwisu
        if (intent.action == StreamingService.ACTION_START) {
            val serviceIntent = Intent(context, StreamingService::class.java).apply {
                action = StreamingService.ACTION_START
                putExtra(StreamingService.EXTRA_RTSP, rtsp)
                putExtra(StreamingService.EXTRA_RTMP, rtmp)
                putExtra(StreamingService.EXTRA_DURATION, duration)
            }
            context.startForegroundService(serviceIntent)
        }

        // Zaplanowanie kolejnego powtórzenia na jutro (dokładnie +24h od planowanego czasu)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextTrigger = if (targetTime > 0) {
            targetTime + AlarmManager.INTERVAL_DAY
        } else {
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }

        val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RTSP, rtsp)
            putExtra(StreamingService.EXTRA_RTMP, rtmp)
            putExtra(StreamingService.EXTRA_DURATION, duration)
            putExtra(StreamingService.EXTRA_REQUEST_CODE, requestCode)
            putExtra(StreamingService.EXTRA_TARGET_TIME, nextTrigger)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            if (requestCode != -1) requestCode else intent.hashCode(),
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextTrigger,
            pendingIntent
        )
        
        StreamingService.addLog(context, "AlarmReceiver: Zaplanowano powtórzenie na jutro: $nextTrigger")
    }
}
