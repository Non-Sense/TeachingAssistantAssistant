import arrow.core.escaped
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.*
import org.apache.xmlbeans.XmlObject
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCfRule
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDataBar
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.nio.file.Path
import kotlin.io.path.absolutePathString


fun writeDetail(
    outputStream: BufferedWriter,
    testResults: List<TestResult>,
    workspacePath: Path,
    resultTable: TestResultTable,
    studentNameTable: Map<StudentID, String>,
    detailHeader: List<String>,
    config: Config
) {
    outputStream.appendLine(detailHeader.joinToString(","))
    for(result in testResults) {
        val detailRow = DetailRow(result, studentNameTable, workspacePath, resultTable)
        outputStream.appendLine(detailRow.getCsvString(config))
    }
}

fun writeSummary(
    tasks: Map<TaskName, Task>,
    outputStream: BufferedWriter,
    resultTable: TestResultTable,
    studentNameTable: Map<StudentID, String>
) {
    val summaryHeader = SummaryHeader(tasks)
    outputStream.appendLine(summaryHeader.headers.joinToString())
    resultTable.forEach { row ->
        val summaryRow = SummaryRow(row, studentNameTable, summaryHeader)
        outputStream.appendLine(summaryRow.getCsvString())
    }
}

private fun String.removeCarriageReturn(): String {
    return this.replace("\r", "")
}

private fun String.csvFormat(): String {
    return this.replace(',', ' ').escaped()
}

private class DetailRow(
    result: TestResult,
    studentNameTable: Map<StudentID, String>,
    workspacePath: Path,
    resultTable: TestResultTable
) {
    val studentId = result.judgeInfo.studentID.id
    val studentName = studentNameTable[result.judgeInfo.studentID] ?: ""
    val sourcePath = result.judgeInfo.javaSource.absolutePath.removeRange(0..workspacePath.absolutePathString().length)
    val packageName = result.judgeInfo.compileResult.packageName()?.name ?: ""
    val className = result.judgeInfo.className ?: ""
    val taskName = result.judgeInfo.taskName ?: TaskName("")
    val compileError = when(val it = result.judgeInfo.compileResult) {
        is CompileResultDetail.Error -> "InternalError:\nUTF8:\n${it.utf8Error.error}\nShiftJIS:\n${it.sJisError.error}".removeCarriageReturn()
        is CompileResultDetail.Failure -> it.errorMessage.removeCarriageReturn()
        is CompileResultDetail.Success -> ""
    }
    val compileCommand = when(val it = result.judgeInfo.compileResult) {
        is CompileResultDetail.Error -> "UTF8:${it.utf8Error.compileCommand}\tShiftJIS:${it.sJisError.compileCommand}"
        is CompileResultDetail.Failure -> it.compileCommand
        is CompileResultDetail.Success -> it.compileCommand
    }
    val failedCompileCommand = when(val it = result.judgeInfo.compileResult) {
        is CompileResultDetail.Error -> null
        is CompileResultDetail.Failure -> it.prevCompileCommand
        is CompileResultDetail.Success -> it.prevCompileCommand
    } ?: ""
    val command = result.command
    val arg = result.testCase?.arg ?: ""
    val input = result.testCase?.input?.joinToString("\n")?.removeCarriageReturn() ?: ""
    val expect = result.testCase?.expect?.joinToString("\n")?.removeCarriageReturn() ?: ""
    val output = result.output.trimEnd().removeCarriageReturn()
    val errorOutput = result.errorOutput.trimEnd().removeCarriageReturn()
    val testCaseName = result.testCase?.name ?: ""
    val time = result.runTimeNano?.let { it/1000000f }
    val stat = if(result.judgeInfo.taskName == null) Judge.NF.toString() else resultTable.get(
        result.judgeInfo.studentID,
        taskName,
        result.testCase,
        result.judgeInfo.javaSource.toPath()
    )?.toString() ?: "__"

    fun getCsvString(config: Config): String {
        return """$studentId,$studentName,$sourcePath,$taskName,$testCaseName,$arg,${input.csvFormat()},$stat,$time,${expect.csvFormat()},${output.csvFormat()},${errorOutput.csvFormat()},${compileError.csvFormat()},$packageName,$className,$command,$compileCommand""".let {
            if(config.allowAmbiguousClassPath)
                "$it,$failedCompileCommand"
            else
                it
        }

    }
}

private class SummaryHeader(
    tasks: Map<TaskName, Task>
) {
    val taskNames = tasks.mapValues {
        it.value.testcase.size
    }
    val testCaseList = tasks.flatMap {
        (it.value.testcase).map { testCase ->
            "${it.key}:${testCase.name}"
            it.key to testCase
        }
    }
    val headers: List<String> = mutableListOf<String>().apply {
        add("ID")
        add("Name")
        addAll(taskNames.map { it.key.name })
        addAll(testCaseList.map { "${it.first}:${it.second.name}" })
    }
}

private class SummaryRow(
    result: TestResultTable.Element,
    studentNameTable: Map<StudentID, String>,
    summaryHeader: SummaryHeader
) {
    val studentID = result.studentID
    val studentName = studentNameTable[studentID] ?: ""
    private val data = result.value

    val stats = summaryHeader.testCaseList.map {
        val t = data[TestResultTableKey(it.first, it.second)]
        val s = data[TestResultTableKey(it.first, null)]
        when {
            t?.size == 1 -> t.values.first()
            t != null && t.size > 1 -> Judge.CF
            s != null -> s.values.first()
            else -> Judge.NF
        }
    }
    val acceptRatio = summaryHeader.taskNames.mapValues { n ->
        summaryHeader.testCaseList.filter { it.first == n.key }
            .count { data[TestResultTableKey(it.first, it.second)]?.count { (_, k) -> k == Judge.AC } == 1 } to n.value
    }.map { (it.value.first.toFloat()/it.value.second.toFloat()*100f).toInt().toString() }

    fun getCsvString(): String {
        return """$studentID,$studentName,${acceptRatio.joinToString()},${stats.joinToString()}"""
    }
}

fun writeDetailAndSummaryExcel(
    outputFileStream: FileOutputStream,
    testResults: List<TestResult>,
    workspacePath: Path,
    resultTable: TestResultTable,
    studentNameTable: Map<StudentID, String>,
    tasks: Map<TaskName, Task>,
    detailHeader: List<String>,
    config: Config
) {
    WorkbookFactory.create(true).let { it as XSSFWorkbook }.use { workBook ->
        val sheet = workBook.createSheet("Detail")
        sheet.createRow(0).let { row ->
            detailHeader.forEachIndexed { index, s ->
                row.createCell(index, CellType.STRING).apply {
                    setCellValue(s)
                }
            }
        }
        val dataFormat = workBook.createDataFormat()
        val timeStyle = workBook.createCellStyle().apply {
            setDataFormat(dataFormat.getFormat("#.000"))
        }

        val wrapStyle = workBook.createCellStyle().apply {
            wrapText = true
        }

        testResults.forEachIndexed { index, result ->
            val detailRow = DetailRow(result, studentNameTable, workspacePath, resultTable)
            sheet.createRow(index + 1).let { row ->
                row.createCell(0, CellType.STRING).setCellValue(detailRow.studentId)
                row.createCell(1, CellType.STRING).setCellValue(detailRow.studentName)
                row.createCell(2, CellType.STRING).setCellValue(detailRow.sourcePath)
                row.createCell(3, CellType.STRING).setCellValue(detailRow.taskName.name)
                row.createCell(4, CellType.STRING).setCellValue(detailRow.testCaseName)
                row.createCell(5, CellType.STRING).setCellValue(detailRow.arg)
                row.createCell(6, CellType.STRING).setCellValue(detailRow.input)
                row.createCell(7, CellType.STRING).setCellValue(detailRow.stat)
                if(detailRow.time == null) {
                    row.createCell(8, CellType.BLANK).setBlank()
                } else {
                    row.createCell(8, CellType.NUMERIC).apply {
                        setCellValue(detailRow.time.toDouble())
                        this.cellStyle = timeStyle
                    }
                }
                row.createCell(9, CellType.STRING).setCellValue(detailRow.expect)
                row.createCell(10, CellType.STRING).apply {
                    setCellValue(detailRow.output)
                    cellStyle = wrapStyle
                }
                row.createCell(11, CellType.STRING).apply {
                    setCellValue(detailRow.errorOutput)
                    cellStyle = wrapStyle
                }
                row.createCell(12, CellType.STRING).apply {
                    setCellValue(detailRow.compileError)
                    cellStyle = wrapStyle
                }
                row.createCell(13, CellType.STRING).setCellValue(detailRow.packageName)
                row.createCell(14, CellType.STRING).setCellValue(detailRow.className)
                row.createCell(15, CellType.STRING).setCellValue(detailRow.command)
                row.createCell(16, CellType.STRING).setCellValue(detailRow.compileCommand)
                if(config.allowAmbiguousClassPath)
                    row.createCell(17, CellType.STRING).setCellValue(detailRow.failedCompileCommand)
            }
        }

        val summarySheet = workBook.createSheet("Summary")
        val summaryHeader = SummaryHeader(tasks)

        summarySheet.createRow(0).let { row ->
            summaryHeader.headers.forEachIndexed { index, s ->
                row.createCell(index, CellType.STRING).setCellValue(s)
            }
        }
        var count = 1

        resultTable.forEach { row ->
            val summaryRow = SummaryRow(row, studentNameTable, summaryHeader)
            summarySheet.createRow(count).apply {
                createCell(0, CellType.STRING).setCellValue(summaryRow.studentID.toString())
                createCell(1, CellType.STRING).setCellValue(summaryRow.studentName)
                summaryRow.acceptRatio.forEachIndexed { index, i ->
                    createCell(index + 2, CellType.NUMERIC).setCellValue(i.toDouble())
                }
                summaryRow.stats.forEachIndexed { index, judge ->
                    createCell(index + 2 + summaryRow.acceptRatio.size, CellType.STRING).setCellValue(judge.toString())
                }
            }
            count += 1
        }

        fun SheetConditionalFormatting.create(
            judge: Judge,
            background: Color,
            fontColor: Color,
        ): ConditionalFormattingRule {
            return createConditionalFormattingRule("""EXACT("$judge",INDIRECT(ADDRESS(ROW(),COLUMN())))""").apply {
                createPatternFormatting().apply {
                    setFillBackgroundColor(background)
                    fillPattern = PatternFormatting.SOLID_FOREGROUND
                }
                createFontFormatting().fontColor = fontColor
            }
        }

        fun XSSFColor(r: Int, g: Int, b: Int): XSSFColor {
            return XSSFColor(byteArrayOf(r.toByte(), g.toByte(), b.toByte()))
        }

        fun XSSFSheet.setStatCond(range: CellRangeAddress) {
            val sheetCF = sheetConditionalFormatting
            listOf(
                sheetCF.create(Judge.AC, XSSFColor(198, 239, 206), XSSFColor(0, 97, 0)),
                sheetCF.create(Judge.WA, XSSFColor(255, 235, 156), XSSFColor(156, 87, 0)),
                sheetCF.create(Judge.TLE, XSSFColor(255, 235, 156), XSSFColor(156, 87, 0)),
                sheetCF.create(Judge.RE, XSSFColor(255, 199, 206), XSSFColor(156, 0, 6)),
                sheetCF.create(Judge.CE, XSSFColor(255, 199, 206), XSSFColor(156, 0, 6)),
                sheetCF.create(Judge.IE, XSSFColor(255, 255, 255), XSSFColor(0, 0, 0)),
                sheetCF.create(Judge.NF, XSSFColor(191, 191, 191), XSSFColor(0, 0, 0))
            ).forEach {
                sheetCF.addConditionalFormatting(arrayOf(range), it)
            }

        }

        val statIndex = detailHeader.indexOf("stat")
        sheet.setStatCond(CellRangeAddress(1, testResults.size, statIndex, statIndex))
        sheet.setAutoFilter(CellRangeAddress(0, 0, 0, detailHeader.size - 1))
        detailHeader.indices.forEach { sheet.autoSizeColumn(it) }

        val summaryLastCol = summaryHeader.headers.size - 1
        summarySheet.setStatCond(
            CellRangeAddress(1, resultTable.size(), summaryHeader.taskNames.size + 2, summaryLastCol)
        )
        summarySheet.setAutoFilter(CellRangeAddress(0, 0, 0, summaryLastCol))
        val color = workBook.creationHelper.createExtendedColor().apply {
            this.rgb = byteArrayOf(99, 142.toByte(), 198.toByte())
        }
        applyDataBars(
            summarySheet.sheetConditionalFormatting,
            CellRangeAddress(1, resultTable.size(), 2, summaryHeader.taskNames.size + 1),
            color
        )
        workBook.write(outputFileStream)
    }
}


@Throws(Exception::class)
private fun applyDataBars(sheetCF: SheetConditionalFormatting, region: CellRangeAddress, color: ExtendedColor?) {
    val regions = arrayOf(region)
    val rule = sheetCF.createConditionalFormattingRule(color)
    val dbf = rule.dataBarFormatting
    dbf.minThreshold.rangeType = ConditionalFormattingThreshold.RangeType.NUMBER
    dbf.minThreshold.value = 0.0
    dbf.maxThreshold.rangeType = ConditionalFormattingThreshold.RangeType.NUMBER
    dbf.maxThreshold.value = 100.0
    dbf.isIconOnly = false
    dbf.widthMin =
        0 //cannot work for XSSFDataBarFormatting, see https://svn.apache.org/viewvc/poi/tags/REL_4_0_1/src/ooxml/java/org/apache/poi/xssf/usermodel/XSSFDataBarFormatting.java?view=markup#l57
    dbf.widthMax =
        100 //cannot work for XSSFDataBarFormatting, see https://svn.apache.org/viewvc/poi/tags/REL_4_0_1/src/ooxml/java/org/apache/poi/xssf/usermodel/XSSFDataBarFormatting.java?view=markup#l64
    if(dbf is XSSFDataBarFormatting) {
        val databar: Field = XSSFDataBarFormatting::class.java.getDeclaredField("_databar")
        databar.isAccessible = true
        val ctDataBar = databar.get(dbf) as CTDataBar
        ctDataBar.minLength = 0
        ctDataBar.maxLength = 100
    }

    // use extension from x14 namespace to set data bars not using gradient color
    if(rule is XSSFConditionalFormattingRule) {
        val cfRule: Field = XSSFConditionalFormattingRule::class.java.getDeclaredField("_cfRule")
        cfRule.isAccessible = true
        val ctRule = cfRule.get(rule) as CTCfRule
        var extList = ctRule.addNewExtLst()
        var ext = extList.addNewExt()
        var extXML = ("<x14:id"
                + " xmlns:x14=\"http://schemas.microsoft.com/office/spreadsheetml/2009/9/main\">"
                + "{00000000-000E-0000-0000-000001000000}"
                + "</x14:id>")
        var xlmObject = XmlObject.Factory.parse(extXML)
        ext.set(xlmObject)
        ext.uri = "{B025F937-C7B1-47D3-B67F-A62EFF666E3E}"
        val _sh: Field = XSSFConditionalFormattingRule::class.java.getDeclaredField("_sh")
        _sh.setAccessible(true)
        val sheet = _sh.get(rule) as XSSFSheet
        extList = sheet.ctWorksheet.addNewExtLst()
        ext = extList.addNewExt()
        extXML =
            ("<x14:conditionalFormattings xmlns:x14=\"http://schemas.microsoft.com/office/spreadsheetml/2009/9/main\">"
                    + "<x14:conditionalFormatting xmlns:xm=\"http://schemas.microsoft.com/office/excel/2006/main\">"
                    + "<x14:cfRule type=\"dataBar\" id=\"{00000000-000E-0000-0000-000001000000}\">"
                    + "<x14:dataBar minLength=\"" + 0 + "\" maxLength=\"" + 100 + "\" gradient=\"" + false + "\">"
                    + "<x14:cfvo type=\"num\"><xm:f>" + 0 + "</xm:f></x14:cfvo>"
                    + "<x14:cfvo type=\"num\"><xm:f>" + 100 + "</xm:f></x14:cfvo>"
                    + "</x14:dataBar>"
                    + "</x14:cfRule>"
                    + "<xm:sqref>" + region.formatAsString() + "</xm:sqref>"
                    + "</x14:conditionalFormatting>"
                    + "</x14:conditionalFormattings>")
        xlmObject = XmlObject.Factory.parse(extXML)
        ext.set(xlmObject)
        ext.uri = "{78C0D931-6437-407d-A8EE-F0AAD7539E65}"
    }
    sheetCF.addConditionalFormatting(regions, rule)
}
