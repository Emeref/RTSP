package com.example.rtsptoyoutubertmp

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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

    private var isScheduled = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("streaming_prefs", Context.MODE_PRIVATE) }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

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

        setupLinkLogging(rtspEditText, "RTSP")
        setupLinkLogging(rtmpEditText, "RTMP")

        rtspEditText.setText(prefs.getString("last_rtsp", ""))
        rtmpEditText.setText(prefs.getString("last_rtmp", ""))
        loadSavedTasks()

        findViewById<Button>(R.id.btnAddTask).setOnClickListener { 
            logAction("Kliknięto: Dodaj zadanie")
            addTaskRow(null, null) 
        }
        startButton.setOnClickListener { 
            logAction("Kliknięto: ${startButton.text}")
            if (isScheduled) stopAll() else scheduleStreaming() 
        }
        findViewById<Button>(R.id.viewLogsButton).setOnClickListener { 
            logAction("Kliknięto: Przeglądaj logi")
            showLogsList() 
        }
    }

    private fun logAction(msg: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        StreamingService.addLog("[$timestamp] UI: $msg")
    }

    private fun setupLinkLogging(edit: TextInputEditText, name: String) {
        edit.addTextChangedListener(object : TextWatcher {
            var oldVal = ""
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { oldVal = s.toString() }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (oldVal != s.toString()) {
                    logAction("Edycja $name: '$oldVal' -> '${s.toString()}'")
                }
            }
        })
    }

    private fun addTaskRow(timeMillis: Long?, durationMin: String?) {
        val row = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL 
            setPadding(0, 8, 0, 8)
        }
        val timeBtn = Button(this).apply { 
            text = if (timeMillis != null) timeFormat.format(Date(timeMillis)) else "Godzina"
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
                val oldTime = timeBtn.text.toString()
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); cal.set(Calendar.SECOND, 0)
                timeBtn.tag = cal.timeInMillis
                timeBtn.text = String.format(Locale.getDefault(), "%02d:%02d", h, m)
                logAction("Edycja harmonogramu: Czas $oldTime -> ${timeBtn.text}")
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        
        durationEdit.addTextChangedListener(object : TextWatcher {
            var oldVal = durationMin ?: ""
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { oldVal = s.toString() }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (oldVal != s.toString()) {
                    logAction("Edycja harmonogramu: Czas trwania $oldVal -> ${s.toString()}")
                }
            }
        })
        
        deleteBtn.setOnClickListener { 
            logAction("Usunięto harmonogram: ${timeBtn.text} / ${durationEdit.text} min")
            tasksContainer.removeView(row) 
        }
        
        row.addView(timeBtn); row.addView(durationEdit); row.addView(deleteBtn)
        tasksContainer.addView(row)
        logAction("Dodano harmonogram: ${timeBtn.text} / ${durationEdit.text} min")
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

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleStreaming() {
        val rtsp = rtspEditText.text.toString()
        val rtmp = rtmpEditText.text.toString()
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = Calendar.getInstance()
        
        val taskDetails = mutableListOf<String>()
        var soonestTaskTime: Long = Long.MAX_VALUE

        for (i in 0 until tasksContainer.childCount) {
            val row = tasksContainer.getChildAt(i) as LinearLayout
            val btn = row.getChildAt(0) as Button
            val edit = row.getChildAt(1) as EditText
            val timeMillis = btn.tag as? Long ?: continue
            val dur = edit.text.toString().toLong() * 60000

            val scheduledTime = Calendar.getInstance().apply { timeInMillis = timeMillis }
            if (scheduledTime.before(now)) scheduledTime.add(Calendar.DAY_OF_YEAR, 1)
            
            taskDetails.add("${btn.text} (${edit.text} min)")
            if (scheduledTime.timeInMillis < soonestTaskTime) soonestTaskTime = scheduledTime.timeInMillis

            val intent = Intent(this, AlarmReceiver::class.java).apply {
                putExtra(StreamingService.EXTRA_RTSP, rtsp)
                putExtra(StreamingService.EXTRA_RTMP, rtmp)
                putExtra("EXTRA_DURATION", dur)
                putExtra(StreamingService.EXTRA_LOG_FILE, "log_${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.txt")
            }

            val pendingIntent = PendingIntent.getBroadcast(this, i, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, scheduledTime.timeInMillis, pendingIntent)
        }
        
        val diffMin = (soonestTaskTime - now.timeInMillis) / 60000
        logAction("START HARMONOGRAMU: Liczba zadań: ${taskDetails.size}, Godziny: ${taskDetails.joinToString()}, Do startu: $diffMin min")
        
        isScheduled = true
        startButton.text = "STOP HARMONOGRAM"
        Toast.makeText(this, "Zaplanowano zadania", Toast.LENGTH_SHORT).show()
        saveTasks()
        statusTextView.text = "Status: Zaplanowano"
    }

    private fun stopAll() {
        logAction("Kliknięto: Zatrzymano harmonogram")
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until tasksContainer.childCount) {
            val intent = Intent(this, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(this, i, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.cancel(pendingIntent)
        }
        
        stopService(Intent(this, StreamingService::class.java))
        
        isScheduled = false
        startButton.text = "START STREAMING"
        statusTextView.text = "Status: Zatrzymano"
        Toast.makeText(this, "Harmonogram i serwis zatrzymany", Toast.LENGTH_SHORT).show()
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

    override fun onResume() { super.onResume(); handler.post(logUpdater) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(logUpdater) }
}
