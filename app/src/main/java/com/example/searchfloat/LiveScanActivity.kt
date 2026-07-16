package com.example.searchfloat

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.searchfloat.data.Question
import com.example.searchfloat.data.QuestionDatabase
import com.example.searchfloat.ui.theme.SearchFloatTheme
import com.example.searchfloat.util.ActiveLibrary
import com.example.searchfloat.util.QuestionMatcher
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v3 改动（基于 v2 的真机反馈）：
 *  1. 锁定按钮位置改到屏幕右侧、垂直略偏下（BiasAlignment(1f, 0.25f)），
 *     更符合右手单手大拇指自然弧线，比 v2 的左侧中部好按。
 *  2. 答案保留 buffer：最近一次 confident 命中保留 3 秒，期间即使当前帧识别
 *     失败 / 弱匹配，答案也继续显示。解决"答案一闪而过"问题。
 *  3. 稳定计数不再被非 confident 帧打断：仅在出现 不同 confident qid 时重置，
 *     单帧 OCR 噪声不再"清零"，自动锁更容易达成。
 *  4. 按钮 / 音量键按下时，锁的是 lastGoodMatch（grace 内）而非"当前可见答案"，
 *     避免"看到答案 → 答案消失 → 按下去锁了个空"的问题。
 *
 * v2 改动保留：
 *  - 稳定即锁：连续 2 帧 OCR 命中同一题且 confident=true → 自动软锁（lockSource=AUTO）。
 *  - 音量下键 = 锁/解锁切换（在 Activity 层 dispatchKeyEvent 拦截）。
 *  - 解锁判定：字符集 Jaccard < 0.45 连续 2 帧才真解锁。
 *  - 答案面板高度上限 = 屏幕 85%，给取景框保留至少 15% 视野。
 */
class LiveScanActivity : ComponentActivity() {

    /** Compose 层注册的"音量下键被按下"回调。null 时按键透传给系统。 */
    @Volatile
    var onVolumeDownToggle: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SearchFloatTheme {
                LiveScanScreen(onFinish = { finish() })
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 仅在 Compose 注册了回调（即 LiveScanScreen 处于活跃态）时才拦截音量键
        val cb = onVolumeDownToggle
        if (cb != null && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                cb.invoke()
            }
            // 不论 DOWN/UP/repeat 都吞掉，避免一边锁一边调音量
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        // 安全兜底：Activity 不在前台时不要再拦截音量键
        onVolumeDownToggle = null
    }
}

/** 锁定来源。NONE=未锁；AUTO=连续帧稳定后自动软锁；MANUAL=用户主动按键/按钮锁。 */
private enum class LockSource { NONE, AUTO, MANUAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScanScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? LiveScanActivity
    val activeLib = remember { ActiveLibrary.get(context) }
    val dao = remember { QuestionDatabase.getDatabase(context).questionDao() }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp

    var allQuestions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var ocrText by remember { mutableStateOf("") }
    var bestMatch by remember { mutableStateOf<MatchInfo?>(null) }

    // 锁定状态
    var lockSource by remember { mutableStateOf(LockSource.NONE) }
    var lockedMatch by remember { mutableStateOf<MatchInfo?>(null) }
    var lockedOcr by remember { mutableStateOf("") }

    // 稳定计数器
    var stableCount by remember { mutableStateOf(0) }
    var lastQid by remember { mutableStateOf<Long?>(null) }
    // 解锁去抖：连续 N 帧低于阈值才真的解锁
    var unlockMissCount by remember { mutableStateOf(0) }

    // 答案保留 buffer：最近一次 confident 命中，3 秒内即使当前帧丢了也继续显示
    var lastGoodMatch by remember { mutableStateOf<MatchInfo?>(null) }
    var lastGoodOcr by remember { mutableStateOf("") }
    var lastGoodAtMs by remember { mutableStateOf(0L) }
    val goodGraceMs = 3000L

    var hasCamPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamPerm = it
    }

    LaunchedEffect(Unit) {
        allQuestions = withContext(Dispatchers.IO) { dao.getAllOnceByLibrary(activeLib) }
        if (!hasCamPerm) permLauncher.launch(Manifest.permission.CAMERA)
    }

    // 解锁逻辑
    fun unlock() {
        lockSource = LockSource.NONE
        lockedMatch = null
        lockedOcr = ""
        stableCount = 0
        lastQid = null
        unlockMissCount = 0
    }

    // 锁定逻辑
    fun lock(source: LockSource, match: MatchInfo, ocr: String) {
        if (source == LockSource.NONE) return
        lockSource = source
        lockedMatch = match
        lockedOcr = ocr
        stableCount = 0
        lastQid = null
        unlockMissCount = 0
    }

    // 拿"当前可锁的最佳候选"——优先用最近一次 confident 命中（grace 内），
    // 否则用当前帧的 bestMatch。这样按键/按钮按下时，即使答案刚刚一闪而过，
    // 也能锁住最近一次 confident 答案，而不是锁了个空。
    fun pickLockable(): Pair<MatchInfo, String>? {
        val now = System.currentTimeMillis()
        val good = lastGoodMatch
        if (good != null && now - lastGoodAtMs <= goodGraceMs) {
            return good to lastGoodOcr
        }
        val cur = bestMatch
        if (cur != null && cur.confident) return cur to ocrText
        return null
    }

    // 切换锁定（音量键 / 按钮）：未锁定 → 锁定 lastGood/best；已锁定 → 解锁
    val toggleLock = {
        when (lockSource) {
            LockSource.NONE -> {
                val pick = pickLockable()
                if (pick != null) lock(LockSource.MANUAL, pick.first, pick.second)
            }
            else -> unlock()
        }
    }

    // 把最新的 toggleLock 暴露给 Activity 的音量键拦截器
    val currentToggle by rememberUpdatedState(newValue = toggleLock)
    DisposableEffect(activity) {
        activity?.onVolumeDownToggle = { currentToggle() }
        onDispose { activity?.onVolumeDownToggle = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫描搜题  📚 $activeLib") },
                navigationIcon = {
                    IconButton(onClick = onFinish) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val intent = android.content.Intent(context, QuickSearchActivity::class.java)
                        context.startActivity(intent)
                        onFinish()
                    }) {
                        Text("✍️ 手动搜题", fontSize = 13.sp)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (hasCamPerm) {
                CameraPreviewView(
                    onText = { text ->
                        // ===== 锁定中：判断要不要解锁 =====
                        if (lockSource != LockSource.NONE) {
                            val sim = textSimilarity(lockedOcr, text)
                            if (sim < 0.45 && text.length >= 8) {
                                unlockMissCount++
                                if (unlockMissCount >= 2) {
                                    unlock()
                                    // 解锁成功后这一帧不立即匹配，让下一帧 OCR 稳定再说
                                }
                            } else {
                                unlockMissCount = 0
                            }
                            return@CameraPreviewView
                        }

                        // ===== 未锁定：实时识别 + 稳定即锁 =====
                        ocrText = text
                        val match: MatchInfo? = if (allQuestions.isNotEmpty() && text.length >= 8) {
                            val r = QuestionMatcher.findBestMatchScored(text, allQuestions)
                            if (r.question != null) {
                                MatchInfo(
                                    question = r.question!!,
                                    score = r.score,
                                    titleLen = r.titleLen,
                                    matched = r.matched,
                                    confident = r.confident
                                )
                            } else null
                        } else null
                        bestMatch = match

                        // 稳定即锁 + 答案保留 buffer
                        if (match != null && match.confident) {
                            // 更新最近一次 confident 命中（用于 grace 内继续显示 / 锁定）
                            lastGoodMatch = match
                            lastGoodOcr = text
                            lastGoodAtMs = System.currentTimeMillis()

                            // 连续帧命中同一题计数；不同 qid 重置；非 confident 帧不重置
                            val qid = match.question.id
                            if (lastQid == null || qid == lastQid) {
                                stableCount++
                                lastQid = qid
                                if (stableCount >= 2) {
                                    lock(LockSource.AUTO, match, text)
                                }
                            } else {
                                lastQid = qid
                                stableCount = 1
                            }
                        }
                        // 非 confident 帧：保持 stableCount / lastQid 不变，
                        // 给 OCR 偶尔抖动留余地，不会因为一帧噪声就把累计清零。
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // ===== 顶部识别状态条 =====
                Surface(
                    color = Color(0xCC000000),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val type = QuestionMatcher.detectQuestionType(ocrText)
                        val typeStr = type?.let { " · 📌 $it 题" } ?: ""
                        val status = when {
                            lockSource == LockSource.MANUAL -> "🔒 已固定答案 · 对准新题自动解锁"
                            lockSource == LockSource.AUTO -> "🔒 自动固定（对准新题解锁）"
                            ocrText.isBlank() -> "🔍 对准考试屏幕..."
                            bestMatch == null -> "👀 识别到 ${ocrText.length} 字$typeStr · 题库无匹配"
                            bestMatch!!.confident ->
                                "✅ 已命中 (${bestMatch!!.matched}/${bestMatch!!.titleLen} · 分 ${bestMatch!!.score})$typeStr"
                            else ->
                                "⚠️ 仅弱匹配 (命中 ${bestMatch!!.matched}/${bestMatch!!.titleLen})$typeStr · 已隐藏答案"
                        }
                        Text(status, color = Color.White, fontSize = 13.sp)
                    }
                }

                // ===== 底部答案面板 =====
                // 显示优先级：锁定 → 当前 confident → grace 内的 lastGood
                val now = System.currentTimeMillis()
                val graceGood = lastGoodMatch?.takeIf {
                    now - lastGoodAtMs <= goodGraceMs
                }
                val displayMatch = when {
                    lockSource != LockSource.NONE -> lockedMatch
                    bestMatch?.confident == true -> bestMatch
                    graceGood != null -> graceGood
                    else -> bestMatch
                }
                displayMatch?.let { m ->
                    if (m.confident || lockSource != LockSource.NONE) {
                        Surface(
                            color = Color(0xEE1B2D1F),
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                // 给取景框至少 15% 高度
                                .heightIn(max = screenHeightDp * 0.85f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(14.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (m.question.category.isNotBlank()) {
                                    Text(
                                        m.question.category,
                                        color = Color(0xFFAAAAAA),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    m.question.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    m.question.content,
                                    color = Color(0xFFD0FFDA),
                                    fontSize = 14.sp
                                )
                                // 不再放固定按钮，按钮在屏幕左侧中部
                            }
                        }
                    } else {
                        // 弱匹配：只显示一个简短提示，不暴露答案，避免误导
                        Surface(
                            color = Color(0xEE2E2A1B),
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "⚠️ 题库未找到可信匹配",
                                    color = Color(0xFFFFE08A),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "最接近：${m.question.title}",
                                    color = Color(0xFFCCCCCC),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "（命中 ${m.matched}/${m.titleLen} 字，覆盖率不足，请用「✍️ 手动搜题」）",
                                    color = Color(0xFF888888),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // ===== 右侧中部偏下：固定/解除固定 小按钮 =====
                // 显示条件：已锁定 / 当前 confident / grace 内还有可锁的候选
                val canShowLockBtn =
                    lockSource != LockSource.NONE ||
                    bestMatch?.confident == true ||
                    graceGood != null
                if (canShowLockBtn) {
                    val isLocked = lockSource != LockSource.NONE
                    val bg = when (lockSource) {
                        LockSource.MANUAL -> Color(0xCCF36440) // 橙：手动锁
                        LockSource.AUTO -> Color(0xCC2556B6) // 蓝：自动软锁
                        LockSource.NONE -> Color(0x99000000) // 黑：未锁待按
                    }
                    Box(
                        modifier = Modifier
                            .align(BiasAlignment(1f, 0.25f)) // 右边缘，竖直方向稍稍偏下
                            .padding(end = 6.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(bg)
                            .clickable { toggleLock() }
                    ) {
                        Icon(
                            imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = if (isLocked) "解除固定" else "固定答案",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(18.dp)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("需要相机权限")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("授予权限")
                    }
                }
            }
        }
    }
}

data class MatchInfo(
    val question: Question,
    val score: Int,
    val titleLen: Int,
    val matched: Int,
    val confident: Boolean
)

@OptIn(ExperimentalCamera2Interop::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraPreviewView(
    onText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // v3.1: 用 FILL_CENTER，避免"预览裁剪显示、分析器另一个分辨率"造成的
                // 视觉清晰 ≠ 实际帧清晰。
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                try {
                    val provider = cameraProviderFuture.get()

                    // v3.2: 预览分辨率显式设为 1920x1080，让 AF 算法拿到更多细节信号。
                    val previewResSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                    val previewBuilder = Preview.Builder()
                        .setResolutionSelector(previewResSelector)
                    // v3.2: Camera2Interop 显式钉住 CONTROL_AF_MODE_CONTINUOUS_PICTURE，
                    // 避免个别 ROM（含华为 EMUI）默认走 AUTO / EDOF 之类的模式。
                    Camera2Interop.Extender(previewBuilder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                    val preview = previewBuilder.build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    // v3.2: 分析器 720p → 1080p。ML Kit 中文识别在 1080p 更稳，
                    // 副作用是每帧耗时 +~30%，但我们节流 600ms，一样吃得住。
                    val analyzerResSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(analyzerResSelector)
                        .build()

                    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    var lastAnalyzeMs = 0L
                    var processing = false
                    var lastRefocusMs = 0L
                    var emptyStreakStartMs = 0L

                    // v3.2 方案2: 枚举所有后置镜头，选最近对焦距离最小的那颗。
                    // LENS_INFO_MINIMUM_FOCUS_DISTANCE 单位是屈光度（1/m），
                    // 数值越大表示能对焦得越近。华为 Mate 40 上大概率只返回主摄一颗，
                    // 但如果 ROM 版本暴露了副摄（超广/微距），会自动切到能拍更近的那颗。
                    val selector = pickClosestFocusBackCamera(provider) ?: CameraSelector.DEFAULT_BACK_CAMERA

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analyzer)

                    // v3.2: 记录一下我们最终绑到了哪颗镜头，方便看日志。
                    logBoundCamera(camera.cameraInfo)

                    // v3.1: 点按对焦 —— 用户看到糊就点一下题目区域，立即重新 AF/AE。
                    previewView.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            val point = previewView.meteringPointFactory
                                .createPoint(event.x, event.y)
                            val action = FocusMeteringAction.Builder(
                                point,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                            )
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()
                            camera.cameraControl.startFocusAndMetering(action)
                            previewView.performClick()
                        }
                        true
                    }

                    // v3.2: 定时踢 AF —— 每 3 秒对中心区域重新对焦一次，
                    // 就算 OCR 一直有输出，也主动刷新焦点，模拟原生相机"总在扫焦"的手感。
                    val periodicRefocus = Runnable {
                        val w = previewView.width.coerceAtLeast(1)
                        val h = previewView.height.coerceAtLeast(1)
                        val center = previewView.meteringPointFactory
                            .createPoint(w / 2f, h / 2f)
                        val action = FocusMeteringAction.Builder(
                            center,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        )
                            .setAutoCancelDuration(2, TimeUnit.SECONDS)
                            .build()
                        camera.cameraControl.startFocusAndMetering(action)
                    }
                    val periodicHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    val periodicTask = object : Runnable {
                        override fun run() {
                            periodicRefocus.run()
                            periodicHandler.postDelayed(this, 3000L)
                        }
                    }
                    periodicHandler.postDelayed(periodicTask, 3000L)

                    // v3.1: OCR 兜底重对焦 —— 连续 ~1.5 秒没识别到文字就踢一下中心 AF。
                    analyzer.setAnalyzer(analyzerExecutor) { proxy ->
                        val now = System.currentTimeMillis()
                        if (processing || now - lastAnalyzeMs < 600) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        lastAnalyzeMs = now
                        processing = true
                        processFrame(proxy, recognizer) { text ->
                            val empty = text.isBlank()
                            if (empty) {
                                if (emptyStreakStartMs == 0L) emptyStreakStartMs = now
                                if (now - emptyStreakStartMs >= 1500 &&
                                    now - lastRefocusMs >= 2000
                                ) {
                                    lastRefocusMs = now
                                    val w = previewView.width.coerceAtLeast(1)
                                    val h = previewView.height.coerceAtLeast(1)
                                    val center = previewView.meteringPointFactory
                                        .createPoint(w / 2f, h / 2f)
                                    val action = FocusMeteringAction.Builder(
                                        center,
                                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                    )
                                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                                        .build()
                                    previewView.post {
                                        camera.cameraControl.startFocusAndMetering(action)
                                    }
                                }
                            } else {
                                emptyStreakStartMs = 0L
                            }
                            previewView.post { onText(text) }
                            processing = false
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("LiveScan", "camera fail", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )

    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }
}

/**
 * v3.2 方案2: 从 provider 拿到所有后置 CameraInfo，
 * 用 Camera2CameraInfo 读 LENS_INFO_MINIMUM_FOCUS_DISTANCE，
 * 选屈光度最大（=能对得最近）的那颗。
 *
 * 华为 Mate 40 上第三方 App 常常只能看到主摄一颗；如果 ROM 只返回一颗，
 * 此函数就会返回那一颗对应的 CameraSelector，等价于原 DEFAULT_BACK_CAMERA。
 * 如果幸运返回多颗，会切到最能拍近处的镜头，实测能扩大清晰高度范围。
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun pickClosestFocusBackCamera(provider: ProcessCameraProvider): CameraSelector? {
    return try {
        val backInfos: List<CameraInfo> = provider.availableCameraInfos.filter { info ->
            try {
                Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } catch (_: Throwable) {
                false
            }
        }
        if (backInfos.isEmpty()) return null

        // 记一下每颗镜头的能力，方便你在 logcat 里看到底华为暴露了几颗。
        backInfos.forEachIndexed { idx, info ->
            val c2 = Camera2CameraInfo.from(info)
            val id = c2.cameraId
            val minFocusDist = c2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
            )
            val focalLengths = c2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.joinToString()
            Log.i(
                "LiveScan",
                "back cam #$idx id=$id minFocusDist(diopter)=$minFocusDist focalLens=$focalLengths"
            )
        }

        // 选屈光度最大的一颗（能对焦得最近）。
        val best = backInfos.maxByOrNull { info ->
            val d = try {
                Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            } catch (_: Throwable) {
                null
            }
            d ?: -1f
        } ?: return null

        val bestId = try {
            Camera2CameraInfo.from(best).cameraId
        } catch (_: Throwable) {
            return null
        }
        Log.i("LiveScan", "picked back cam id=$bestId (closest focus)")

        // 用 CameraFilter 把选择限制到目标 cameraId。
        CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter(CameraFilter { infos ->
                infos.filter { info ->
                    try {
                        Camera2CameraInfo.from(info).cameraId == bestId
                    } catch (_: Throwable) {
                        false
                    }
                }
            })
            .build()
    } catch (t: Throwable) {
        Log.w("LiveScan", "pickClosestFocusBackCamera failed", t)
        null
    }
}

@OptIn(ExperimentalCamera2Interop::class)
private fun logBoundCamera(info: CameraInfo) {
    try {
        val c2 = Camera2CameraInfo.from(info)
        val id = c2.cameraId
        val focal = c2.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        )?.joinToString()
        val minFocus = c2.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        )
        Log.i("LiveScan", "bound camera id=$id focal=$focal minFocusDist=$minFocus")
    } catch (t: Throwable) {
        Log.w("LiveScan", "logBoundCamera failed", t)
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processFrame(
    proxy: ImageProxy,
    recognizer: TextRecognizer,
    onComplete: (String) -> Unit
) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        onComplete("")
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    recognizer.process(input)
        .addOnSuccessListener { vis -> onComplete(vis.text) }
        .addOnFailureListener { onComplete("") }
        .addOnCompleteListener { proxy.close() }
}

/**
 * 用最简单的字符集合 Jaccard 相似度判断两段 OCR 文本是否在描述"同一道题"。
 * 0.0 完全不同；1.0 完全相同。锁定状态下相似度低于阈值即判定换题。
 */
private fun textSimilarity(a: String, b: String): Double {
    if (a.isBlank() || b.isBlank()) return 0.0
    val sa = a.toCharArray().filter { !it.isWhitespace() }.toSet()
    val sb = b.toCharArray().filter { !it.isWhitespace() }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return 0.0
    val inter = sa.intersect(sb).size
    val union = sa.union(sb).size
    return inter.toDouble() / union
}
