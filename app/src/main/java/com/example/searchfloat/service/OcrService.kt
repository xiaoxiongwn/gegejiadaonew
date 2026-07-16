package com.example.searchfloat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.searchfloat.MainActivity
import com.example.searchfloat.data.Question
import com.example.searchfloat.data.QuestionDatabase
import com.example.searchfloat.util.QuestionMatcher
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OcrService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var cachedQuestions: List<Question> = emptyList()

    companion object {
        private const val TAG = "OcrService"
        private const val CHANNEL_ID = "ocr_capture"
        private const val NOTIF_ID = 1001
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        @Volatile var instance: OcrService? = null
        @Volatile var lastOcrText: String = ""
        @Volatile var lastResult: String = ""

        fun isAuthorized(): Boolean = instance?.mediaProjection != null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, OcrService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun captureAndMatch(onResult: (String) -> Unit) {
            val svc = instance
            if (svc == null) {
                onResult("⚠️ 未授权截屏\n\n请回 App 主界面点击「开启截屏 OCR」按钮，授权一次（只需授权一次）")
                return
            }
            svc.captureAndMatch(onResult)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        // 预加载题库（仅当前选中的）
        scope.launch {
            cachedQuestions = withContext(Dispatchers.IO) {
                val lib = com.example.searchfloat.util.ActiveLibrary.get(this@OcrService)
                QuestionDatabase.getDatabase(this@OcrService).questionDao().getAllOnceByLibrary(lib)
            }
        }
        com.example.searchfloat.util.ActiveLibrary.addListener(libListener)
    }

    private val libListener: (String) -> Unit = { lib ->
        scope.launch {
            cachedQuestions = withContext(Dispatchers.IO) {
                QuestionDatabase.getDatabase(this@OcrService).questionDao().getAllOnceByLibrary(lib)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14 要求 mediaProjection 类型必须先 startForeground 再调用 getMediaProjection
        startInForeground()

        if (intent != null && mediaProjection == null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)

            if (resultCode != 0 && data != null) {
                try {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mpm.getMediaProjection(resultCode, data)
                    setupVirtualDisplay()
                    Log.d(TAG, "MediaProjection 创建成功 ${screenW}x${screenH}")
                } catch (e: Throwable) {
                    Log.e(TAG, "MediaProjection 创建失败", e)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "截屏识别", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("悬浮搜题")
            .setContentText("截屏识别已就绪")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun setupVirtualDisplay() {
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        screenDpi = dm.densityDpi

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SearchFloatCapture",
            screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    fun captureAndMatch(onResult: (String) -> Unit) {
        val reader = imageReader
        if (reader == null) {
            onResult("⚠️ 截屏未就绪")
            return
        }
        // 等 200ms 让虚拟显示有新帧
        Handler(Looper.getMainLooper()).postDelayed({
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) {
                onResult("⚠️ 截屏失败：未获取到画面")
                return@postDelayed
            }
            val bitmap = try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenW
                val tmp = Bitmap.createBitmap(screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888)
                tmp.copyPixelsFromBuffer(buf)
                Bitmap.createBitmap(tmp, 0, 0, screenW, screenH).also { tmp.recycle() }
            } catch (e: Throwable) {
                Log.e(TAG, "bitmap fail", e); null
            } finally {
                try { image.close() } catch (_: Exception) {}
            }
            if (bitmap == null) {
                onResult("⚠️ Bitmap 转换失败")
                return@postDelayed
            }
            // 诊断：保存截图到相册
            val savedPath = saveBitmapToGallery(bitmap)
            // 诊断：检查是否大部分是黑色（FLAG_SECURE 防截屏会得到黑屏）
            val brightness = sampleBrightness(bitmap)
            runOcr(bitmap, savedPath, brightness, onResult)
        }, 250)
    }

    private fun sampleBrightness(bm: Bitmap): Int {
        // 在图中均匀采样 100 个点，返回平均亮度 0~255
        var total = 0L
        var count = 0
        val stepX = (bm.width / 10).coerceAtLeast(1)
        val stepY = (bm.height / 10).coerceAtLeast(1)
        var y = 0
        while (y < bm.height) {
            var x = 0
            while (x < bm.width) {
                val p = bm.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                total += (r + g + b) / 3
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0 else (total / count).toInt()
    }

    private fun saveBitmapToGallery(bm: Bitmap): String {
        return try {
            val ts = System.currentTimeMillis()
            val name = "searchfloat_$ts.png"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SearchFloat")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        bm.compress(Bitmap.CompressFormat.PNG, 90, os)
                    }
                    "Pictures/SearchFloat/$name"
                } else "(保存失败)"
            } else {
                "(需 Android 10+)"
            }
        } catch (e: Throwable) {
            Log.e(TAG, "save fail", e)
            "(保存异常: ${e.message})"
        }
    }

    private fun runOcr(bitmap: Bitmap, savedPath: String, brightness: Int, onResult: (String) -> Unit) {
        try {
            val input = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(input)
                .addOnSuccessListener { vis ->
                    val text = vis.text
                    lastOcrText = text
                    handleOcrText(text, savedPath, brightness, onResult)
                    bitmap.recycle()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR 失败", e)
                    onResult("⚠️ OCR 失败：${e.message}")
                    bitmap.recycle()
                }
        } catch (e: Throwable) {
            Log.e(TAG, "OCR 调用异常", e)
            onResult("⚠️ OCR 异常：${e.message}")
            bitmap.recycle()
        }
    }

    private fun handleOcrText(text: String, savedPath: String, brightness: Int, onResult: (String) -> Unit) {
        scope.launch {
            val activeLib = com.example.searchfloat.util.ActiveLibrary.get(this@OcrService)
            if (cachedQuestions.isEmpty()) {
                cachedQuestions = withContext(Dispatchers.IO) {
                    QuestionDatabase.getDatabase(this@OcrService).questionDao().getAllOnceByLibrary(activeLib)
                }
            }
            val secureWarn = if (brightness < 8)
                "\n\n⚠️ 截图几乎全黑（亮度 $brightness/255），考试 App 可能启用了防截屏保护（FLAG_SECURE），无法识别。"
            else ""
            val diag = "\n\n[诊断] 截图已保存到「相册 → SearchFloat」: $savedPath\n图像平均亮度: $brightness/255"

            if (cachedQuestions.isEmpty()) {
                onResult("题库「$activeLib」为空$secureWarn$diag")
                return@launch
            }
            if (text.isBlank()) {
                onResult("⚠️ OCR 没识别到文字$secureWarn$diag")
                return@launch
            }
            val r = QuestionMatcher.findBestMatchScored(text, cachedQuestions)
            val typePrefix = QuestionMatcher.detectQuestionType(text)?.let { "【$it】" } ?: ""
            val preview = text.replace("\\s+".toRegex(), " ").take(150)
            val libBadge = "📚 $activeLib"
            val result = if (r.confident && r.question != null) {
                val q = r.question!!
                "$libBadge\n$typePrefix${q.title}\n\n${q.content}\n\n— 匹配度 ${r.score}/${r.titleLen}"
            } else if (r.question != null) {
                "$libBadge ⚠️ 未找到可信匹配\n\n最接近：${r.question!!.title}\n" +
                    "— 命中 ${r.matched}/${r.titleLen} · 综合分 ${r.score}\n\n[OCR 文字]\n$preview$diag"
            } else {
                "$libBadge\n未在题库中找到匹配题目\n\n[OCR 文字]\n$preview\n\n该题库共 ${cachedQuestions.size} 题$secureWarn$diag"
            }
            lastResult = result
            onResult(result)
        }
    }

    fun reloadQuestions() {
        scope.launch {
            cachedQuestions = withContext(Dispatchers.IO) {
                val lib = com.example.searchfloat.util.ActiveLibrary.get(this@OcrService)
                QuestionDatabase.getDatabase(this@OcrService).questionDao().getAllOnceByLibrary(lib)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.example.searchfloat.util.ActiveLibrary.removeListener(libListener)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        instance = null
    }
}
