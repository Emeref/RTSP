package com.example.rtsptoyoutubertmp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rtsp = intent.getStringExtra(StreamingService.EXTRA_RTSP)
        val rtmp = intent.getStringExtra(StreamingService.EXTRA_RTMP)
        val logFile = intent.getStringExtra(StreamingService.EXTRA_LOG_FILE)
        val duration = intent.getLongExtra("EXTRA_DURATION", 0)

        // Uruchomienie serwisu
        val serviceIntent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RTSP, rtsp)
            putExtra(StreamingService.EXTRA_RTMP, rtmp)
            putExtra(StreamingService.EXTRA_LOG_FILE, logFile)
            putExtra("EXTRA_DURATION", duration) // Upewnienie się, że duration jest przekazane
        }
        context.startForegroundService(serviceIntent)

        // Zaplanowanie kolejnego powtórzenia na jutro
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextTrigger = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            intent.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextTrigger,
            pendingIntent
        )
    }
}
