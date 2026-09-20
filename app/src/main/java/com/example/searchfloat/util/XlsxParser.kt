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
 * Excel (.xlsx / .xlsm / 伪 .xls) 解析器：IO + 单元格抽取 + 高亮识别，
 * 行→记录的识别交给 SmartTableParser。
 *
 * v3.2 变更：
 *   1) 多 sheet 合并（旧版是"取记录最多的一张"，会丢失分 sheet 存放的多题型）。
 *   2) 读取 styles.xml → 记录每个单元格样式对应的 fill 是否为黄色（含浅黄/金色）。
 *   3) 交给 SheetEnricher 前处理：把"黄底=正确答案"翻译成合成答案列；
 *      sheet 名带题型关键词时自动注入题型列。
 */
object XlsxParser {

    class NotXlsxException(msg: String) : Exception(msg)

    fun parse(context: Context, uri: Uri): List<ParsedRow> =
        parseRecords(context, uri).map { it.toParsedRow() }

    fun convertToJsonLines(context: Context, uri: Uri): String =
        JsonlParser.toJsonLines(parseRecords(context, uri))

    fun parseRecords(context: Context, uri: Uri): List<QuestionRecord> {
        val parts = readZipParts(context, uri) ?: throw NotXlsxException("not_xlsx")
        val ss = parts.sharedStrings ?: emptyList()
        val yellowStyles = parts.stylesXml?.let { parseYellowStyleIds(it) } ?: emptySet()

        val all = mutableListOf<QuestionRecord>()
        for ((sheetName, sheetBytes) in parts.sheets) {
            val (rows, highlights) = extractRowsWithFill(sheetBytes, ss, yellowStyles)
            val enriched = SheetEnricher.enrich(sheetName, rows, highlights)
            all += SmartTableParser.parse(enriched)
        }
        return all
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

    private data class ZipParts(
        val sharedStrings: List<String>?,
        val stylesXml: ByteArray?,
        val sheets: List<Pair<String, ByteArray>>
    )

    private fun readZipParts(context: Context, uri: Uri): ZipParts? {
        val raw = context.contentResolver.openInputStream(uri) ?: return null
        val pb = PushbackInputStream(raw, 4)
        val sig = ByteArray(4)
        val n = pb.read(sig)
        if (n < 4) { pb.close(); return null }
        pb.unread(sig, 0, n)

        val isZip = sig[0] == 'P'.code.toByte() &&
            sig[1] == 'K'.code.toByte() &&
            sig[2] == 0x03.toByte() &&
            sig[3] == 0x04.toByte()
        if (!isZip) { pb.close(); return null }

        var sharedStrings: List<String>? = null
        var stylesXml: ByteArray? = null
        var workbookXml: ByteArray? = null
        var workbookRelsXml: ByteArray? = null
        val sheetsByPath = mutableMapOf<String, ByteArray>()

        ZipInputStream(pb).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "xl/sharedStrings.xml" ->
                        sharedStrings = parseSharedStrings(zis.readAllBytesCompat())
                    name == "xl/styles.xml" -> stylesXml = zis.readAllBytesCompat()
                    name == "xl/workbook.xml" -> workbookXml = zis.readAllBytesCompat()
                    name == "xl/_rels/workbook.xml.rels" -> workbookRelsXml = zis.readAllBytesCompat()
                    name.startsWith("xl/worksheets/") && name.endsWith(".xml") ->
                        sheetsByPath[name] = zis.readAllBytesCompat()
                }
                entry = zis.nextEntry
            }
        }
        if (sheetsByPath.isEmpty()) return null

        val sheets = resolveSheetOrder(workbookXml, workbookRelsXml, sheetsByPath)
        return ZipParts(sharedStrings, stylesXml, sheets)
    }

    /** 用 workbook.xml + rels 还原 sheet 顺序 & 名字；失败则按路径排序。 */
    private fun resolveSheetOrder(
        workbookXml: ByteArray?,
        relsXml: ByteArray?,
        byPath: Map<String, ByteArray>
    ): List<Pair<String, ByteArray>> {
        val ridToTarget = mutableMapOf<String, String>()
        if (relsXml != null) {
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(relsXml.inputStream(), "UTF-8")
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id")
                    val target = p.getAttributeValue(null, "Target")
                    if (id != null && target != null) ridToTarget[id] = target
                }
                p.next()
            }
        }
        val ordered = mutableListOf<Pair<String, ByteArray>>()
        if (workbookXml != null) {
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(workbookXml.inputStream(), "UTF-8")
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                    val name = p.getAttributeValue(null, "name") ?: ""
                    val rid = p.getAttributeValue(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"
                    ) ?: p.getAttributeValue(null, "id") ?: ""
                    val target = ridToTarget[rid] ?: ""
                    val cleaned = target.trimStart('/').removePrefix("../").removePrefix("xl/")
                    val fullPath = "xl/$cleaned"
                    val bytes = byPath[fullPath]
                    if (bytes != null) ordered.add(name to bytes)
                }
                p.next()
            }
        }
        if (ordered.isNotEmpty()) return ordered
        return byPath.entries.sortedBy { it.key }
            .map { it.key.substringAfterLast('/').removeSuffix(".xml") to it.value }
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

    // ==== styles.xml：找出所有"实心黄色填充"的样式索引 =================

    private fun parseYellowStyleIds(bytes: ByteArray): Set<Int> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        val fillYellow = mutableListOf<Boolean>()   // fillId -> isYellow
        val cellXfFillIds = mutableListOf<Int>()    // styleIdx -> fillId

        var inFills = false
        var inCellXfs = false
        var patternType: String? = null
        var fillIsYellow = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "fills" -> inFills = true
                    "cellXfs" -> inCellXfs = true
                    "fill" -> if (inFills) { patternType = null; fillIsYellow = false }
                    "patternFill" -> if (inFills) {
                        patternType = parser.getAttributeValue(null, "patternType")
                    }
                    "fgColor" -> if (inFills) {
                        val rgb = parser.getAttributeValue(null, "rgb")
                        if (rgb != null && isYellow(rgb)) fillIsYellow = true
                    }
                    "xf" -> if (inCellXfs) {
                        val fid = (parser.getAttributeValue(null, "fillId") ?: "0").toIntOrNull() ?: 0
                        cellXfFillIds.add(fid)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "fill" -> if (inFills) {
                        fillYellow.add(patternType == "solid" && fillIsYellow)
                    }
                    "fills" -> inFills = false
                    "cellXfs" -> inCellXfs = false
                }
            }
            parser.next()
        }

        val yellowFillIds = fillYellow
            .mapIndexedNotNull { idx, y -> if (y) idx else null }.toSet()
        return cellXfFillIds
            .mapIndexedNotNull { styleIdx, fid -> if (fid in yellowFillIds) styleIdx else null }
            .toSet()
    }

    /** RGB(A) 十六进制字符串 → 是否"够黄"。宽松规则以吃下浅黄/金色。 */
    private fun isYellow(rgb: String): Boolean {
        val hex = rgb.takeLast(6)
        if (hex.length != 6) return false
        val r = hex.substring(0, 2).toIntOrNull(16) ?: return false
        val g = hex.substring(2, 4).toIntOrNull(16) ?: return false
        val b = hex.substring(4, 6).toIntOrNull(16) ?: return false
        // 红/绿都够高、蓝低，且 R+G 明显大于 2B → 黄色系
        return r >= 200 && g >= 180 && b <= 170 && (r + g) > 2 * b
    }

    // ==== sheet → List<List<String>> + 每行高亮列集合 =================

    private fun extractRowsWithFill(
        bytes: ByteArray,
        sharedStrings: List<String>,
        yellowStyleIds: Set<Int>
    ): Pair<List<List<String>>, List<Set<Int>>> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        val allRows = mutableListOf<List<String>>()
        val allHl = mutableListOf<Set<Int>>()
        var currentRow = mutableListOf<String>()
        var currentHl = mutableSetOf<Int>()
        val cellValue = StringBuilder()
        var inV = false
        var inInlineString = false
        var inInlineText = false
        var cellType = ""
        var currentColIndex = 0
        var currentStyle = 0

        fun colIndexFromRef(ref: String): Int {
            var idx = 0
            for (c in ref) {
                if (!c.isLetter()) break
                idx = idx * 26 + (c.uppercaseChar() - 'A' + 1)
            }
            return (idx - 1).coerceAtLeast(0)
        }

        fun push(value: String) {
            while (currentRow.size < currentColIndex) currentRow.add("")
            if (currentRow.size == currentColIndex) currentRow.add(value)
            else currentRow[currentColIndex] = value
            if (currentStyle in yellowStyleIds && value.trim().isNotBlank()) {
                currentHl.add(currentColIndex)
            }
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        currentColIndex = colIndexFromRef(parser.getAttributeValue(null, "r") ?: "")
                        currentStyle = (parser.getAttributeValue(null, "s") ?: "0").toIntOrNull() ?: 0
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
                        push(real)
                    }
                    "t" -> if (inInlineString) inInlineText = false
                    "is" -> { inInlineString = false; push(cellValue.toString()) }
                    "row" -> {
                        if (currentRow.any { it.trim().isNotBlank() && it.trim() != "\\" }) {
                            allRows.add(currentRow.toList())
                            allHl.add(currentHl.toSet())
                        }
                        currentRow = mutableListOf()
                        currentHl = mutableSetOf()
                    }
                }
            }
            parser.next()
        }
        return allRows to allHl
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
