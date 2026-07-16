package com.example.searchfloat.util

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 商业扫描 App 常见 TXT 题库格式解析器：一行一个 JSON。
 * 例：{"q":"题干","ans":"D","a":["选项A","选项B","选项C","选项D"]}
 *
 * 本 App 内部也把 Excel 题库转换成同等结构再导入，便于后续用题干 + 选项一起匹配。
 */
object JsonlParser {

    fun parse(context: Context, uri: Uri): List<ParsedRow> {
        val rows = mutableListOf<ParsedRow>()
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return rows
        return parseText(text)
    }

    fun parseText(text: String): List<ParsedRow> {
        val trimmed = text.trim()
        // 整个文件是 JSON 数组：[{...},{...}]
        if (trimmed.startsWith("[")) {
            try {
                val arr = JSONArray(trimmed)
                val rows = mutableListOf<ParsedRow>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val row = parseObject(obj)
                    if (row != null) rows.add(row)
                }
                if (rows.isNotEmpty()) return rows
            } catch (_: Exception) { /* fall through to per-line */ }
        }
        // 一行一个 JSON
        return trimmed.lineSequence().mapNotNull { parseLine(it) }.toList()
    }

    /** 把 app 解析出的结构转成商业 App 类似的 JSON Lines 文本。 */
    fun toJsonLines(rows: List<QuestionRecord>): String {
        return rows.joinToString("\n") { record ->
            JSONObject().apply {
                put("q", record.question)
                put("ans", record.answer)
                put("a", JSONArray(record.options))
            }.toString()
        }
    }

    private fun parseLine(line: String): ParsedRow? {
        val raw = line.trim()
        if (raw.isBlank() || !raw.startsWith("{")) return null
        val obj = try { JSONObject(raw) } catch (_: Exception) { return null }
        return parseObject(obj)
    }

    private fun parseObject(obj: JSONObject): ParsedRow? {
        val q = firstString(obj, "q", "question", "title", "题干", "题目").trim()
        val ans = firstString(obj, "ans", "answer", "correct", "答案", "正确答案").trim().uppercase()
        val type = firstString(obj, "type", "题型", "类型").trim()

        // 选项可以是数组，也可以是对象 {A:..,B:..,C:..}
        val options = mutableListOf<String>()
        val arr = firstJsonArray(obj, "a", "options", "choices", "选项")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotBlank()) options.add(v)
            }
        } else {
            // {A:..,B:..} 形式
            for (letter in 'A'..'H') {
                val v = firstString(obj, "$letter", letter.lowercaseChar().toString(),
                    "选项$letter", "option$letter").trim()
                if (v.isNotBlank()) options.add(v) else if (options.isNotEmpty()) break
            }
        }

        if (options.isEmpty()) return null
        val record = QuestionRecord(
            question = q,
            answer = ans,
            options = options,
            type = type.ifBlank { null }
        )
        if (!record.isUsable()) return null
        return record.toParsedRow()
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (k in keys) if (obj.has(k)) {
            val v = obj.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun firstJsonArray(obj: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) {
            val a = obj.optJSONArray(k)
            if (a != null) return a
        }
        return null
    }
}

/** Excel/TXT 统一后的中间结构。 */
data class QuestionRecord(
    val question: String,
    val answer: String,
    val options: List<String>,
    val type: String? = null
) {
    fun isUsable(): Boolean {
        if (question.isBlank()) return false
        if (answer.isBlank()) return false

        // 过滤 Excel 干扰 Sheet 转出来的垃圾：q 是纯数字，选项也大多是纯数字。
        if (question.matches(Regex("^\\d+$"))) {
            val numericOptions = options.count { it.matches(Regex("^\\d+$")) }
            if (numericOptions >= 5) return false
        }

        // 客观题：必须有选项
        val t = normalizeType()
        if (t in setOf("单选题", "多选题")) {
            if (options.size < 2) return false
        }
        if (t == "判断题") {
            // 判断题可以没有 options（直接答 正确/错误）
        }
        // 简答/填空/问答/名词解释/论述/计算 等主观题：只要题干 + 答案 即可
        return true
    }

    fun normalizeType(): String {
        val t = type.orEmpty().replace(" ", "").replace("　", "")
        if (t.isNotBlank()) {
            return when {
                t.contains("多选") || t.contains("多项") -> "多选题"
                t.contains("单选") || t.contains("单项") -> "单选题"
                t.contains("判断") || t.contains("正误") || t.contains("对错") -> "判断题"
                t.contains("填空") -> "填空题"
                t.contains("名词") || t.contains("术语") -> "名词解释"
                t.contains("论述") || t.contains("分析") -> "论述题"
                t.contains("计算") -> "计算题"
                t.contains("简答") || t.contains("问答") || t.contains("解答") || t.contains("主观") -> "简答题"
                else -> "简答题"
            }
        }

        // 没题型，按内容推断
        return when {
            isJudge() -> "判断题"
            options.size >= 2 && answer.matches(Regex("^[A-H]{2,}$")) -> "多选题"
            options.size >= 2 && answer.matches(Regex("^[A-H]$")) -> "单选题"
            options.isEmpty() && isTrueFalseAnswer() -> "判断题"
            options.isEmpty() -> "简答题"
            else -> "单选题"
        }
    }

    fun toParsedRow(): ParsedRow {
        val content = if (options.isNotEmpty()) {
            val opts = options.mapIndexed { index, value ->
                val label = ('A'.code + index).toChar()
                "$label-$value"
            }.joinToString("\n")
            val ansText = answerText() ?: answer
            "选项：\n$opts\n\n答案：$ansText"
        } else {
            // 主观题/判断题（无选项）
            "答案：$answer"
        }

        return ParsedRow(
            title = question.trim(),
            category = normalizeType(),
            content = content.trim()
        )
    }

    private fun isJudge(): Boolean {
        if (options.size != 2) return false
        val s = options.map { it.trim() }.toSet()
        return s == setOf("正确", "错误") || s == setOf("对", "错")
    }

    private fun isTrueFalseAnswer(): Boolean {
        val a = answer.trim()
        return a in setOf("正确", "错误", "对", "错", "是", "否", "T", "F", "√", "×", "A", "B")
    }

    private fun answerText(): String? {
        val letters = answer.filter { it in 'A'..'Z' }
        if (letters.isBlank()) return null
        return letters.mapNotNull { ch ->
            val idx = ch - 'A'
            options.getOrNull(idx)?.let { "$ch-$it" }
        }.joinToString("；").ifBlank { null }
    }
}
