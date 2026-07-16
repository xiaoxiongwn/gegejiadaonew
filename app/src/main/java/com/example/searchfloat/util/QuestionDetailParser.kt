package com.example.searchfloat.util

import com.example.searchfloat.data.Question

/**
 * 把 Question.content（文本格式）解析回结构化数据，供"练习"屏幕渲染。
 * content 形如：
 *   选项：
 *   A-xxx
 *   B-xxx
 *   ...
 *   答案：A-xxx   或   答案：A   或   答案：AB   或   答案：xxx（简答题）
 */
data class QuestionDetail(
    val options: List<Pair<String, String>>, // (letter, text)
    val correctLetters: String,               // "A" / "AB" / "" (简答题)
    val correctText: String                   // 完整答案文本（简答题用）
) {
    val isObjective: Boolean get() = options.isNotEmpty() && correctLetters.isNotEmpty()
}

object QuestionDetailParser {
    private val OPTION_RE = Regex("^([A-Ha-h])\\s*[-．、)）.\\.]\\s*(.+)$")

    fun parse(q: Question): QuestionDetail {
        val text = q.content
        val optionsBlock = text.substringAfter("选项：", "").substringBefore("答案：")
        val answerBlock = text.substringAfter("答案：", "").trim()

        val options = optionsBlock.lines()
            .mapNotNull { line ->
                val m = OPTION_RE.find(line.trim()) ?: return@mapNotNull null
                m.groupValues[1].uppercase() to m.groupValues[2].trim()
            }

        // 答案块第一段（分号/逗号前）的开头连续大写字母 = 正确选项
        val firstSeg = answerBlock.split(Regex("[；;,，]"))[0].trim()
        val letters = firstSeg.takeWhile { it in 'A'..'Z' }

        return QuestionDetail(options, letters, answerBlock)
    }
}
