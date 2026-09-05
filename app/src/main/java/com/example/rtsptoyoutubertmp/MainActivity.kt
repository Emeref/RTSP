package com.example.rtsptoyoutubertmp

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var rtspEditText: TextInputEditText
    private lateinit var rtmpEditText: TextInputEditText
    private lateinit var startButton: Button
    private lateinit var tasksContainer: LinearLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rtspEditText = findViewById(R.id.rtspEditText)
        rtmpEditText = findViewById(R.id.rtmpEditText)
        startButton = findViewById(R.id.startStreamButton)
        tasksContainer = findViewById(R.id.tasksContainer)
        statusTextView = findViewById(R.id.statusTextView)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        rtspEditText.setText(prefs.getString("last_rtsp", ""))
        rtmpEditText.setText(prefs.getString("last_rtmp", ""))
        loadSavedTasks()

        findViewById<Button>(R.id.btnAddTask).setOnClickListener { addTaskRow(null, null) }
        startButton.setOnClickListener { if (isStreaming) stopStreaming() else startStreaming() }
        findViewById<Button>(R.id.viewLogsButton).setOnClickListener { showLogsList() }
    }

    private fun addTaskRow(timeMillis: Long?, durationMin: String?) {
        val row = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL 
            setPadding(0, 8, 0, 8)
        }
        val timeBtn = Button(this).apply { 
            text = if (timeMillis != null) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis)) else "Godzina"
            tag = timeMillis 
        }
        val durationEdit = EditText(this).apply { 
            hint = "Minuty"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (durationMin != null) setText(durationMin)
        }
        val deleteBtn = Button(this).apply { text = "X" }
        
        timeBtn.setOnClickListener {
            val cal = Calendar.getInstance()
            TimePickerDialog(this, { _, h, m ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); cal.set(Calendar.SECOND, 0)
                timeBtn.tag = cal.timeInMillis
                timeBtn.text = String.format(Locale.getDefault(), "%02d:%02d", h, m)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        
        deleteBtn.setOnClickListener { tasksContainer.removeView(row) }
        
        row.addView(timeBtn); row.addView(durationEdit); row.addView(deleteBtn)
        tasksContainer.addView(row)
    }

    private fun saveTasks() {
        val taskSet = mutableSetOf<String>()
        for (i in 0 until tasksContainer.childCount) {
            val row = tasksContainer.getChildAt(i) as LinearLayout
            val btn = row.getChildAt(0) as Button
            val edit = row.getChildAt(1) as EditText
            if (btn.tag != null && edit.text.isNotEmpty()) {
                taskSet.add("${btn.tag}|${edit.text}")
            }
        }
        prefs.edit().putStringSet("saved_tasks", taskSet).apply()
    }

    private fun loadSavedTasks() {
        val tasks = prefs.getStringSet("saved_tasks", emptySet()) ?: return
        tasks.forEach {
            val parts = it.split("|")
            if (parts.size == 2) addTaskRow(parts[0].toLong(), parts[1])
        }
    }

    private fun startStreaming() {
        val rtsp = rtspEditText.text.toString()
        val rtmp = rtmpEditText.text.toString()
        val tasks = arrayListOf<String>()

        for (i in 0 until tasksContainer.childCount) {
            val row = tasksContainer.getChildAt(i) as LinearLayout
            val btn = row.getChildAt(0) as Button
            val edit = row.getChildAt(1) as EditText
            if (btn.tag != null && edit.text.isNotEmpty()) {
                val start = btn.tag as Long
                val dur = edit.text.toString().toLong() * 60000
                tasks.add("$start:$dur")
            }
        }

        if (rtsp.isEmpty() || rtmp.isEmpty() || tasks.isEmpty()) {
            Toast.makeText(this, "Wypełnij URL i dodaj zadania", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit()
            .putString("last_rtsp", rtsp)
            .putString("last_rtmp", rtmp)
            .apply()
        saveTasks()

        val logFileName = "log_${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.txt"
        startForegroundService(Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RTSP, rtsp)
            putExtra(StreamingService.EXTRA_RTMP, rtmp)
            putExtra(StreamingService.EXTRA_LOG_FILE, logFileName)
            putStringArrayListExtra(StreamingService.EXTRA_TASKS, tasks)
        })
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
    
    private fun showLogsList() { /* ... */ }
    override fun onResume() { super.onResume(); handler.post(logUpdater) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(logUpdater) }
}
