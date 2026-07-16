package com.example.searchfloat.util

/**
 * 通用题库表格解析器。
 *
 * 输入：任意来源（xlsx/xls/csv）转出来的二维 List<List<String>>。
 * 输出：QuestionRecord 列表。
 *
 * 设计目标 —— 像商业搜题 App 那样"扔什么进来都能识别"：
 *   1) 不依赖固定表头名。表头有就用表头加速，没有也能从内容特征识别。
 *   2) 自动识别每列的角色：题干 / 答案 / 选项 / 题型 / 序号 / 分类 / 噪声。
 *      - 选项：合并列（A-xx|B-xx|...）或拆分列（选项A/B/C/D 或 A/B/C/D）都支持。
 *   3) 不抛弃简答 / 填空 / 问答 / 判断 / 名词解释 / 论述 / 计算等题型。
 *      只要题干 + 答案就保留。
 */
object SmartTableParser {

    fun parse(allRows: List<List<String>>): List<QuestionRecord> {
        if (allRows.isEmpty()) return emptyList()
        val maxCols = allRows.maxOfOrNull { it.size } ?: 0
        if (maxCols < 2) return emptyList()

        val rows = allRows
            .map { r -> List(maxCols) { i -> (r.getOrNull(i) ?: "").trim() } }
            .filter { row -> row.any { it.isNotBlank() } }
        if (rows.size < 2) return emptyList()

        // 双路并行：表头匹配 + 内容启发式，取条数多的那条
        val headerBased = parseByHeader(rows)
        val contentBased = parseByContent(rows)
        return if (headerBased.size >= contentBased.size) headerBased else contentBased
    }

    // ============ 路径 1：表头关键词匹配 ============

    private val TITLE_KEYS = setOf("题干", "考题", "题目", "标题", "问题", "试题", "题目内容", "试题内容", "question", "title", "stem")
    private val ANSWER_KEYS = setOf("答案", "正确答案", "参考答案", "标准答案", "answer", "ans", "key")
    private val OPTION_KEYS = setOf("选项", "选择项", "选项内容", "choices", "options")
    private val TYPE_KEYS = setOf("题型", "类型", "type", "category")
    private val CATEGORY_KEYS = setOf("题目分类", "分类", "章节", "知识点", "category")

    private fun norm(s: String): String =
        s.trim().replace(" ", "").replace("　", "").replace("\t", "").lowercase()

    private fun clean(s: String): String {
        val v = s.trim()
        return if (v == "\\") "" else v
    }

    private fun parseByHeader(rows: List<List<String>>): List<QuestionRecord> {
        val headerIdx = findHeaderIndex(rows) ?: return emptyList()
        val header = rows[headerIdx].map { norm(it) }
        val map = header.mapIndexed { i, k -> k to i }.toMap()

        fun col(keys: Iterable<String>): Int? = keys.firstNotNullOfOrNull { map[norm(it)] }

        val titleCol = col(TITLE_KEYS)
        val answerCol = col(ANSWER_KEYS)
        val typeCol = col(TYPE_KEYS)
        val optionCol = col(OPTION_KEYS)
        // 拆分选项列：选项A/B/... 或 A/B/...
        val splitOptCols = mutableListOf<Int>()
        for (letter in 'A'..'H') {
            val c = listOf("选项$letter", "答案$letter", "option$letter", "$letter")
                .firstNotNullOfOrNull { map[norm(it)] }
            if (c != null) splitOptCols.add(c)
        }

        if (titleCol == null || answerCol == null) return emptyList()

        return rows.drop(headerIdx + 1).mapNotNull { row ->
            val title = clean(row.getOrElse(titleCol) { "" })
            val answer = clean(row.getOrElse(answerCol) { "" })
            val type = typeCol?.let { clean(row.getOrElse(it) { "" }) } ?: ""

            val options = when {
                optionCol != null -> splitOptionsToList(clean(row.getOrElse(optionCol) { "" }))
                splitOptCols.isNotEmpty() -> splitOptCols
                    .map { clean(row.getOrElse(it) { "" }) }
                    .map { it.replace(Regex("^[A-Za-z][\\.．、)）\\-]\\s*"), "").trim() }
                    .takeWhile { it.isNotBlank() || splitOptCols.isNotEmpty() }
                    .filter { it.isNotBlank() }
                else -> emptyList()
            }

            val rec = QuestionRecord(
                question = title,
                answer = answer.uppercase().let { a ->
                    // 答案如果是字母组合，统一大写；否则保留原样（简答/填空答案可能含中文/数字）
                    if (a.matches(Regex("^[A-H]{1,8}$"))) a else answer
                },
                options = options,
                type = type
            )
            if (rec.isUsable()) rec else null
        }
    }

    private fun findHeaderIndex(rows: List<List<String>>): Int? {
        var bestIdx: Int? = null
        var bestHits = 0
        for (i in 0 until rows.size.coerceAtMost(10)) {
            val h = rows[i].map { norm(it) }.toSet()
            var hits = 0
            if (h.any { it in TITLE_KEYS }) hits++
            if (h.any { it in ANSWER_KEYS }) hits++
            if (h.any { it in OPTION_KEYS } ||
                h.any { it.matches(Regex("^选项[a-h]$")) || it.matches(Regex("^[a-h]$")) }) hits++
            if (h.any { it in TYPE_KEYS }) hits++
            if (hits > bestHits) {
                bestHits = hits
                bestIdx = i
            }
        }
        return if (bestHits >= 2) bestIdx else null
    }

    // ============ 路径 2：内容启发式识别 ============

    private fun parseByContent(rows: List<List<String>>): List<QuestionRecord> {
        // 判断首行是不是表头：如果首行有 ≥1 个题库关键词，跳过
        val firstRow = rows[0].map { norm(it) }
        val isHeader = firstRow.any { it in TITLE_KEYS || it in ANSWER_KEYS || it in OPTION_KEYS || it in TYPE_KEYS } ||
            firstRow.any { it.matches(Regex("^选项[a-h]$")) || it.matches(Regex("^[a-h]$")) }
        val data = if (isHeader) rows.drop(1) else rows
        if (data.isEmpty()) return emptyList()

        val cols = data[0].size
        val features = (0 until cols).map { c -> ColFeatures.compute(data.map { it.getOrElse(c) { "" } }) }

        // 角色分配
        val roles = assignRoles(features)

        val titleCol = roles.title ?: return emptyList()
        val answerCol = roles.answer ?: return emptyList()

        return data.mapNotNull { row ->
            val title = clean(row.getOrElse(titleCol) { "" })
            val answerRaw = clean(row.getOrElse(answerCol) { "" })
            val type = roles.type?.let { clean(row.getOrElse(it) { "" }) } ?: ""

            val options = when {
                roles.optionCombined != null ->
                    splitOptionsToList(clean(row.getOrElse(roles.optionCombined) { "" }))
                roles.optionSplit.isNotEmpty() ->
                    roles.optionSplit
                        .map { clean(row.getOrElse(it) { "" }) }
                        .map { it.replace(Regex("^[A-Za-z][\\.．、)）\\-]\\s*"), "").trim() }
                        .filter { it.isNotBlank() }
                else -> emptyList()
            }

            val answer = if (answerRaw.matches(Regex("^[A-Ha-h]{1,8}$"))) {
                answerRaw.uppercase()
            } else answerRaw

            val rec = QuestionRecord(
                question = title,
                answer = answer,
                options = options,
                type = type
            )
            if (rec.isUsable()) rec else null
        }
    }

    private data class Roles(
        val title: Int?,
        val answer: Int?,
        val optionCombined: Int?,
        val optionSplit: List<Int>,
        val type: Int?
    )

    private fun assignRoles(features: List<ColFeatures>): Roles {
        val n = features.size
        val used = BooleanArray(n)

        // 1) 序号列（纯数字 + 高单调性）→ 直接屏蔽
        for (i in 0 until n) {
            if (features[i].pctNumeric > 0.9 && features[i].monotonic) used[i] = true
        }

        // 2) 题型列
        val typeCol = (0 until n)
            .filter { !used[it] && features[it].typeKeywordHits >= 2 && features[it].distinctRatio < 0.2 }
            .maxByOrNull { features[it].typeKeywordHits }
        if (typeCol != null) used[typeCol] = true

        // 3) 答案列：优先字母答案；其次"正确/错误"；再其次最右的较短文本列
        val letterAnswerCol = (0 until n)
            .filter { !used[it] && features[it].pctLetterAnswer > 0.6 }
            .maxByOrNull { features[it].pctLetterAnswer }
        val tfAnswerCol = letterAnswerCol ?: (0 until n)
            .filter { !used[it] && features[it].pctTrueFalse > 0.6 }
            .maxByOrNull { features[it].pctTrueFalse }
        var answerCol = tfAnswerCol
        if (answerCol != null) used[answerCol] = true

        // 4) 选项合并列（含 | 或 A./A、 起始）
        val optionCombinedCol = (0 until n)
            .filter { !used[it] && features[it].pctOptionMarker > 0.5 && features[it].avgLen > 4 }
            .maxByOrNull { features[it].pctOptionMarker }
        if (optionCombinedCol != null) used[optionCombinedCol] = true

        // 5) 选项拆分列：找连续 2-5 列的"短文本 + 高独特率"
        val optionSplitCols = mutableListOf<Int>()
        if (optionCombinedCol == null) {
            val candidates = (0 until n).filter { !used[it] && features[it].looksLikeOption() }
            // 找最长连续段（长度 2-6）
            var bestRun: List<Int> = emptyList()
            var run = mutableListOf<Int>()
            for (i in 0 until n) {
                if (i in candidates) {
                    if (run.isEmpty() || run.last() == i - 1) run.add(i) else { run = mutableListOf(i) }
                    if (run.size in 2..6 && run.size > bestRun.size) bestRun = run.toList()
                } else {
                    run = mutableListOf()
                }
            }
            optionSplitCols.addAll(bestRun)
            optionSplitCols.forEach { used[it] = true }
        }

        // 6) 题干列：剩余列里 avgLen 最大且 distinctRatio 高
        val titleCol = (0 until n)
            .filter { !used[it] && features[it].avgLen > 4 && features[it].distinctRatio > 0.5 }
            .maxByOrNull { features[it].avgLen }
        if (titleCol != null) used[titleCol] = true

        // 7) 没找到字母/判断答案 → 简答题路径：剩余文本列作为答案
        if (answerCol == null) {
            answerCol = (0 until n)
                .filter { !used[it] && features[it].avgLen > 2 && features[it].distinctRatio > 0.3 }
                .maxByOrNull { features[it].avgLen }
        }

        return Roles(titleCol, answerCol, optionCombinedCol, optionSplitCols, typeCol)
    }

    // ============ 列特征 ============

    private data class ColFeatures(
        val n: Int,
        val avgLen: Double,
        val maxLen: Int,
        val pctBlank: Double,
        val distinctRatio: Double,
        val pctLetterAnswer: Double,
        val pctTrueFalse: Double,
        val pctNumeric: Double,
        val pctOptionMarker: Double,
        val typeKeywordHits: Int,
        val monotonic: Boolean
    ) {
        fun looksLikeOption(): Boolean {
            // 选项列：短-中等长度、单元格大多有内容、独特率高、不是纯数字也不是单字母
            return n > 0 && avgLen in 1.0..40.0 && pctBlank < 0.4 &&
                distinctRatio > 0.4 && pctLetterAnswer < 0.3 && pctNumeric < 0.5
        }

        companion object {
            private val TYPE_VALUES = setOf(
                "单选题", "多选题", "判断题", "简答题", "填空题", "问答题",
                "名词解释", "论述题", "计算题", "不定项", "单选", "多选", "判断", "简答", "填空"
            )

            fun compute(values: List<String>): ColFeatures {
                val cleaned = values.map { it.trim() }
                val nonBlank = cleaned.filter { it.isNotBlank() }
                val n = cleaned.size
                if (n == 0) return ColFeatures(0, 0.0, 0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, false)

                val nNon = nonBlank.size
                val avgLen = if (nNon > 0) nonBlank.sumOf { it.length.toDouble() } / nNon else 0.0
                val maxLen = nonBlank.maxOfOrNull { it.length } ?: 0
                val pctBlank = (n - nNon).toDouble() / n
                val distinctRatio = if (nNon > 0) nonBlank.toSet().size.toDouble() / nNon else 0.0

                val letterRe = Regex("^[A-Ha-h]{1,8}$")
                val tf = setOf("正确", "错误", "对", "错", "是", "否", "T", "F", "√", "×", "true", "false")
                val numRe = Regex("^\\d+(?:\\.\\d+)?$")
                val markerRe = Regex("[|｜]|^[A-Ha-h][\\.．、)）\\-]")

                val nNonD = nNon.coerceAtLeast(1).toDouble()
                val pctLetter = nonBlank.count { letterRe.matches(it) } / nNonD
                val pctTF = nonBlank.count { it in tf } / nNonD
                val pctNum = nonBlank.count { numRe.matches(it) } / nNonD
                val pctMarker = nonBlank.count { markerRe.containsMatchIn(it) } / nNonD
                val typeHits = nonBlank.count { v ->
                    val nv = v.replace(" ", "").replace("　", "")
                    TYPE_VALUES.any { nv.contains(it) }
                }

                // 单调递增（序号列）
                val nums = nonBlank.mapNotNull { it.toIntOrNull() }
                val monotonic = nums.size >= 3 && nums.size == nonBlank.size &&
                    nums.zipWithNext().all { (a, b) -> b > a }

                return ColFeatures(
                    n = nNon,
                    avgLen = avgLen,
                    maxLen = maxLen,
                    pctBlank = pctBlank,
                    distinctRatio = distinctRatio,
                    pctLetterAnswer = pctLetter,
                    pctTrueFalse = pctTF,
                    pctNumeric = pctNum,
                    pctOptionMarker = pctMarker,
                    typeKeywordHits = typeHits,
                    monotonic = monotonic
                )
            }
        }
    }

    // ============ 选项拆分（合并列） ============

    fun splitOptionsToList(raw: String): List<String> {
        val text = raw.trim().replace('｜', '|')
        if (text.isBlank()) return emptyList()

        val parts = if (text.contains('|') || text.contains('\n')) {
            text.split('|', '\n')
        } else {
            // "A. xx B. xx C. xx" 风格
            Regex("(?=(?<![A-Za-z])[A-Z][\\.．、)）\\-])").split(text)
        }

        return parts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("^[A-Za-z][\\.．、)）\\-]\\s*"), "").trim() }
            .filter { it.isNotBlank() }
    }
}
