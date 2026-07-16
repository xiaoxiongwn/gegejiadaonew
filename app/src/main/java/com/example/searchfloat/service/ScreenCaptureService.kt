package com.example.searchfloat.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.searchfloat.data.Question
import com.example.searchfloat.data.QuestionDatabase
import com.example.searchfloat.util.QuestionMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCaptureService : AccessibilityService() {
    private var lastText = ""
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingJob: Job? = null
    @Volatile
    private var cachedQuestions: List<Question> = emptyList()

    companion object {
        var instance: ScreenCaptureService? = null
        var lastResult: String = ""
        var lastFullText: String = ""
        var onResultUpdate: ((String) -> Unit)? = null
        var lastDebug: String = ""

        fun forceRescan() {
            instance?.let { svc ->
                svc.lastText = ""
                svc.scope.launch { svc.scanNow() }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        scope.launch {
            cachedQuestions = withContext(Dispatchers.IO) {
                val lib = com.example.searchfloat.util.ActiveLibrary.get(this@ScreenCaptureService)
                QuestionDatabase.getDatabase(this@ScreenCaptureService).questionDao().getAllOnceByLibrary(lib)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        pendingJob?.let { return }
        pendingJob = scope.launch {
            delay(300)
            scanNow()
            pendingJob = null
        }
    }

    private suspend fun scanNow() {
        val text = collectAllText()
        if (text.isBlank() || text == lastText) return
        lastText = text
        lastFullText = text

        val activeLib = com.example.searchfloat.util.ActiveLibrary.get(this)
        if (cachedQuestions.isEmpty()) {
            cachedQuestions = withContext(Dispatchers.IO) {
                QuestionDatabase.getDatabase(this@ScreenCaptureService).questionDao().getAllOnceByLibrary(activeLib)
            }
        }

        if (cachedQuestions.isEmpty()) {
            onResultUpdate?.invoke("题库「$activeLib」为空")
            return
        }

        val r = QuestionMatcher.findBestMatchScored(text, cachedQuestions)
        val typePrefix = QuestionMatcher.detectQuestionType(text)?.let { "【$it】" } ?: ""
        val confident = r.question != null && r.titleLen > 0 && r.score * 2 >= r.titleLen
        val preview = text.replace("\\s+".toRegex(), " ").take(200)
        val libBadge = "📚 $activeLib (辅助功能)"

        val result = if (confident) {
            val q = r.question!!
            "$libBadge\n$typePrefix${q.title}\n\n${q.content}\n\n— 匹配度 ${r.score}/${r.titleLen}"
        } else if (r.question != null) {
            "$libBadge ⚠️ 弱匹配\n\n${r.question!!.title}\n${r.question!!.content}\n\n— 匹配度 ${r.score}/${r.titleLen}\n\n[读到 ${text.length} 字]\n$preview"
        } else {
            "$libBadge\n未匹配\n\n[辅助功能读到 ${text.length} 字]\n$preview\n\n该题库共 ${cachedQuestions.size} 题"
        }
        lastResult = result
        lastDebug = "score=${r.score} titleLen=${r.titleLen} screenLen=${text.length}"
        onResultUpdate?.invoke(result)
    }

    /** 遍历当前所有窗口，收集所有可见文字。跳过本 App 自己的窗口。 */
    private fun collectAllText(): String {
        val sb = StringBuilder()
        val myPkg = packageName
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                val windows = windows
                if (!windows.isNullOrEmpty()) {
                    for (w in windows) {
                        val root = try { w.root } catch (_: Throwable) { null } ?: continue
                        val pkg = root.packageName?.toString() ?: ""
                        // 跳过本 App 自己的窗口（避免读到上一次答案）
                        if (pkg == myPkg) {
                            try { root.recycle() } catch (_: Throwable) {}
                            continue
                        }
                        // 跳过系统 UI（状态栏、导航栏）
                        if (pkg.startsWith("com.android.systemui") || pkg.startsWith("com.huawei.android.launcher")) {
                            try { root.recycle() } catch (_: Throwable) {}
                            continue
                        }
                        walkNode(root, sb)
                        try { root.recycle() } catch (_: Throwable) {}
                    }
                    return sb.toString().trim()
                }
            }
        } catch (_: Throwable) {}

        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return ""
        val pkg = root.packageName?.toString() ?: ""
        if (pkg != myPkg) walkNode(root, sb)
        try { root.recycle() } catch (_: Throwable) {}
        return sb.toString().trim()
    }

    private fun walkNode(root: AccessibilityNodeInfo, sb: StringBuilder) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 5000) {
            val n = queue.removeFirst()
            count++
            val parts = mutableListOf<CharSequence?>(n.text, n.contentDescription)
            if (Build.VERSION.SDK_INT >= 26) parts.add(n.hintText)
            if (Build.VERSION.SDK_INT >= 28) parts.add(n.paneTitle)
            for (p in parts) {
                if (!p.isNullOrBlank()) sb.append(p).append(' ')
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
            if (n != root) try { n.recycle() } catch (_: Throwable) {}
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun reloadQuestions() {
        scope.launch {
            cachedQuestions = withContext(Dispatchers.IO) {
                val lib = com.example.searchfloat.util.ActiveLibrary.get(this@ScreenCaptureService)
                QuestionDatabase.getDatabase(this@ScreenCaptureService).questionDao().getAllOnceByLibrary(lib)
            }
        }
    }
}
