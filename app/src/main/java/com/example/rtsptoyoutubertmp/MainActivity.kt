package com.example.rtsptoyoutubertmp

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
    private lateinit var stopStreamButton: Button
    private lateinit var tasksContainer: LinearLayout
    private lateinit var statusTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private var isScheduled = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("streaming_prefs", Context.MODE_PRIVATE) }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isStreaming = intent?.getBooleanExtra("is_streaming", false) ?: false
            stopStreamButton.isEnabled = isStreaming
        }
    }

    private val logUpdater = object : Runnable {
        override fun run() {
            synchronized(StreamingService.logBuffer) {
                val logBuilder = StringBuilder()
                for (log in StreamingService.logBuffer.toList().asReversed()) {
                    logBuilder.append(log).append("\n")
                }
                logTextView.text = logBuilder.toString()
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
        stopStreamButton = findViewById(R.id.stopStreamButton)
        stopStreamButton.isEnabled = false
        tasksContainer = findViewById(R.id.tasksContainer)
        statusTextView = findViewById(R.id.statusTextView)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        setupLinkLogging(rtspEditText, "RTSP")
        setupLinkLogging(rtmpEditText, "RTMP")

        rtspEditText.setText(prefs.getString("last_rtsp", ""))
        rtmpEditText.setText(prefs.getString("last_rtmp", ""))
        loadSavedTasks()
        checkBatteryOptimizations()

        findViewById<Button>(R.id.btnAddTask).setOnClickListener { 
            logAction("Kliknięto: Dodaj zadanie")
            addTaskRow(null, null) 
        }
        startButton.setOnClickListener { 
            logAction("Kliknięto: ${startButton.text}")
            if (isScheduled) stopAll() else scheduleStreaming() 
        }
        stopStreamButton.setOnClickListener {
            logAction("Kliknięto: Stop Stream (Force)")
            startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_STOP })
        }
        findViewById<Button>(R.id.viewLogsButton).setOnClickListener { 
            logAction("Kliknięto: Przeglądaj logi")
            showLogsList() 
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStateReceiver, IntentFilter("com.example.rtsptoyoutubertmp.STREAM_STATE"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceStateReceiver, IntentFilter("com.example.rtsptoyoutubertmp.STREAM_STATE"))
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(serviceStateReceiver)
    }

    private fun logAction(msg: String) {
        StreamingService.addLog(this, "UI: $msg")
    }

    private fun setupLinkLogging(edit: TextInputEditText, name: String) {
        edit.addTextChangedListener(object : TextWatcher {
            var oldVal = ""
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { oldVal = s.toString() }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (oldVal != s.toString()) {
                    // Automatyczne zapisywanie RTSP/RTMP
                    if (name == "RTSP") prefs.edit().putString("last_rtsp", s.toString()).apply()
                    if (name == "RTMP") prefs.edit().putString("last_rtmp", s.toString()).apply()
                }
            }
        })
    }

    private fun addTaskRow(startTimeMillis: Long?, endTimeMillis: Long?) {
        val row = LinearLayout(this).apply { 
            orientation = LinearLayout.HORIZONTAL 
            setPadding(0, 8, 0, 8)
        }
        val startBtn = Button(this).apply { 
            text = if (startTimeMillis != null) timeFormat.format(Date(startTimeMillis)) else "Start"
            tag = startTimeMillis 
        }
        val endBtn = Button(this).apply { 
            text = if (endTimeMillis != null) timeFormat.format(Date(endTimeMillis)) else "Koniec"
            tag = endTimeMillis 
        }
        val deleteBtn = Button(this).apply { text = "X" }
        
        startBtn.setOnClickListener {
            val cal = Calendar.getInstance()
            if (startBtn.tag != null) cal.timeInMillis = startBtn.tag as Long
            TimePickerDialog(this, { _, h, m ->
                val oldTime = startBtn.text.toString()
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                startBtn.tag = cal.timeInMillis
                startBtn.text = String.format(Locale.getDefault(), "%02d:%02d", h, m)
                logAction("Edycja harmonogramu: Start $oldTime -> ${startBtn.text}")
                saveTasks()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        
        endBtn.setOnClickListener {
            val cal = Calendar.getInstance()
            if (endBtn.tag != null) cal.timeInMillis = endBtn.tag as Long
            TimePickerDialog(this, { _, h, m ->
                val oldTime = endBtn.text.toString()
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                endBtn.tag = cal.timeInMillis
                endBtn.text = String.format(Locale.getDefault(), "%02d:%02d", h, m)
                logAction("Edycja harmonogramu: Koniec $oldTime -> ${endBtn.text}")
                saveTasks()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        
        deleteBtn.setOnClickListener { 
            logAction("Usunięto harmonogram: ${startBtn.text} - ${endBtn.text}")
            tasksContainer.removeView(row) 
            saveTasks()
        }
        
        row.addView(startBtn); row.addView(endBtn); row.addView(deleteBtn)
        tasksContainer.addView(row)
        logAction("Dodano harmonogram: ${startBtn.text} - ${endBtn.text}")
        saveTasks()
    }

    private fun saveTasks() {
        val taskSet = mutableSetOf<String>()
        for (i in 0 until tasksContainer.childCount) {
            val row = tasksContainer.getChildAt(i) as LinearLayout
            val startBtn = row.getChildAt(0) as Button
            val endBtn = row.getChildAt(1) as Button
            if (startBtn.tag != null && endBtn.tag != null) {
                taskSet.add("${startBtn.tag}|${endBtn.tag}")
            }
        }
        prefs.edit().putStringSet("saved_tasks", taskSet).apply()
    }

    private fun loadSavedTasks() {
        val tasks = prefs.getStringSet("saved_tasks", emptySet()) ?: return
        tasks.forEach {
            val parts = it.split("|")
            if (parts.size == 2) {
                val start = parts[0].toLongOrNull()
                val secondPart = parts[1]
                val end = secondPart.toLongOrNull()
                if (start != null) {
                    if (end != null && end > 100000) {
                        addTaskRow(start, end)
                    } else {
                        // Stary format z minutami trwania
                        val minutes = secondPart.toLongOrNull() ?: 0L
                        addTaskRow(start, start + minutes * 60000)
                    }
                }
            }
        }
    }

    private fun getAlarmIntent(index: Int): PendingIntent {
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = StreamingService.ACTION_START
        }
        return PendingIntent.getBroadcast(this, index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleStreaming() {
        // Wyczyszczenie poprzednich alarmów bez ubijania aktualnie trwającego streamu
        stopAll(stopService = false) 
        saveTasks()

        val rtsp = rtspEditText.text.toString()
        val rtmp = rtmpEditText.text.toString()
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = Calendar.getInstance()
        
        val taskDetails = mutableListOf<String>()

        for (i in 0 until tasksContainer.childCount) {
            val row = tasksContainer.getChildAt(i) as LinearLayout
            val startBtn = row.getChildAt(0) as Button
            val endBtn = row.getChildAt(1) as Button
            val startTimeMillis = startBtn.tag as? Long ?: continue
            val endTimeMillis = endBtn.tag as? Long ?: continue

            val startCal = Calendar.getInstance().apply { timeInMillis = startTimeMillis }
            val endCal = Calendar.getInstance().apply { timeInMillis = endTimeMillis }

            val scheduledTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, startCal.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, startCal.get(Calendar.MINUTE))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                if (before(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val scheduledEndTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, endCal.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, endCal.get(Calendar.MINUTE))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                if (before(scheduledTime) || timeInMillis == scheduledTime.timeInMillis) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val dur = scheduledEndTime.timeInMillis - scheduledTime.timeInMillis
            val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(scheduledTime.time)
            taskDetails.add("${startBtn.text}-${endBtn.text} ($formattedTime, ${dur / 60000} min)")

            val intent = Intent(this, AlarmReceiver::class.java).apply {
                action = StreamingService.ACTION_START
                putExtra(StreamingService.EXTRA_RTSP, rtsp)
                putExtra(StreamingService.EXTRA_RTMP, rtmp)
                putExtra(StreamingService.EXTRA_DURATION, dur)
                putExtra(StreamingService.EXTRA_REQUEST_CODE, i)
                putExtra(StreamingService.EXTRA_TARGET_TIME, scheduledTime.timeInMillis)
            }

            val pendingIntent = PendingIntent.getBroadcast(this, i, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                scheduledTime.timeInMillis,
                pendingIntent
            )
        }
        
        logAction("START HARMONOGRAMU: Liczba zadań: ${taskDetails.size}, Detale: ${taskDetails.joinToString()}")
        
        isScheduled = true
        startButton.text = "STOP HARMONOGRAM"
        Toast.makeText(this, "Zaplanowano zadania", Toast.LENGTH_SHORT).show()
        statusTextView.text = "Status: Zaplanowano"
    }

    private fun stopAll(stopService: Boolean = true) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until 50) {
            alarmManager.cancel(getAlarmIntent(i))
        }
        
        if (stopService) {
            val stopIntent = Intent(this, StreamingService::class.java).apply { 
                action = StreamingService.ACTION_STOP 
            }
            startService(stopIntent)
        }
        
        isScheduled = false
        startButton.text = "START STREAMING"
        statusTextView.text = "Status: Zatrzymano"
    }

    private fun showLogsList() {
        val logDir = File(filesDir, "logs")
        if (!logDir.exists()) logDir.mkdir()
        val files = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: listOf()
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
        val reversedContent = content.lines().reversed().joinToString("\n").trim()
        textView.text = reversedContent
        textView.setPadding(16, 16, 16, 16)
        AlertDialog.Builder(this)
            .setTitle(fileName)
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("Zamknij", null)
            .show()
    }

    private fun checkBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Wymagane wyłączenie optymalizacji baterii")
                .setMessage("Aby stream mógł wystartować o zaplanowanej godzinie (gdy telefon śpi), musisz wyłączyć optymalizację baterii dla tej aplikacji. Znajdź aplikację na liście i wybierz 'Brak ograniczeń'.")
                .setPositiveButton("Otwórz ustawienia") { _, _ ->
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("Zamknij", null)
                .show()
        }
    }

    override fun onResume() { super.onResume(); handler.post(logUpdater) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(logUpdater) }
}
