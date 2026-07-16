package com.example.searchfloat

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.searchfloat.data.Question
import com.example.searchfloat.data.QuestionDatabase
import com.example.searchfloat.service.FloatWindowManager
import com.example.searchfloat.service.ScreenCaptureService
import com.example.searchfloat.ui.theme.SearchFloatTheme
import com.example.searchfloat.util.QuestionMatcher
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    private val screenshotLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(result.resultCode, result.data!!)
            captureScreenAndOCR()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = QuestionDatabase.getDatabase(this)
        val dao = db.questionDao()

        // 检查是否是截图触发
        if (intent.getBooleanExtra("screenshot", false)) {
            requestScreenshotPermission()
        }

        setContent {
            SearchFloatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SearchFloatApp(dao)
                }
            }
        }
    }

    private fun requestScreenshotPermission() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenshotLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun captureScreenAndOCR() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection?.let { mp ->
            virtualDisplay = mp.createVirtualDisplay(
                "screenshot", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, handler
            )

            handler.postDelayed({
                val image = imageReader?.acquireLatestImage()
                image?.let {
                    val planes = it.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    it.close()
                    runOCR(bitmap)
                }
                virtualDisplay?.release()
                imageReader?.close()
            }, 500)
        }
    }

    private fun runOCR(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                if (text.isNotBlank()) {
                    searchOcrResult(text)
                } else {
                    sendOcrResult("未能识别到文字")
                }
            }
            .addOnFailureListener { e ->
                sendOcrResult("识别失败: ${e.message}")
            }
    }

    private fun searchOcrResult(text: String) {
        val dao = QuestionDatabase.getDatabase(this).questionDao()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val results = dao.search(text.take(100))
            val result = if (results.isNotEmpty()) {
                val best = QuestionMatcher.findBestMatch(text, results)
                if (best != null) {
                    val typePrefix = QuestionMatcher.detectQuestionType(text)?.let { "【$it】" } ?: ""
                    "【截图识别】$typePrefix\n${best.title}\n${best.content}"
                } else {
                    "【截图识别】未找到匹配题目\n识别内容: ${text.take(200)}"
                }
            } else {
                "【截图识别】未找到匹配题目\n识别内容: ${text.take(200)}"
            }
            sendOcrResult(result)
        }
    }

    private fun sendOcrResult(result: String) {
        ScreenCaptureService.onResultUpdate?.invoke(result)
        finish()
    }
}

fun isAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    if (am.isEnabled) {
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        if (enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }) {
            return true
        }
    }

    // 备选方案：读取系统设置中的已启用服务列表
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    val packageName = context.packageName
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.startsWith(packageName)) {
            return true
        }
    }
    return false
}

enum class Screen { HOME, ADD, EDIT, SEARCH, PRACTICE_MENU, PRACTICING }

fun canDrawOverlays(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFloatApp(dao: com.example.searchfloat.data.QuestionDao) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    var selectedQuestion by remember { mutableStateOf<Question?>(null) }
    var practiceMode by remember { mutableStateOf(com.example.searchfloat.ui.PracticeMode.SEQUENTIAL) }
    var practiceType by remember { mutableStateOf<String?>(null) }
    var hasOverlayPermission by remember { mutableStateOf(canDrawOverlays(context)) }
    var hasAccessibility by remember { mutableStateOf(isAccessibilityEnabled(context)) }

    // 每次返回App时重新检测权限
    LaunchedEffect(Unit) {
        while (true) {
            hasOverlayPermission = canDrawOverlays(context)
            hasAccessibility = isAccessibilityEnabled(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            dao = dao,
            hasOverlayPermission = hasOverlayPermission,
            hasAccessibility = hasAccessibility,
            onRequestOverlay = {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            },
            onRequestAccessibility = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            },
            onAdd = { screen = Screen.ADD; selectedQuestion = null },
            onEdit = { screen = Screen.EDIT; selectedQuestion = it },
            onOpenSearch = { screen = Screen.SEARCH },
            onOpenPractice = { screen = Screen.PRACTICE_MENU }
        )
        Screen.ADD -> AddEditScreen(
            question = null,
            dao = dao,
            onBack = { screen = Screen.HOME }
        )
        Screen.EDIT -> AddEditScreen(
            question = selectedQuestion,
            dao = dao,
            onBack = { screen = Screen.HOME }
        )
        Screen.SEARCH -> com.example.searchfloat.ui.TextSearchScreen(
            dao = dao,
            onBack = { screen = Screen.HOME }
        )
        Screen.PRACTICE_MENU -> com.example.searchfloat.ui.PracticeMenuScreen(
            dao = dao,
            onBack = { screen = Screen.HOME },
            onStart = { mode, type ->
                practiceMode = mode
                practiceType = type
                screen = Screen.PRACTICING
            }
        )
        Screen.PRACTICING -> com.example.searchfloat.ui.PracticingScreen(
            dao = dao,
            mode = practiceMode,
            typeFilter = practiceType,
            onBack = { screen = Screen.PRACTICE_MENU }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    dao: com.example.searchfloat.data.QuestionDao,
    hasOverlayPermission: Boolean,
    hasAccessibility: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Question) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPractice: () -> Unit
) {
    val context = LocalContext.current
    var activeLibrary by remember { mutableStateOf(com.example.searchfloat.util.ActiveLibrary.get(context)) }
    val libraries by produceState(initialValue = listOf(activeLibrary)) {
        dao.getLibrariesFlow().collect { list ->
            val merged = (list + activeLibrary).distinct().sorted()
            value = if (merged.isEmpty()) listOf(com.example.searchfloat.util.ActiveLibrary.DEFAULT) else merged
        }
    }
    val questions by produceState(initialValue = emptyList<Question>(), activeLibrary) {
        dao.getAllByLibrary(activeLibrary).collect { value = it }
    }
    var searchQuery by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf<Question?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("悬浮搜题") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // 题库选择卡片
            var showNewLib by remember { mutableStateOf(false) }
            var showRenameLib by remember { mutableStateOf(false) }
            var showDeleteLib by remember { mutableStateOf(false) }
            var libMenuOpen by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📚 当前题库", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { libMenuOpen = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "$activeLibrary  (${questions.size} 题)  ▼",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            DropdownMenu(
                                expanded = libMenuOpen,
                                onDismissRequest = { libMenuOpen = false }
                            ) {
                                libraries.forEach { lib ->
                                    DropdownMenuItem(
                                        text = { Text(lib + if (lib == activeLibrary) "  ✓" else "") },
                                        onClick = {
                                            activeLibrary = lib
                                            com.example.searchfloat.util.ActiveLibrary.set(context, lib)
                                            libMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { showNewLib = true }, modifier = Modifier.weight(1f)) {
                            Text("新建", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { showRenameLib = true }, modifier = Modifier.weight(1f)) {
                            Text("重命名", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { showDeleteLib = true },
                            modifier = Modifier.weight(1f),
                            enabled = libraries.size > 1
                        ) {
                            Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (showNewLib) {
                var newName by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showNewLib = false },
                    title = { Text("新建题库") },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("题库名称") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val n = newName.trim()
                            if (n.isNotBlank()) {
                                activeLibrary = n
                                com.example.searchfloat.util.ActiveLibrary.set(context, n)
                            }
                            showNewLib = false
                        }) { Text("创建并切换") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNewLib = false }) { Text("取消") }
                    }
                )
            }

            if (showRenameLib) {
                var newName by remember { mutableStateOf(activeLibrary) }
                AlertDialog(
                    onDismissRequest = { showRenameLib = false },
                    title = { Text("重命名「$activeLibrary」") },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("新名称") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val n = newName.trim()
                            if (n.isNotBlank() && n != activeLibrary) {
                                scope.launch {
                                    dao.renameLibrary(activeLibrary, n)
                                    activeLibrary = n
                                    com.example.searchfloat.util.ActiveLibrary.set(context, n)
                                }
                            }
                            showRenameLib = false
                        }) { Text("确定") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRenameLib = false }) { Text("取消") }
                    }
                )
            }

            if (showDeleteLib) {
                AlertDialog(
                    onDismissRequest = { showDeleteLib = false },
                    title = { Text("删除「$activeLibrary」") },
                    text = { Text("将永久删除该题库下所有 ${questions.size} 道题目，无法恢复。") },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                dao.deleteLibrary(activeLibrary)
                                val remaining = dao.getLibraries()
                                val next = remaining.firstOrNull() ?: com.example.searchfloat.util.ActiveLibrary.DEFAULT
                                activeLibrary = next
                                com.example.searchfloat.util.ActiveLibrary.set(context, next)
                            }
                            showDeleteLib = false
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteLib = false }) { Text("取消") }
                    }
                )
            }

            // 权限卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (!hasOverlayPermission) {
                        Text("需要悬浮窗权限", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = onRequestOverlay, modifier = Modifier.fillMaxWidth()) {
                            Text("开启悬浮窗权限")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (!hasAccessibility) {
                        Text("需要辅助功能权限（自动识别屏幕文字）", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = onRequestAccessibility, modifier = Modifier.fillMaxWidth()) {
                            Text("开启辅助功能权限")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (hasOverlayPermission && hasAccessibility) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("权限已就绪", color = MaterialTheme.colorScheme.primary)
                            if (FloatWindowManager.isRunning) {
                                Button(onClick = { FloatWindowManager.hide(context) }) {
                                    Text("停止悬浮窗")
                                }
                            } else {
                                Button(onClick = { FloatWindowManager.show(context) }) {
                                    Text("启动悬浮窗")
                                }
                            }
                        }
                    }

                    // 诊断日志
                    if (FloatWindowManager.lastError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("错误: ${FloatWindowManager.lastError}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    if (FloatWindowManager.lastLog.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("日志:", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        Text(FloatWindowManager.lastLog, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            // 导入题库
            var importMsg by remember { mutableStateOf("") }
            // 截屏 OCR 授权
            var ocrAuthed by remember { mutableStateOf(com.example.searchfloat.service.OcrService.isAuthorized()) }
            LaunchedEffect(Unit) {
                while (true) {
                    ocrAuthed = com.example.searchfloat.service.OcrService.isAuthorized()
                    kotlinx.coroutines.delay(1000)
                }
            }
            val mpLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                    com.example.searchfloat.service.OcrService.start(context, result.resultCode, result.data!!)
                    android.widget.Toast.makeText(context, "截屏 OCR 已开启", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "未授权截屏", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (ocrAuthed) "✅ 截屏 OCR 已就绪（推荐）" else "📸 截屏 OCR（推荐）",
                        color = if (ocrAuthed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "考试 App 用 WebView 渲染时辅助功能读不到题目，开启截屏 OCR 后通过实时截图识别屏幕文字。授权一次，重启 App 后需重新授权。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                            mpLauncher.launch(mpm.createScreenCaptureIntent())
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (ocrAuthed) "重新授权截屏 OCR" else "开启截屏 OCR")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    scope.launch {
                        // 不看扩展名、不看 MIME：先按内容盲试 xlsx；不是 xlsx 再回退 CSV。
                        // 这能避开 WPS 导出的“.xls 其实是 xlsx”大坑。
                        val rows = try {
                            // xlsx/xlsm/伪 xls：内部先转换成商业 App 类似 JSONL 结构，再导入题库
                            com.example.searchfloat.util.XlsxParser.parse(context, uri)
                        } catch (_: com.example.searchfloat.util.XlsxParser.NotXlsxException) {
                            // 真正的老 Excel .xls / BIFF 不是 ZIP，走 jxl 解析
                            try {
                                val xlsRows = com.example.searchfloat.util.XlsParser.parse(context, uri)
                                if (xlsRows.isNotEmpty()) xlsRows else {
                                    val jsonRows = com.example.searchfloat.util.JsonlParser.parse(context, uri)
                                    if (jsonRows.isNotEmpty()) jsonRows
                                    else com.example.searchfloat.util.XlsxParser.parseCsv(context, uri)
                                }
                            } catch (xe: Exception) {
                                android.util.Log.e("Import", "old xls parse fail", xe)
                                importMsg = "老版 .xls 解析失败：${xe.message ?: xe.javaClass.simpleName}"
                                emptyList()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Import", "xlsx parse fail, fallback xls/jsonl/csv", e)
                            try {
                                val xlsRows = com.example.searchfloat.util.XlsParser.parse(context, uri)
                                if (xlsRows.isNotEmpty()) xlsRows else {
                                    val jsonRows = com.example.searchfloat.util.JsonlParser.parse(context, uri)
                                    if (jsonRows.isNotEmpty()) jsonRows
                                    else com.example.searchfloat.util.XlsxParser.parseCsv(context, uri)
                                }
                            } catch (xe: Exception) {
                                android.util.Log.e("Import", "old xls parse fail", xe)
                                val jsonRows = com.example.searchfloat.util.JsonlParser.parse(context, uri)
                                if (jsonRows.isNotEmpty()) jsonRows
                                else com.example.searchfloat.util.XlsxParser.parseCsv(context, uri)
                            }
                        }

                        if (rows.isNotEmpty()) {
                            rows.forEach { row ->
                                dao.insert(
                                    com.example.searchfloat.data.Question(
                                        title = row.title,
                                        category = row.category,
                                        content = row.content,
                                        library = activeLibrary
                                    )
                                )
                            }
                            importMsg = "成功导入 ${rows.size} 条题目到「$activeLibrary」"
                            com.example.searchfloat.service.OcrService.instance?.reloadQuestions()
                        } else {
                            importMsg = "文件内容为空、格式错误，或没有单选/多选/判断题"
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { filePicker.launch("*/*") },
                    modifier = Modifier.weight(1f)
                ) { Text("导入题库") }
                OutlinedButton(
                    onClick = onOpenSearch,
                    modifier = Modifier.weight(1f)
                ) { Text("文字搜题") }
                OutlinedButton(
                    onClick = onOpenPractice,
                    modifier = Modifier.weight(1f)
                ) { Text("练习") }
            }
            if (importMsg.isNotBlank()) {
                Text(
                    importMsg,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("搜索题库") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            val filtered = if (searchQuery.isBlank()) questions else questions.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.content.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true)
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无题目", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered) { q ->
                        QuestionCard(
                            question = q,
                            onClick = { onEdit(q) },
                            onDelete = { showDelete = q }
                        )
                    }
                }
            }
        }
    }

    if (showDelete != null) {
        AlertDialog(
            onDismissRequest = { showDelete = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条题目吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.delete(showDelete!!) }
                    showDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
fun QuestionCard(question: Question, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = question.title.ifBlank { "无标题" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
            if (question.category.isNotBlank()) {
                Text(
                    question.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                question.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditScreen(
    question: Question?,
    dao: com.example.searchfloat.data.QuestionDao,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(question?.title ?: "") }
    var content by remember { mutableStateOf(question?.content ?: "") }
    var category by remember { mutableStateOf(question?.category ?: "") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (question == null) "添加题目" else "编辑题目") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            val q = Question(
                                id = question?.id ?: 0,
                                title = title,
                                content = content,
                                category = category,
                                library = question?.library ?: com.example.searchfloat.util.ActiveLibrary.get(context),
                                createdAt = question?.createdAt ?: System.currentTimeMillis()
                            )
                            if (question == null) dao.insert(q) else dao.update(q)
                            com.example.searchfloat.service.OcrService.instance?.reloadQuestions()
                            onBack()
                        }
                    }) { Text("保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("题目 / 关键词") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("分类（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("答案 / 内容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
                minLines = 5
            )
        }
    }
}
