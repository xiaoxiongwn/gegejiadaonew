package com.example.searchfloat.util

import com.example.searchfloat.data.Question
import kotlin.math.min

object QuestionMatcher {

    // ==== 题型识别 ====================================================

    /**
     * 识别屏幕左上角题型徽标。
     * 优先只看 OCR 前几行，避免题干正文里的“判断/选择”等词误触发。
     * 返回短类型：单选 / 多选 / 判断。
     */
    fun detectQuestionType(text: String): String? {
        if (text.isBlank()) return null

        val lines = text.lines()
            .map { normalizeLoose(it) }
            .filter { it.isNotBlank() }

        // 左上角徽标通常在 OCR 前 8 行，且很短：单选 / 多选 / 判断
        for (line in lines.take(8)) {
            val compact = line.replace("题", "")
            if (compact.length <= 8) {
                when {
                    compact.contains("多选") || compact.contains("多项") -> return "多选"
                    compact.contains("单选") || compact.contains("单项") -> return "单选"
                    compact.contains("判断") || compact.contains("正误") || compact.contains("对错") -> return "判断"
                }
            }
        }

        // 兜底：只在前 120 字内找明确题型词；不要根据 ABCD 推断，单选/多选都会有 ABCD。
        val head = normalizeLoose(text.take(120))
        return when {
            head.contains("多选题") || head.contains("多项选择") || head.contains("多选") -> "多选"
            head.contains("单选题") || head.contains("单项选择") || head.contains("单选") -> "单选"
            head.contains("判断题") || head.contains("正误判断") || head.contains("判断") -> "判断"
            else -> null
        }
    }

    /** 把题库 category 统一成短类型：单选 / 多选 / 判断 */
    fun normalizeQuestionTypeShort(raw: String): String? {
        val s = normalizeLoose(raw)
        return when {
            s.contains("多选") || s.contains("多项") -> "多选"
            s.contains("单选") || s.contains("单项") -> "单选"
            s.contains("判断") || s.contains("正误") || s.contains("对错") -> "判断"
            else -> null
        }
    }

    private fun isTypeCompatible(screenType: String?, q: Question): Boolean {
        if (screenType == null) return true
        val qType = normalizeQuestionTypeShort(q.category) ?: return true
        return qType == screenType
    }

    // ==== 题干抽取 ====================================================

    private val OPTION_LINE = Regex("""^\s*[A-Da-d][\.．、)）\-]\s*\S""")
    private val INLINE_OPTION = Regex("""\s+[A-Da-d][\.．、)）\-]\s*""")

    private val UI_NOISE_EXACT = setOf(
        "字体", "朗读", "题库纠错", "搜题", "提交", "上一题", "下一题", "答题情况"
    )

    /**
     * 把屏幕 OCR 文本里的 UI、题型徽标、ABCD 选项剥掉，只留题干。
     */
    fun extractStem(rawScreen: String): String {
        if (rawScreen.isBlank()) return rawScreen
        val stem = mutableListOf<String>()

        for (rawLine in rawScreen.lines()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            val compact = normalizeLoose(line)

            // 跳过顶部 UI 和题型徽标
            if (compact in UI_NOISE_EXACT) continue
            if (compact.contains("倒计时") || Regex("""^\d+/\d+$""").matches(compact)) continue
            if (compact.length <= 8 && detectQuestionType(compact) != null) continue

            // 遇到选项开始，题干结束
            if (OPTION_LINE.containsMatchIn(line)) break
            if (compact == "正确" || compact == "错误") break

            stem += line
        }

        var joined = stem.joinToString(" ").trim()
        if (joined.isBlank()) joined = rawScreen.trim()

        // OCR 偶尔把题干和 A. 选项识别成一行，截掉第一个选项标记之后的内容
        val parts = joined.split(INLINE_OPTION, limit = 2)
        if (parts.isNotEmpty() && parts[0].trim().length >= 6) {
            joined = parts[0].trim()
        }
        return joined.ifBlank { rawScreen }
    }

    // 高频公共词，命中只算 0.3 倍，避免“工作负责人/监护人”等公共词把错题顶上来
    private val COMMON_PHRASES = setOf(
        "工作负责人", "工作班成员", "工作票签发人", "工作许可人",
        "专责监护人", "监护人", "操作人", "运维人员", "现场负责人",
        "应当", "不得", "必须", "应该", "可以", "不准",
        "进行", "采取", "确认", "保证", "符合", "执行",
        "本规程", "本规定", "本标准", "上级", "有关部门",
        "安全生产", "生产经营单位", "从业人员", "管理人员"
    )

    // ==== 匹配 ========================================================

    data class MatchResult(
        val question: Question?,
        val score: Int,
        val titleLen: Int,
        val matched: Int,
        val confident: Boolean
    )

    fun findBestMatchScored(rawScreen: String, questions: List<Question>): MatchResult {
        if (questions.isEmpty() || rawScreen.isBlank()) {
            return MatchResult(null, 0, 0, 0, false)
        }

        val screenType = detectQuestionType(rawScreen)
        val stemRaw = extractStem(rawScreen)
        val stem = normalizeForMatch(stemRaw)
        if (stem.length < 4) return MatchResult(null, 0, 0, 0, false)

        var bestQ: Question? = null
        var bestComposite = 0
        var bestLen = 0
        var bestMatched = 0
        var bestCoverTitle = 0.0
        var bestCoverStem = 0.0

        for (q in questions) {
            // 题型是强过滤，不是小加分。识别到“判断”就不拿“单选题”硬凑。
            if (!isTypeCompatible(screenType, q)) continue

            val title = normalizeForMatch(q.title)
            if (title.isEmpty()) continue

            val matched = matchTitleScore(title, stem)
            if (matched <= 0) continue

            val coverTitle = matched.toDouble() / title.length.coerceAtLeast(1)
            val coverStem = matched.toDouble() / stem.length.coerceAtLeast(1)

            // 主分只看题干双向覆盖率；选项只作为小幅加成，不能靠选项单独锁定答案。
            var composite = (coverTitle * coverStem * 100).toInt()
            if (composite >= 10) {
                composite += optionOverlapBonus(q.content, rawScreen)
            }

            if (composite > bestComposite) {
                bestComposite = composite
                bestQ = q
                bestLen = title.length
                bestMatched = matched
                bestCoverTitle = coverTitle
                bestCoverStem = coverStem
            }
        }

        val confident = bestQ != null &&
            bestCoverTitle >= 0.60 &&
            bestCoverStem >= 0.30 &&
            bestMatched >= 6

        return MatchResult(bestQ, bestComposite, bestLen, bestMatched, confident)
    }

    /** 旧接口保留：仅在 confident 时返回题目 */
    fun findBestMatch(rawScreen: String, questions: List<Question>): Question? {
        val r = findBestMatchScored(rawScreen, questions)
        return if (r.confident) r.question else null
    }

    // ==== 内部评分 ====================================================

    private fun matchTitleScore(title: String, screenStem: String): Int {
        val t = title.trim()
        if (t.isEmpty()) return 0
        if (t.length >= 4 && screenStem.contains(t)) return t.length

        var matched = 0.0
        var i = 0
        while (i < t.length) {
            var bestLen = 0
            var j = min(t.length, i + 30)
            while (j - i >= 3) {
                val sub = t.substring(i, j)
                if (screenStem.contains(sub)) {
                    bestLen = j - i
                    break
                }
                j--
            }
            if (bestLen >= 3) {
                val sub = t.substring(i, i + bestLen)
                val weight = if (COMMON_PHRASES.any { sub.contains(it) }) 0.3 else 1.0
                matched += bestLen * weight
                i += bestLen
            } else {
                i++
            }
        }
        return matched.toInt()
    }

    private fun optionOverlapBonus(questionContent: String, rawScreen: String): Int {
        val screen = normalizeForMatch(rawScreen)
        val optionText = questionContent.substringBefore("答案：")
        if (optionText.isBlank()) return 0

        val parts = optionText
            .replace("选项：", "")
            .split('\n', '|', '；', ';')
            .map { it.trim() }
            .filter { it.length >= 3 }
            .map { it.replace(Regex("""^[A-Da-d][\.．、)）\-]\s*"""), "") }
            .map { normalizeForMatch(it) }
            .filter { it.length >= 2 && it != "正确" && it != "错误" }

        if (parts.isEmpty()) return 0
        val hit = parts.count { screen.contains(it) }
        return min(hit * 3, 12)
    }

    private fun normalizeLoose(s: String): String =
        s.trim()
            .replace(" ", "")
            .replace("　", "")
            .replace("\t", "")
            .replace("：", ":")
            .lowercase()

    private fun normalizeForMatch(s: String): String =
        s.lowercase()
            .replace(Regex("""\s+"""), "")
            .replace("（", "(")
            .replace("）", ")")
            .replace("，", ",")
            .replace("。", ".")
            .replace("；", ";")
            .replace("：", ":")
}
