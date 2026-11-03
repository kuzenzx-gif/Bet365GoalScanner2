package com.example.bet365goalscanner

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        const val EXTRA_LINK = "match_link"
        const val ACTION_RESULT = "com.example.bet365goalscanner.SCAN_RESULT"
        private const val CHANNEL = "goal_scanner"
        private const val NOTIFICATION_ID = 365
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val busy = AtomicBoolean(false)
    private var previous: MatchStats? = null
    private var scanCount = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Đang chuẩn bị quét màn hình"))
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.parcelableIntent(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "GoalScanner", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null
        )

        scope.launch {
            delay(3_000)
            scanLatestFrame()
            while (isActive) {
                delay(30_000)
                scanLatestFrame()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun scanLatestFrame() {
        if (!busy.compareAndSet(false, true)) return
        try {
            val image = reader?.acquireLatestImage() ?: return
            val bitmap = imageToBitmap(image)
            image.close()
            if (bitmap == null) return
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitText()
                val parsed = StatsParser.parse(text)
                val delta = previous?.let { StatsParser.delta(it, parsed) }
                val signal = SignalEngine.evaluate(previous, parsed)
                previous = parsed
                scanCount++
                val summary = parsed.summary() + "\\n\\nBIẾN ĐỘNG SO VỚI LẦN TRƯỚC:\\n" +
                    (delta ?: "Đang lấy dữ liệu gốc; cần lần quét tiếp theo.")
                val out = Intent(ACTION_RESULT).setPackage(packageName)
                    .putExtra("summary", summary)
                    .putExtra("signal", signal)
                    .putExtra("raw", text)
                sendBroadcast(out)
                updateNotification("Lần quét $scanCount: $signal")
            } finally {
                recognizer.close()
                bitmap.recycle()
            }
        } catch (e: Exception) {
            updateNotification("Không đọc được ảnh. Hãy để bảng thống kê hiện rõ.")
        } finally {
            busy.set(false)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Goal Scanner", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Live Goal Scanner")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        display?.release()
        reader?.close()
        projection?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun Intent.parcelableIntent(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java)
        else @Suppress("DEPRECATION") (getParcelableExtra(key) as? Intent)

    private suspend fun com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text>.awaitText(): String =
        suspendCoroutine { cont ->
            addOnSuccessListener { cont.resume(it.text) }
            addOnFailureListener { cont.resumeWithException(it) }
        }

    data class MatchStats(
        val attacks: Pair<Int?, Int?> = null to null,
        val dangerous: Pair<Int?, Int?> = null to null,
        val possession: Pair<Int?, Int?> = null to null,
        val shots: Pair<Int?, Int?> = null to null,
        val target: Pair<Int?, Int?> = null to null,
        val corners: Pair<Int?, Int?> = null to null
    ) {
        fun summary() = listOf(
            "Attacks: ${attacks.first ?: "--"} - ${attacks.second ?: "--"}",
            "Dangerous Attacks: ${dangerous.first ?: "--"} - ${dangerous.second ?: "--"}",
            "Possession: ${possession.first ?: "--"}% - ${possession.second ?: "--"}%",
            "Shots: ${shots.first ?: "--"} - ${shots.second ?: "--"}",
            "On Target: ${target.first ?: "--"} - ${target.second ?: "--"}",
            "Corners: ${corners.first ?: "--"} - ${corners.second ?: "--"}"
        ).joinToString("\\n")
    }

    object StatsParser {
        private fun nums(s: String): List<Int> =
            Regex("\\\\d+").findAll(s).mapNotNull { it.value.toIntOrNull() }.toList()

        private fun pairOnOrAfter(text: String, regex: Regex, maxChars: Int = 150): Pair<Int?, Int?> {
            val match = regex.find(text) ?: return null to null
            val segment = text.substring(match.range.last + 1, (match.range.last + 1 + maxChars).coerceAtMost(text.length))
            val n = nums(segment)
            return if (n.size >= 2) n[0] to n[1] else null to null
        }

        fun parse(text: String): MatchStats {
            val clean = text.replace("\\r", "\\n")
            val attacks = pairOnOrAfter(clean, Regex("Attacks", RegexOption.IGNORE_CASE))
            val dangerous = pairOnOrAfter(clean, Regex("Dangerous\\\\s+Attacks", RegexOption.IGNORE_CASE))
            val possession = pairOnOrAfter(clean, Regex("Possession\\\\s*%?", RegexOption.IGNORE_CASE))
            val shotSection = pairOnOrAfter(clean, Regex("Shots\\\\s*/\\\\s*On\\\\s*Target", RegexOption.IGNORE_CASE), 180)
            // Common Bet365 layout: 16/10 on the left and 10/5 on the right.
            val shotMatch = Regex("(\\\\d+)\\\\s*/\\\\s*(\\\\d+)[\\\\s\\\\S]{0,100}?(\\\\d+)\\\\s*/\\\\s*(\\\\d+)").find(
                clean.substringAfter("Shots", clean).take(220)
            )
            val shots = shotMatch?.let { it.groupValues[1].toInt() to it.groupValues[3].toInt() } ?: (null to null)
            val target = shotMatch?.let { it.groupValues[2].toInt() to it.groupValues[4].toInt() } ?: (null to null)
            val corners = pairOnOrAfter(clean, Regex("Corners?", RegexOption.IGNORE_CASE))
            return MatchStats(
                attacks = attacks,
                dangerous = dangerous,
                possession = possession,
                shots = if (shots.first != null) shots else shotSection,
                target = target,
                corners = corners
            )
        }

        fun delta(old: MatchStats, now: MatchStats): String {
            fun d(label: String, a: Pair<Int?,Int?>, b: Pair<Int?,Int?>): String {
                if (a.first == null || a.second == null || b.first == null || b.second == null)
                    return "$label: chưa đọc đủ dữ liệu"
                val left = b.first!! - a.first!!
                val right = b.second!! - a.second!!
                return "$label: ${if (left >= 0) "+" else ""}$left / ${if (right >= 0) "+" else ""}$right"
            }
            return listOf(
                d("Attacks", old.attacks, now.attacks),
                d("Dangerous Attacks", old.dangerous, now.dangerous),
                d("Shots", old.shots, now.shots),
                d("On Target", old.target, now.target)
            ).joinToString("\\n")
        }
    }

    object SignalEngine {
        fun evaluate(old: MatchStats?, now: MatchStats): String {
            if (old == null) return "ĐANG LẤY MỐC – chưa đủ dữ liệu"
            fun diff(a: Pair<Int?,Int?>, b: Pair<Int?,Int?>): Int {
                if (a.first == null || a.second == null || b.first == null || b.second == null) return 0
                return (b.first!! - a.first!!) + (b.second!! - a.second!!)
            }
            val danger = diff(old.dangerous, now.dangerous)
            val shots = diff(old.shots, now.shots)
            val target = diff(old.target, now.target)
            return when {
                target >= 1 || (danger >= 5 && shots >= 1) ->
                    "TÍN HIỆU MẠNH – áp lực tăng rõ, cần theo dõi"
                danger >= 3 || shots >= 1 ->
                    "TÍN HIỆU TRUNG BÌNH – có dấu hiệu gia tăng tấn công"
                else -> "TÍN HIỆU THẤP – chưa thấy áp lực tăng rõ"
            }
        }
    }
}
