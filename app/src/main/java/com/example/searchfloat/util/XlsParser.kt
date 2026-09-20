package com.example.searchfloat.util

import android.content.Context
import android.net.Uri
import jxl.Workbook
import jxl.format.Colour
import jxl.format.RGB

/** 老 Excel .xls / BIFF 解析器：IO + 单元格抽取 + 黄色高亮识别，交给 SmartTableParser。 */
object XlsParser {

    fun parse(context: Context, uri: Uri): List<ParsedRow> =
        parseRecords(context, uri).map { it.toParsedRow() }

    fun parseRecords(context: Context, uri: Uri): List<QuestionRecord> {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val workbook = Workbook.getWorkbook(input)
            try {
                val all = mutableListOf<QuestionRecord>()
                for (sheet in workbook.sheets) {
                    val (rows, highlights) = extractRowsWithFill(sheet)
                    val enriched = SheetEnricher.enrich(sheet.name, rows, highlights)
                    all += SmartTableParser.parse(enriched)
                }
                return all
            } finally {
                workbook.close()
            }
        }
        return emptyList()
    }

    private val YELLOW_COLOURS = setOf(
        Colour.YELLOW, Colour.YELLOW2, Colour.LIGHT_YELLOW, Colour.GOLD,
        Colour.LIGHT_ORANGE
    )

    private fun isYellowRgb(rgb: RGB?): Boolean {
        if (rgb == null) return false
        val r = rgb.red; val g = rgb.green; val b = rgb.blue
        return r >= 200 && g >= 180 && b <= 170 && (r + g) > 2 * b
    }

    private fun extractRowsWithFill(sheet: jxl.Sheet): Pair<List<List<String>>, List<Set<Int>>> {
        val rows = mutableListOf<List<String>>()
        val highlights = mutableListOf<Set<Int>>()
        for (r in 0 until sheet.rows) {
            val row = (0 until sheet.columns).map { c -> sheet.getCell(c, r).contents ?: "" }
            if (row.none { it.trim().isNotBlank() && it.trim() != "\\" }) continue
            val hl = (0 until sheet.columns)
                .filter { c ->
                    val cell = sheet.getCell(c, r)
                    val bg = cell.cellFormat?.backgroundColour
                    val yellow = bg != null && (bg in YELLOW_COLOURS || isYellowRgb(bg.defaultRGB))
                    yellow && cell.contents.trim().isNotBlank()
                }
                .toSet()
            rows.add(row)
            highlights.add(hl)
        }
        return rows to highlights
    }
}
