package com.example.serialmonitor

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * USB-시리얼 어댑터(OTG)로 들어오는 "SW1:on / SW2:OFF ..." 데이터를 표시하고,
 * SW1~SW3가 ON(녹색)이면 경보음을 계속 울리는 앱.
 */
class MainActivity : Activity(), SerialInputOutputManager.Listener {

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.serialmonitor.USB_PERMISSION"
        const val BAUD = 9600
        const val MAX_LOG = 300
        val PATTERN = Regex("SW(\\d+)\\s*:\\s*(on|off)", RegexOption.IGNORE_CASE)
        val GREEN = Color.rgb(46, 204, 64)
        val RED = Color.rgb(255, 65, 54)
        val GRAY = Color.rgb(187, 187, 187)
    }

    private lateinit var usbManager: UsbManager
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val buf = StringBuilder()

    private val states = HashMap<Int, Boolean>()
    private val lights = LinkedHashMap<Int, Pair<View, TextView>>()
    private val logLines = ArrayDeque<String>()
    private val fmtTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fmtDay = SimpleDateFormat("yyyyMMdd", Locale.US)

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var alarmActive = false
    private var muted = false
    private var modeAll = false
    private var saveCsv = true

    private lateinit var statusTv: TextView
    private lateinit var alarmTv: TextView
    private lateinit var logTv: TextView
    private lateinit var connectBtn: Button
    private lateinit var lightRow: LinearLayout

    // ── 경보음: 경보 조건이 유지되는 동안 0.7초마다 반복 ──
    private val alarmTick = object : Runnable {
        override fun run() {
            if (!alarmActive) return
            if (!muted) {
                try {
                    tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                } catch (_: Exception) {
                }
            }
            handler.postDelayed(this, 700)
        }
    }

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    connect()
                } else {
                    toast("USB 사용 권한이 거부되었습니다.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 화면 꺼짐 방지
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        try {
            tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: Exception) {
        }
        setContentView(buildUi())

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permReceiver, filter)
        }
        updateAlarm()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(permReceiver)
        } catch (_: Exception) {
        }
        disconnect()
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
    }

    // ── 화면 구성 ──
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.DKGRAY)
    }

    private fun buildUi(): View {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "시리얼 SW 모니터"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        connectBtn = Button(this).apply {
            text = "연결"
            setOnClickListener { if (port == null) connect() else disconnect() }
        }
        statusTv = TextView(this).apply {
            text = "연결 안 됨"
            setPadding(dp(12), 0, 0, 0)
        }
        row.addView(connectBtn)
        row.addView(statusTv)
        root.addView(row)

        lightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(12))
        }
        root.addView(lightRow, LinearLayout.LayoutParams(match, wrap))
        for (n in 1..3) addLight(n)

        alarmTv = TextView(this).apply {
            text = "정상"
            textSize = 20f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(alarmTv, LinearLayout.LayoutParams(match, wrap))

        root.addView(CheckBox(this).apply {
            text = "소리 끄기"
            setOnCheckedChangeListener { _, checked ->
                muted = checked
                updateAlarm()
            }
        })
        root.addView(CheckBox(this).apply {
            text = "SW1~SW3가 모두 ON일 때만 경보 (기본: 하나라도 ON)"
            setOnCheckedChangeListener { _, checked ->
                modeAll = checked
                updateAlarm()
            }
        })

        val opt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        opt.addView(CheckBox(this).apply {
            text = "CSV 저장"
            isChecked = true
            setOnCheckedChangeListener { _, checked -> saveCsv = checked }
        })
        opt.addView(Button(this).apply {
            text = "로그 지우기"
            setOnClickListener {
                logLines.clear()
                logTv.text = ""
            }
        })
        root.addView(opt)

        logTv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
        }
        val sv = ScrollView(this).apply { addView(logTv) }
        root.addView(sv, LinearLayout.LayoutParams(match, 0, 1f))
        return root
    }

    private fun addLight(n: Int) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), 0, dp(14), 0)
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            background = circle(GRAY)
        }
        val name = TextView(this).apply {
            text = "SW$n"
            textSize = 16f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val st = TextView(this).apply {
            text = "-"
            gravity = Gravity.CENTER
        }
        col.addView(dot)
        col.addView(name)
        col.addView(st)
        lightRow.addView(col)
        lights[n] = Pair(dot, st)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ── 연결 / 해제 ──
    private fun connect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            toast("USB-시리얼 장치를 찾을 수 없습니다. OTG 케이블과 어댑터 연결을 확인하세요.")
            return
        }
        val driver = drivers[0]
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            usbManager.requestPermission(device, pi)
            return
        }
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            toast("장치를 열 수 없습니다.")
            return
        }
        val p = driver.ports[0]
        try {
            p.open(conn)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            try {
                p.close()
            } catch (_: Exception) {
            }
            toast("연결 실패: ${e.message}")
            return
        }
        port = p
        synchronized(buf) { buf.setLength(0) }
        ioManager = SerialInputOutputManager(p, this).also { it.start() }
        connectBtn.text = "연결 해제"
        statusTv.text = "연결됨 ($BAUD bps)"
    }

    private fun disconnect(msg: String = "연결 안 됨") {
        ioManager?.stop()
        ioManager = null
        try {
            port?.close()
        } catch (_: Exception) {
        }
        port = null
        states.clear()
        for ((dot, st) in lights.values) {
            dot.background = circle(GRAY)
            st.text = "-"
        }
        updateAlarm()
        connectBtn.text = "연결"
        statusTv.text = msg
    }

    // ── 수신 (USB 읽기 스레드에서 호출됨) ──
    override fun onNewData(data: ByteArray) {
        val text = String(data, Charsets.ISO_8859_1)
        val events = ArrayList<Pair<Int, Boolean>>()
        synchronized(buf) {
            buf.append(text)
            val s = buf.toString()
            var last = 0
            for (m in PATTERN.findAll(s)) {
                val n = m.groupValues[1].toIntOrNull() ?: continue
                events.add(Pair(n, m.groupValues[2].equals("on", ignoreCase = true)))
                last = m.range.last + 1
            }
            buf.delete(0, last)
            if (buf.length > 64) buf.delete(0, buf.length - 64) // 잘려 들어온 조각만 유지
        }
        if (events.isNotEmpty()) {
            runOnUiThread { events.forEach { onSwitch(it.first, it.second) } }
        }
    }

    override fun onRunError(e: Exception) {
        runOnUiThread {
            if (ioManager != null) disconnect("연결 끊김: ${e.message}")
        }
    }

    // ── 상태 반영 ──
    private fun onSwitch(n: Int, on: Boolean) {
        if (!lights.containsKey(n)) addLight(n)
        val (dot, st) = lights[n]!!
        dot.background = circle(if (on) GREEN else RED)
        st.text = if (on) "ON" else "OFF"
        states[n] = on
        updateAlarm()

        val now = Date()
        val stateText = if (on) "ON" else "OFF"
        logLines.addFirst("${fmtTime.format(now)}  SW$n  $stateText")
        if (logLines.size > MAX_LOG) logLines.removeLast()
        logTv.text = logLines.joinToString("\n")
        if (saveCsv) writeCsv(now, n, stateText)
    }

    private fun updateAlarm() {
        val watch = (1..3).map { states[it] == true }
        val active = if (modeAll) watch.all { it } else watch.any { it }
        if (active && !alarmActive) {
            alarmActive = true
            handler.post(alarmTick)
        } else if (!active && alarmActive) {
            alarmActive = false
            handler.removeCallbacks(alarmTick)
            try {
                tone?.stopTone()
            } catch (_: Exception) {
            }
        }
        if (active) {
            alarmTv.text = if (muted) "경보 중 (소리 꺼짐)" else "경보 중!"
            alarmTv.setTextColor(Color.rgb(221, 34, 34))
        } else {
            alarmTv.text = "정상"
            alarmTv.setTextColor(Color.rgb(34, 170, 119))
        }
    }

    private fun writeCsv(now: Date, n: Int, state: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, "serial_log_${fmtDay.format(now)}.csv")
            val header = if (f.exists()) "" else "\uFEFF시간,스위치,상태\n"
            f.appendText("$header${fmtTime.format(now)},SW$n,$state\n", Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}
