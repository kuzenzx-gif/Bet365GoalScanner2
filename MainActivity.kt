package com.example.bet365goalscanner

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val requestCapture = 4102
    private lateinit var link: EditText
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var signal: TextView
    private lateinit var raw: TextView
    private var receiverRegistered = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_RESULT) return
            stats.text = intent.getStringExtra("summary") ?: "Không có dữ liệu"
            signal.text = intent.getStringExtra("signal") ?: "Chưa đủ dữ liệu"
            raw.text = intent.getStringExtra("raw") ?: ""
            status.text = "Đã cập nhật lúc ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 24)
        }
        val title = TextView(this).apply {
            text = "LIVE GOAL SCANNER"
            textSize = 24f
            setTextColor(0xFF1565C0.toInt())
        }
        link = EditText(this).apply {
            hint = "Dán link trận Bet365 (tùy chọn)"
            setSingleLine(true)
        }
        val note = TextView(this).apply {
            text = "Mở bảng thống kê trận trên Chrome, sau đó bắt đầu chia sẻ màn hình. Giữ bảng thống kê hiển thị để OCR đọc chính xác."
            textSize = 14f
        }
        val start = Button(this).apply {
            text = "BẮT ĐẦU QUÉT MÀN HÌNH"
            setOnClickListener { requestScreenCapture() }
        }
        val stop = Button(this).apply {
            text = "DỪNG QUÉT"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                status.text = "Đã gửi lệnh dừng."
                signal.text = "Đã dừng quét"
            }
        }
        status = TextView(this).apply { text = "Trạng thái: chưa bắt đầu"; textSize = 14f }
        signal = TextView(this).apply {
            text = "CHƯA CÓ TÍN HIỆU"
            textSize = 21f
            setPadding(0, 18, 0, 18)
            setTextColor(0xFF616161.toInt())
        }
        val statsTitle = TextView(this).apply { text = "THỐNG KÊ ĐỌC ĐƯỢC"; textSize = 18f }
        stats = TextView(this).apply {
            text = "Attacks: --\\nDangerous Attacks: --\\nPossession: --\\nShots: --\\nOn Target: --\\nCorners: --"
            textSize = 17f
            setPadding(0, 10, 0, 20)
        }
        val rawTitle = TextView(this).apply { text = "VĂN BẢN OCR (KIỂM TRA LỖI ĐỌC)"; textSize = 14f }
        raw = TextView(this).apply { text = "Chưa có ảnh được quét"; textSize = 12f }
        layout.addView(title); layout.addView(link); layout.addView(note)
        layout.addView(start); layout.addView(stop); layout.addView(status)
        layout.addView(signal); layout.addView(statsTitle); layout.addView(stats)
        layout.addView(rawTitle); layout.addView(raw)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ScreenCaptureService.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(scanReceiver, filter)
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCapture)
    }

    @Deprecated("Activity result API can be adopted in a later version")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val service = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, data)
                    putExtra(ScreenCaptureService.EXTRA_LINK, link.text.toString())
                }
                ContextCompat.startForegroundService(this, service)
                status.text = "Đang quét. Hãy chuyển sang Chrome và mở thống kê trận."
            } else status.text = "Bạn đã từ chối quyền chia sẻ màn hình."
        }
    }
}
