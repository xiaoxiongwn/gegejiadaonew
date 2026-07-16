package com.example.searchfloat.util

import android.content.Context
import android.net.Uri
import jxl.Workbook

/** 老 Excel .xls / BIFF 解析器：负责 IO + 单元格抽取，识别交给 SmartTableParser。 */
object XlsParser {

    fun parse(context: Context, uri: Uri): List<ParsedRow> =
        parseRecords(context, uri).map { it.toParsedRow() }

    fun parseRecords(context: Context, uri: Uri): List<QuestionRecord> {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val workbook = Workbook.getWorkbook(input)
            try {
                var best: List<QuestionRecord> = emptyList()
                for (sheet in workbook.sheets) {
                    val rows = extractRows(sheet)
                    val records = SmartTableParser.parse(rows)
                    if (records.size > best.size) best = records
                }
                return best
            } finally {
                workbook.close()
            }
        }
        return emptyList()
    }

    private fun extractRows(sheet: jxl.Sheet): List<List<String>> {
        val out = mutableListOf<List<String>>()
        for (r in 0 until sheet.rows) {
            val row = (0 until sheet.columns).map { c -> sheet.getCell(c, r).contents ?: "" }
            if (row.any { it.trim().isNotBlank() && it.trim() != "\\" }) out.add(row)
        }
        return out
    }
}
