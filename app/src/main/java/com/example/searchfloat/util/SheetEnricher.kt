package com.example.searchfloat.util

/**
 * 表格前处理器：负责在 SmartTableParser 之前，把"色标答案 + sheet 名题型"这类
 * 不在原始文本里的信息，翻译成 SmartTableParser 能吃的常规表头/列。
 *
 * 触发条件（两条独立）：
 *   1) 该 sheet 有任意黄色高亮单元格且没有显式"答案"列 → 追加一个合成"答案"列，
 *      内容根据高亮所在的选项列位置生成字母（A / AB / ABCD…）。
 *   2) sheet 名带题型关键词（单选/多选/判断/简答/填空/名词解释/论述/计算）→
 *      在最前面插入一个"题型"列，值统一填 sheet 对应的规范题型。
 *
 * 无高亮且无题型关键词时原样返回，不改变行为。
 */
object SheetEnricher {
    private val TYPE_ORDER = listOf(
        "单选题" to "单选题", "多选题" to "多选题", "判断题" to "判断题",
        "简答题" to "简答题", "填空题" to "填空题", "名词解释" to "名词解释",
        "论述题" to "论述题", "计算题" to "计算题",
        "单选" to "单选题", "多选" to "多选题", "判断" to "判断题",
        "简答" to "简答题", "填空" to "填空题", "问答" to "简答题",
        "论述" to "论述题", "计算" to "计算题"
    )

    private val TITLE_HINT = listOf("题干", "题目", "试题内容", "题目内容", "试题")
    private val TYPE_HINT = listOf("题型", "类型")
    private val ID_HINT = listOf("编号", "序号", "id", "no.", "试题编码")
    private val META_HINT = listOf("分类", "章节", "知识点", "来源", "章")

    fun enrich(
        sheetName: String,
        rows: List<List<String>>,
        highlights: List<Set<Int>>
    ): List<List<String>> {
        if (rows.isEmpty()) return rows
        val hasAnyHighlight = highlights.any { it.isNotEmpty() }
        val sheetType = inferType(sheetName)
        if (!hasAnyHighlight && sheetType == null) return rows

        // 找表头行：题目/题干/试题… 关键词
        val nCols = rows.maxOf { it.size }
        val headerIdx = rows.indexOfFirst { r ->
            r.any { c -> normalize(c).let { n -> TITLE_HINT.any { n.contains(it) } } }
        }.let { if (it < 0) 0 else it }

        val header = MutableList(nCols) { i -> rows[headerIdx].getOrElse(i) { "" } }
        val titleCol = header.indexOfFirst { c ->
            normalize(c).let { n -> TITLE_HINT.any { n.contains(it) } }
        }
        val answerColExists = header.any { c ->
            val n = normalize(c)
            n.contains("答案") && !n.contains("选项")
        }

        // 排除非选项列
        val excluded = mutableSetOf<Int>()
        for (i in 0 until nCols) {
            val n = normalize(header[i])
            val isAnswerHdr = n.contains("答案") && !n.contains("选项")
            if (isAnswerHdr || TYPE_HINT.any { n.contains(it) } ||
                ID_HINT.any { n == it || n.contains(it) } ||
                META_HINT.any { n.contains(it) }
            ) excluded.add(i)
        }
        val optionCols = if (titleCol >= 0)
            (titleCol + 1 until nCols).filter { it !in excluded }
        else emptyList()

        val injectAnswer = hasAnyHighlight && !answerColExists && optionCols.isNotEmpty()

        // 输出行
        val out = mutableListOf<List<String>>()

        // 新表头：把 title/option 列名归一，方便 SmartTableParser 表头路径命中
        val newHeader = MutableList(nCols) { i -> header[i] }
        if (titleCol >= 0) newHeader[titleCol] = "题目"
        optionCols.forEachIndexed { idx, col ->
            if (idx < 8) newHeader[col] = "选项${('A' + idx)}"
        }
        if (injectAnswer) newHeader.add("答案")
        if (sheetType != null) newHeader.add(0, "题型")
        out.add(newHeader)

        // 数据行
        for (i in (headerIdx + 1) until rows.size) {
            val src = rows[i]
            val hl = highlights.getOrElse(i) { emptySet() }
            val newRow = MutableList(nCols) { j -> src.getOrElse(j) { "" } }
            if (injectAnswer) {
                val letters = optionCols
                    .mapIndexedNotNull { idx, col -> if (col in hl) idx else null }
                    .joinToString("") { ('A' + it).toString() }
                newRow.add(letters)
            }
            if (sheetType != null) newRow.add(0, sheetType)
            out.add(newRow)
        }
        return out
    }

    private fun normalize(s: String): String =
        s.trim().replace(" ", "").replace("　", "").replace("\t", "").lowercase()

    private fun inferType(sheetName: String): String? {
        val n = sheetName.replace(" ", "").replace("　", "")
        for ((k, v) in TYPE_ORDER) if (n.contains(k)) return v
        return null
    }
}
