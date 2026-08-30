package com.example.rtsptoyoutubertmp

import android.annotation.SuppressLint
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private var lastLogIndex = 0

    // Runnable odpytujący logi z bufora usługi
    private val logUpdater = object : Runnable {
        override fun run() {
            synchronized(StreamingService.logBuffer) {
                // Jeśli w buforze jest mniej danych niż ostatnio (np. wyczyszczono), zresetuj
                if (StreamingService.logBuffer.size < lastLogIndex) {
                    lastLogIndex = 0
                    logTextView.text = ""
                }

                while (lastLogIndex < StreamingService.logBuffer.size) {
                    logTextView.append(StreamingService.logBuffer[lastLogIndex] + "\n")
                    lastLogIndex++
                }

                // Ograniczenie wyświetlanych logów do ostatnich 200 wierszy
                val lines = logTextView.text.split("\n")
                if (lines.size > 200) {
                    logTextView.text = lines.takeLast(200).joinToString("\n")
                }
            }
            if (autoScrollCheckBox.isChecked && lastLogIndex > 0) {
                logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            handler.postDelayed(this, 500)
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

        if (BuildConfig.RTSP_URL.isNotEmpty()) {
            rtspEditText.setText(BuildConfig.RTSP_URL)
        }
        if (BuildConfig.RTMP_URL.isNotEmpty()) {
            rtmpEditText.setText(BuildConfig.RTMP_URL)
        }

        startButton.setOnClickListener {
            if (isStreaming) stopStreaming() else startStreaming()
        }

        copyLogsButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("FFmpeg Logs", logTextView.text))
            Toast.makeText(this, "Skopiowano", Toast.LENGTH_SHORT).show()
        }

        logScrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                autoScrollCheckBox.isChecked = false
            }
            false
        }
    }

    override fun onResume() {
        super.onResume()
        lastLogIndex = 0
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

        logTextView.text = "--- Uruchamianie streamingu ---\n"
        lastLogIndex = 0
        
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