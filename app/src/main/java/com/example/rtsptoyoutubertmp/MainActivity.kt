package com.example.rtsptoyoutubertmp

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var rtspEditText: TextInputEditText
    private lateinit var rtmpEditText: TextInputEditText
    private lateinit var startButton: Button
    private lateinit var copyLogsButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var autoScrollCheckBox: CheckBox

    private var isStreaming = false

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

        // Wypełnienie wartościami domyślnymi z BuildConfig (zdefiniowanymi w local.properties)
        if (BuildConfig.RTSP_URL.isNotEmpty()) {
            rtspEditText.setText(BuildConfig.RTSP_URL)
        }
        if (BuildConfig.RTMP_URL.isNotEmpty()) {
            rtmpEditText.setText(BuildConfig.RTMP_URL)
        }

        startButton.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        copyLogsButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("FFmpeg Logs", logTextView.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logi skopiowane do schowka", Toast.LENGTH_SHORT).show()
        }

        // Disable auto-scroll if user touches the log view
        logScrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                autoScrollCheckBox.isChecked = false
            }
            false
        }
    }

    private fun startStreaming() {
        val rtspUrl = rtspEditText.text.toString()
        val rtmpUrl = rtmpEditText.text.toString()

        if (rtspUrl.isEmpty() || rtmpUrl.isEmpty()) {
            Toast.makeText(this, "Proszę podać oba adresy URL", Toast.LENGTH_SHORT).show()
            return
        }

        // Updated FFmpeg command with silent audio generator (anullsrc) for YouTube compatibility
        // -f lavfi -i anullsrc=r=44100:cl=stereo -map 0:v -map 1:a
        val ffmpegCommand = "-rtsp_transport tcp -i \"$rtspUrl\" -f lavfi -i anullsrc=r=44100:cl=stereo -map 0:v -map 1:a -c:v libx264 -profile:v main -pix_fmt yuv420p -b:v 8000k -maxrate 8000k -bufsize 16000k -g 50 -preset ultrafast -tune zerolatency -c:a aac -b:a 128k -shortest -f flv \"$rtmpUrl\""

        logTextView.text = "Uruchamianie FFmpeg...\n"
        statusTextView.text = "Status: Uruchomiono"
        isStreaming = true
        startButton.text = "Stop Streaming"

        FFmpegKit.executeAsync(ffmpegCommand, { session ->
            val returnCode = session.returnCode
            runOnUiThread {
                if (ReturnCode.isSuccess(returnCode)) {
                    Log.d(TAG, "Streaming zakończony sukcesem")
                    statusTextView.text = "Status: Zakończono sukcesem"
                } else if (ReturnCode.isCancel(returnCode)) {
                    Log.d(TAG, "Streaming anulowany")
                    statusTextView.text = "Status: Anulowano"
                } else {
                    Log.e(TAG, "Błąd streamingu: ${session.failStackTrace}")
                    statusTextView.text = "Status: Błąd!"
                }
                isStreaming = false
                startButton.text = "Start Streaming"
            }
        }, { log ->
            runOnUiThread {
                logTextView.append(log.message + "\n")
                if (autoScrollCheckBox.isChecked) {
                    logScrollView.post {
                        logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
                    }
                }
                
                // Aktualizacja statusu na podstawie logów, jeśli to konieczne
                if (log.message.contains("Output stream") || log.message.contains("frame=")) {
                    if (statusTextView.text != "Status: Uruchomiono") {
                        statusTextView.text = "Status: Uruchomiono"
                    }
                }
            }
        }, { statistics ->
            // Tu można dodać statystyki
        })
    }

    private fun stopStreaming() {
        FFmpegKit.cancel()
        isStreaming = false
        startButton.text = "Start Streaming"
        statusTextView.text = "Status: Zatrzymano"
    }
}
