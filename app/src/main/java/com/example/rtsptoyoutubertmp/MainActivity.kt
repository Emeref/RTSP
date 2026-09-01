package com.example.rtsptoyoutubertmp

import android.annotation.SuppressLint
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var rtspEditText: TextInputEditText
    private lateinit var rtmpEditText: TextInputEditText
    private lateinit var startButton: Button
    private lateinit var viewLogsButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private var isStreaming = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("streaming_prefs", Context.MODE_PRIVATE) }

    private val logUpdater = object : Runnable {
        override fun run() {
            synchronized(StreamingService.logBuffer) {
                val logBuilder = StringBuilder()
                for (log in StreamingService.logBuffer) {
                    logBuilder.append(log).append("\n")
                }
                logTextView.text = logBuilder.toString()
                logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rtspEditText = findViewById(R.id.rtspEditText)
        rtmpEditText = findViewById(R.id.rtmpEditText)
        startButton = findViewById(R.id.startStreamButton)
        viewLogsButton = findViewById(R.id.viewLogsButton)
        statusTextView = findViewById(R.id.statusTextView)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        rtspEditText.setText(prefs.getString("last_rtsp", ""))
        rtmpEditText.setText(prefs.getString("last_rtmp", ""))

        startButton.setOnClickListener {
            if (isStreaming) stopStreaming() else startStreaming()
        }

        viewLogsButton.setOnClickListener { showLogsList() }
    }

    private fun showLogsList() {
        val logDir = File(filesDir, "logs")
        if (!logDir.exists()) logDir.mkdir()
        val files = logDir.listFiles() ?: arrayOf()
        val fileNames = files.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Wybierz log")
            .setItems(fileNames) { _, which ->
                val content = files[which].readText()
                showLogContent(fileNames[which], content)
            }
            .show()
    }

    private fun showLogContent(fileName: String, content: String) {
        val textView = TextView(this)
        textView.text = content
        textView.setPadding(16, 16, 16, 16)
        AlertDialog.Builder(this)
            .setTitle(fileName)
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("Zamknij", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        handler.post(logUpdater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(logUpdater)
    }

    private fun startStreaming() {
        val rtsp = rtspEditText.text.toString()
        val rtmp = rtmpEditText.text.toString()
        if (rtsp.isEmpty() || rtmp.isEmpty()) {
            Toast.makeText(this, "Podaj URL", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit().putString("last_rtsp", rtsp).putString("last_rtmp", rtmp).apply()
        
        val logFileName = "log_${System.currentTimeMillis()}.txt"
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RTSP, rtsp)
            putExtra(StreamingService.EXTRA_RTMP, rtmp)
            putExtra(StreamingService.EXTRA_LOG_FILE, logFileName)
        }
        startForegroundService(intent)
        isStreaming = true
        startButton.text = "Stop Streaming"
        statusTextView.text = "Status: Uruchomiono"
    }

    private fun stopStreaming() {
        startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_STOP })
        isStreaming = false
        startButton.text = "Start Streaming"
        statusTextView.text = "Status: Zatrzymano"
    }
}