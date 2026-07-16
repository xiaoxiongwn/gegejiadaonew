package com.example.searchfloat.util

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream

data class ParsedRow(val title: String, val category: String, val content: String)

/**
 * Excel (.xlsx / .xlsm / 伪 .xls) 解析器：负责 IO + 单元格抽取，
 * 行→记录的识别全部交给 SmartTableParser。
 *
 * 不看扩展名、不看 MIME，先按文件头判断是否 ZIP/xlsx，解决"WPS .xls 实为 xlsx"的坑。
 */
object XlsxParser {

    class NotXlsxException(msg: String) : Exception(msg)

    fun parse(context: Context, uri: Uri): List<ParsedRow> =
        parseRecords(context, uri).map { it.toParsedRow() }

    /** 把 Excel 直接转换成商业 App 类似的 TXT/JSON Lines 文本。 */
    fun convertToJsonLines(context: Context, uri: Uri): String =
        JsonlParser.toJsonLines(parseRecords(context, uri))

    /** Excel -> 统一中间结构。 */
    fun parseRecords(context: Context, uri: Uri): List<QuestionRecord> {
        val (sharedStrings, sheetXmls) = readZipParts(context, uri)
            ?: throw NotXlsxException("not_xlsx")
        val ss = sharedStrings ?: emptyList()

        // 多 sheet：选有效题数最多的那张
        var best: List<QuestionRecord> = emptyList()
        for (xml in sheetXmls) {
            val rows = extractRows(xml, ss)
            val records = SmartTableParser.parse(rows)
            if (records.size > best.size) best = records
        }
        return best
    }

    fun parseCsv(context: Context, uri: Uri): List<ParsedRow> {
        val rows = mutableListOf<List<String>>()
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            for (line in reader.readLines()) {
                if (line.isBlank()) continue
                rows.add(parseCsvLine(line))
            }
        }
        return SmartTableParser.parse(rows).map { it.toParsedRow() }
    }

    // ==== ZIP/xlsx 拆包 ===============================================

    private fun readZipParts(context: Context, uri: Uri): Pair<List<String>?, List<ByteArray>>? {
        val raw = context.contentResolver.openInputStream(uri) ?: return null
        val pb = PushbackInputStream(raw, 4)
        val sig = ByteArray(4)
        val n = pb.read(sig)
        if (n < 4) {
            pb.close()
            return null
        }
        pb.unread(sig, 0, n)

        val isZip = sig[0] == 'P'.code.toByte() &&
            sig[1] == 'K'.code.toByte() &&
            sig[2] == 0x03.toByte() &&
            sig[3] == 0x04.toByte()
        if (!isZip) {
            pb.close()
            return null
        }

        var sharedStrings: List<String>? = null
        val sheetXmls = mutableListOf<ByteArray>()

        ZipInputStream(pb).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "xl/sharedStrings.xml" -> sharedStrings = parseSharedStrings(zis.readAllBytesCompat())
                    name.startsWith("xl/worksheets/") && name.endsWith(".xml") -> sheetXmls.add(zis.readAllBytesCompat())
                }
                entry = zis.nextEntry
            }
        }
        if (sheetXmls.isEmpty()) return null
        return sharedStrings to sheetXmls
    }

    private fun InputStream.readAllBytesCompat(): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (true) {
            val n = read(tmp)
            if (n <= 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        val cur = StringBuilder()
        var inT = false
        var inSi = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { cur.setLength(0); inSi = true }
                    "t" -> if (inSi) inT = true
                }
                XmlPullParser.TEXT -> if (inT) cur.append(parser.text ?: "")
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inT = false
                    "si" -> { list.add(cur.toString()); inSi = false }
                }
            }
            parser.next()
        }
        return list
    }

    /** sheet → List<List<String>> 二维表 */
    private fun extractRows(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        val allRows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val cellValue = StringBuilder()
        var inV = false
        var inInlineString = false
        var inInlineText = false
        var cellType = ""
        var currentColIndex = 0

        fun colIndexFromRef(ref: String): Int {
            var idx = 0
            for (c in ref) {
                if (!c.isLetter()) break
                idx = idx * 26 + (c.uppercaseChar() - 'A' + 1)
            }
            return (idx - 1).coerceAtLeast(0)
        }

        fun pushCell(value: String) {
            while (currentRow.size < currentColIndex) currentRow.add("")
            if (currentRow.size == currentColIndex) currentRow.add(value)
            else currentRow[currentColIndex] = value
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        currentColIndex = colIndexFromRef(parser.getAttributeValue(null, "r") ?: "")
                    }
                    "v" -> { inV = true; cellValue.setLength(0) }
                    "is" -> { inInlineString = true; cellValue.setLength(0) }
                    "t" -> if (inInlineString) inInlineText = true
                }
                XmlPullParser.TEXT -> {
                    if (inV || inInlineText) cellValue.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> {
                        inV = false
                        val raw = cellValue.toString()
                        val real = if (cellType == "s") {
                            val i = raw.toIntOrNull() ?: -1
                            if (i in sharedStrings.indices) sharedStrings[i] else raw
                        } else raw
                        pushCell(real)
                    }
                    "t" -> if (inInlineString) inInlineText = false
                    "is" -> { inInlineString = false; pushCell(cellValue.toString()) }
                    "row" -> {
                        if (currentRow.any { it.trim().isNotBlank() && it.trim() != "\\" }) {
                            allRows.add(currentRow.toList())
                        }
                        currentRow = mutableListOf()
                    }
                }
            }
            parser.next()
        }
        return allRows
    }

    // ==== CSV =========================================================

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
