package com.example.searchfloat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.searchfloat.data.Question
import com.example.searchfloat.data.QuestionDao
import com.example.searchfloat.data.WrongAnswer
import com.example.searchfloat.util.ActiveLibrary
import com.example.searchfloat.util.QuestionDetailParser
import com.example.searchfloat.util.QuestionMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============== 1) 文字搜题 ==============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSearchScreen(dao: QuestionDao, onBack: () -> Unit) {
    val context = LocalContext.current
    val activeLib = remember { ActiveLibrary.get(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<Question, Int>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runSearch(q: String) {
        if (q.trim().length < 2) {
            results = emptyList(); return
        }
        loading = true
        scope.launch {
            val ranked = withContext(Dispatchers.IO) {
                val all = dao.getAllOnceByLibrary(activeLib)
                // 用 QuestionMatcher 评分，取 Top 20
                all.mapNotNull { qs ->
                    val r = QuestionMatcher.findBestMatchScored(q, listOf(qs))
                    if (r.question != null && r.score > 0) qs to r.score else null
                }.sortedByDescending { it.second }.take(20)
            }
            // 兜底：如果模糊匹配空，做关键字 LIKE 搜索
            results = if (ranked.isNotEmpty()) ranked
            else withContext(Dispatchers.IO) {
                dao.searchInLibrary(activeLib, q.trim()).take(20).map { it to 0 }
            }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文字搜题") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    runSearch(it)
                },
                label = { Text("输入题目（部分内容也行）") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )
            Text("题库：$activeLib", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (results.isEmpty() && query.trim().length >= 2 && !loading) {
                Text("未找到匹配题目", color = MaterialTheme.colorScheme.outline)
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(results) { (q, score) ->
                    SearchResultCard(q, score)
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(q: Question, score: Int) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (q.category.isNotBlank()) {
                    Text(
                        q.category,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (score > 0) {
                    Text("匹配 $score", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(q.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(q.content, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============== 2) 练习 ==============

enum class PracticeMode { SEQUENTIAL, BY_TYPE, WRONG }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeMenuScreen(
    dao: QuestionDao,
    onBack: () -> Unit,
    onStart: (PracticeMode, String?) -> Unit
) {
    val context = LocalContext.current
    val activeLib = remember { ActiveLibrary.get(context) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    val wrongCount by produceState(initialValue = 0) {
        dao.countWrongFlow(activeLib).collect { value = it }
    }
    var totalCount by remember { mutableStateOf(0) }
    var seqProgress by remember { mutableStateOf(0) }
    var wrongProgress by remember { mutableStateOf(0) }
    val typeProgress = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(activeLib) {
        scope.launch {
            categories = withContext(Dispatchers.IO) { dao.getCategoriesByLibrary(activeLib) }
            totalCount = withContext(Dispatchers.IO) { dao.countByLibrary(activeLib) }
            seqProgress = com.example.searchfloat.util.PracticeProgress.get(
                context, activeLib, PracticeMode.SEQUENTIAL.name, null
            )
            wrongProgress = com.example.searchfloat.util.PracticeProgress.get(
                context, activeLib, PracticeMode.WRONG.name, null
            )
            typeProgress.clear()
            categories.forEach { cat ->
                typeProgress[cat] = com.example.searchfloat.util.PracticeProgress.get(
                    context, activeLib, PracticeMode.BY_TYPE.name, cat
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("练习") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)
            .verticalScroll(rememberScrollState())) {
            Text("题库：$activeLib  ($totalCount 题)",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))

            val seqSub = if (seqProgress > 0 && seqProgress < totalCount)
                "上次练到第 ${seqProgress + 1} 题（共 $totalCount）· 点击继续"
            else "按题库顺序逐题练习"
            ModeCard("📖 顺序练习", seqSub) {
                onStart(PracticeMode.SEQUENTIAL, null)
            }
            Spacer(Modifier.height(12.dp))

            Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("📂 题型练习", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("选择一种题型开始练习", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(10.dp))
                    if (categories.isEmpty()) {
                        Text("当前题库暂无分类", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    } else {
                        categories.forEach { cat ->
                            val prog = typeProgress[cat] ?: 0
                            val label = if (prog > 0) "$cat · 上次到第 ${prog + 1} 题" else cat
                            OutlinedButton(
                                onClick = { onStart(PracticeMode.BY_TYPE, cat) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) { Text(label) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            val wrongSub = when {
                wrongCount == 0 -> "暂无错题"
                wrongProgress in 1 until wrongCount ->
                    "共 $wrongCount 道错题 · 上次练到第 ${wrongProgress + 1} 题"
                else -> "共 $wrongCount 道错题"
            }
            ModeCard(
                "❌ 错题练习",
                wrongSub,
                enabled = wrongCount > 0
            ) {
                onStart(PracticeMode.WRONG, null)
            }
        }
    }
}

@Composable
private fun ModeCard(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = if (enabled) CardDefaults.cardColors() else CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticingScreen(
    dao: QuestionDao,
    mode: PracticeMode,
    typeFilter: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activeLib = remember { ActiveLibrary.get(context) }
    var pool by remember { mutableStateOf<List<Question>>(emptyList()) }
    var idx by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var rightCount by remember { mutableStateOf(0) }
    var wrongCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mode, typeFilter) {
        loading = true
        pool = withContext(Dispatchers.IO) {
            when (mode) {
                PracticeMode.SEQUENTIAL -> dao.getOrderedByLibrary(activeLib)
                PracticeMode.BY_TYPE -> dao.getByLibraryAndCategory(activeLib, typeFilter ?: "")
                PracticeMode.WRONG -> dao.getWrongQuestions(activeLib)
            }
        }
        // 从上次进度继续；越界时自动重置为 0
        val saved = com.example.searchfloat.util.PracticeProgress.get(
            context, activeLib, mode.name, typeFilter
        )
        idx = if (saved < pool.size) saved else 0
        rightCount = 0
        wrongCount = 0
        loading = false
    }

    // idx 每次变化即持久化
    LaunchedEffect(idx, pool.size) {
        if (!loading && pool.isNotEmpty()) {
            com.example.searchfloat.util.PracticeProgress.set(
                context, activeLib, mode.name, typeFilter, idx
            )
        }
    }

    val title = when (mode) {
        PracticeMode.SEQUENTIAL -> "顺序练习"
        PracticeMode.BY_TYPE -> "题型练习 · ${typeFilter.orEmpty()}"
        PracticeMode.WRONG -> "错题练习"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
                actions = {
                    Text("✓ $rightCount  ✗ $wrongCount",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(end = 8.dp))
                    TextButton(onClick = {
                        com.example.searchfloat.util.PracticeProgress.clear(
                            context, activeLib, mode.name, typeFilter
                        )
                        idx = 0
                        rightCount = 0
                        wrongCount = 0
                    }) { Text("重新开始", fontSize = 13.sp) }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                pool.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有题目", color = MaterialTheme.colorScheme.outline)
                }
                idx >= pool.size -> {
                    LaunchedEffect(Unit) {
                        com.example.searchfloat.util.PracticeProgress.clear(
                            context, activeLib, mode.name, typeFilter
                        )
                    }
                    FinishedView(rightCount, wrongCount, onBack)
                }
                else -> QuestionView(
                    question = pool[idx],
                    indexLabel = "${idx + 1} / ${pool.size}",
                    library = activeLib,
                    dao = dao,
                    mode = mode,
                    onAnswered = { correct ->
                        if (correct) rightCount++ else wrongCount++
                    },
                    onNext = { idx++ }
                )
            }
        }
    }
}

@Composable
private fun FinishedView(right: Int, wrong: Int, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("练习完成 🎉", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("答对 $right 题，答错 $wrong 题",
            fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun QuestionView(
    question: Question,
    indexLabel: String,
    library: String,
    dao: QuestionDao,
    mode: PracticeMode,
    onAnswered: (Boolean) -> Unit,
    onNext: () -> Unit
) {
    val detail = remember(question.id) { QuestionDetailParser.parse(question) }
    val isMulti = question.category.contains("多选")
    val isJudge = question.category.contains("判断")
    val effectiveCorrect = remember(question.id) {
        if (detail.correctLetters.isNotEmpty()) detail.correctLetters
        else if (isJudge) {
            val t = detail.correctText.trim()
            when {
                t.contains("正确") || t == "对" || t == "是" || t == "T" || t == "√" -> "A"
                t.contains("错误") || t == "错" || t == "否" || t == "F" || t == "×" -> "B"
                else -> ""
            }
        } else ""
    }
    val isObjective = detail.isObjective || (isJudge && effectiveCorrect.isNotEmpty())
    val scope = rememberCoroutineScope()

    var selected by remember(question.id) { mutableStateOf(setOf<String>()) }
    var submitted by remember(question.id) { mutableStateOf(false) }
    var revealed by remember(question.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(indexLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(8.dp))
            if (question.category.isNotBlank()) {
                Text(question.category, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(question.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(20.dp))

        if (isObjective) {
            // 客观题：选项 + 提交
            val opts = if (detail.options.isNotEmpty()) detail.options
            else if (isJudge) listOf("A" to "正确", "B" to "错误") else emptyList()

            opts.forEach { (letter, text) ->
                val isSel = letter in selected
                val isCorrect = letter in effectiveCorrect.toCharArray().map { it.toString() }
                val borderColor = when {
                    !submitted && isSel -> MaterialTheme.colorScheme.primary
                    submitted && isCorrect -> Color(0xFF2E7D32)
                    submitted && isSel && !isCorrect -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                val bg = when {
                    submitted && isCorrect -> Color(0xFFE8F5E9)
                    submitted && isSel && !isCorrect -> MaterialTheme.colorScheme.errorContainer
                    isSel -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surface
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
                        .background(bg, RoundedCornerShape(8.dp))
                        .clickable(enabled = !submitted) {
                            selected = if (isMulti) {
                                if (isSel) selected - letter else selected + letter
                            } else setOf(letter)
                        }
                        .padding(12.dp)
                ) {
                    Text("$letter. $text", fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            if (!submitted) {
                Button(
                    onClick = {
                        val userAns = selected.sorted().joinToString("")
                        val correct = userAns == effectiveCorrect &&
                            userAns.isNotEmpty()
                        submitted = true
                        onAnswered(correct)
                        if (!correct) {
                            scope.launch(Dispatchers.IO) {
                                dao.insertWrong(WrongAnswer(
                                    questionId = question.id,
                                    library = library,
                                    userAnswer = userAns
                                ))
                            }
                        } else if (mode == PracticeMode.WRONG) {
                            // 错题模式答对 → 移出错题本
                            scope.launch(Dispatchers.IO) { dao.clearWrongOf(question.id) }
                        }
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("提交") }
            } else {
                val userAns = selected.sorted().joinToString("")
                val correct = userAns == effectiveCorrect
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (correct)
                            Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (correct) "✓ 回答正确" else "✗ 回答错误",
                            fontWeight = FontWeight.Bold,
                            color = if (correct) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        Text("正确答案：${effectiveCorrect.ifBlank { detail.correctText }}",
                            fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("下一题") }
            }
        } else {
            // 主观题：显示答案模式
            if (!revealed) {
                Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("显示答案")
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("参考答案", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(detail.correctText, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onAnswered(false)
                            scope.launch(Dispatchers.IO) {
                                dao.insertWrong(WrongAnswer(
                                    questionId = question.id,
                                    library = library,
                                    userAnswer = "[主观题-标记不会]"
                                ))
                            }
                            onNext()
                        }
                    ) { Text("不会") }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onAnswered(true)
                            if (mode == PracticeMode.WRONG) {
                                scope.launch(Dispatchers.IO) { dao.clearWrongOf(question.id) }
                            }
                            onNext()
                        }
                    ) { Text("会了") }
                }
            }
        }
    }
}
