package com.example.rtsptoyoutubertmp

import android.annotation.SuppressLint
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var rtspEditText: TextInputEditText
    private lateinit var rtmpEditText: TextInputEditText
    private lateinit var startButton: Button
    private lateinit var copyLogsButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var autoScrollCheckBox: CheckBox

    private var isStreaming = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("streaming_prefs", Context.MODE_PRIVATE) }

    private val logUpdater = object : Runnable {
        override fun run() {
            synchronized(StreamingService.logBuffer) {
                val currentText = logTextView.text.toString()
                val logBuilder = StringBuilder()
                for (log in StreamingService.logBuffer) {
                    logBuilder.append(log).append("\n")
                }
                val newText = logBuilder.toString()
                
                // Aktualizuj tylko jeśli tekst się zmienił
                if (currentText != newText) {
                    logTextView.text = newText
                    if (autoScrollCheckBox.isChecked) {
                        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
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
        copyLogsButton = findViewById(R.id.copyLogsButton)
        statusTextView = findViewById(R.id.statusTextView)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        autoScrollCheckBox = findViewById(R.id.autoScrollCheckBox)

        rtspEditText.setText(prefs.getString("last_rtsp", ""))
        rtmpEditText.setText(prefs.getString("last_rtmp", ""))

        startButton.setOnClickListener {
            if (isStreaming) stopStreaming() else startStreaming()
        }

        copyLogsButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("FFmpeg Logs", logTextView.text))
            Toast.makeText(this, "Skopiowano", Toast.LENGTH_SHORT).show()
        }
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
        
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RTSP, rtsp)
            putExtra(StreamingService.EXTRA_RTMP, rtmp)
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