package com.example.searchfloat

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.searchfloat.data.Question
import com.example.searchfloat.data.QuestionDatabase
import com.example.searchfloat.ui.theme.SearchFloatTheme
import com.example.searchfloat.util.ActiveLibrary
import com.example.searchfloat.util.QuestionMatcher
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SearchFloatTheme {
                QuickSearchScreen(onFinish = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSearchScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val activeLib = remember { ActiveLibrary.get(context) }
    val dao = remember { QuestionDatabase.getDatabase(context).questionDao() }
    var allQuestions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var ocrText by remember { mutableStateOf("") }
    var ocrInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        allQuestions = withContext(Dispatchers.IO) { dao.getAllOnceByLibrary(activeLib) }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            ocrInProgress = true
            scope.launch {
                val text = runOcrOnUri(context, uri)
                ocrInProgress = false
                ocrText = text
                query = text.replace("\\s+".toRegex(), " ").take(80)
            }
        }
    }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            ocrInProgress = true
            scope.launch {
                val text = runOcrOnUri(context, uri)
                ocrInProgress = false
                ocrText = text
                query = text.replace("\\s+".toRegex(), " ").take(120)
            }
        }
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = createCameraOutputUri(context)
            if (uri != null) {
                pendingCameraUri = uri
                cameraLauncher.launch(uri)
            }
        }
    }
    fun startCamera() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
        } else {
            val uri = createCameraOutputUri(context)
            if (uri != null) {
                pendingCameraUri = uri
                cameraLauncher.launch(uri)
            }
        }
    }

    val matches = remember(query, allQuestions) {
        if (query.isBlank() || allQuestions.isEmpty()) emptyList()
        else {
            val type = QuestionMatcher.detectQuestionType(query)
            allQuestions
                .map { q ->
                    val r = QuestionMatcher.findBestMatchScored(query, listOf(q))
                    Triple(q, r.score, r.titleLen)
                }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(15)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快速搜题  📚 $activeLib") },
                navigationIcon = {
                    IconButton(onClick = onFinish) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("输入题目关键字（多个用空格分开）") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { startCamera() },
                    modifier = Modifier.weight(1f),
                    enabled = !ocrInProgress
                ) {
                    Text(if (ocrInProgress) "识别中…" else "📷 拍照搜题", fontSize = 13.sp)
                }
                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !ocrInProgress
                ) {
                    Text("🖼️ 选图搜题", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { query = ""; ocrText = "" },
                    modifier = Modifier.weight(0.7f)
                ) { Text("清空", fontSize = 13.sp) }
            }

            if (ocrText.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("📸 OCR 识别结果（已填入搜索框，可手动修改）", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        Text(ocrText, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "共 ${allQuestions.size} 题 · 找到 ${matches.size} 个匹配",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(matches) { (q, score, len) ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (q.category.isNotBlank()) {
                                Text(q.category, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                            }
                            Text(q.title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(q.content, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "匹配度 $score / $len",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
                if (matches.isEmpty() && query.isNotBlank()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("没找到匹配的题目，试试换关键字", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

private fun createCameraOutputUri(context: android.content.Context): Uri? {
    return try {
        val ts = System.currentTimeMillis()
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "ocr_$ts.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SearchFloat")
            }
        }
        context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )
    } catch (_: Throwable) { null }
}

private suspend fun runOcrOnUri(context: android.content.Context, uri: Uri): String {
    val bitmap = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) { null }
    } ?: return ""
    return runOcrOnBitmap(bitmap)
}

private suspend fun runOcrOnBitmap(bitmap: Bitmap): String {
    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { vis ->
                    if (cont.isActive) cont.resumeWith(Result.success(vis.text))
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resumeWith(Result.success(""))
                }
        } catch (_: Throwable) {
            if (cont.isActive) cont.resumeWith(Result.success(""))
        }
    }
}
