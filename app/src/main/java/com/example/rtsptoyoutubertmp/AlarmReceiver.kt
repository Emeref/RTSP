package com.example.rtsptoyoutubertmp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rtsp = intent.getStringExtra(StreamingService.EXTRA_RTSP)
        val rtmp = intent.getStringExtra(StreamingService.EXTRA_RTMP)
        val logFile = intent.getStringExtra(StreamingService.EXTRA_LOG_FILE)
        val duration = intent.getLongExtra("EXTRA_DURATION", 0)

        val serviceIntent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RTSP, rtsp)
            putExtra(StreamingService.EXTRA_RTMP, rtmp)
            putExtra(StreamingService.EXTRA_LOG_FILE, logFile)
            putStringArrayListExtra(StreamingService.EXTRA_TASKS, arrayListOf("${System.currentTimeMillis()}:$duration"))
        }

        context.startForegroundService(serviceIntent)
    }
}
