import arrow.core.Either
import arrow.core.escaped
import arrow.core.left
import arrow.core.right
import com.charleskorn.kaml.Yaml
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.*
import org.apache.xmlbeans.XmlObject
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCfRule
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDataBar
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

enum class Encoding(val charset: Charset) {
    UTF8(Charset.forName("UTF-8")),
    ShiftJIS(Charset.forName("Shift_JIS")),
    Unknown(Charset.defaultCharset())
}

enum class Judge(private val displayName: String) {
    AC("AC"),
    WA("WA"),
    TLE("TLE"),
    RE("RE"),
    CE("CE"),
    IE("IE"),
    NF("NF"),
    Unknown("??");

    companion object {
        fun fromStringInput(input: String): Judge {
            return when(input) {
                "AC", "A", "ac", "a" -> AC
                "WA", "W", "wa", "w" -> WA
                "RE", "R", "re", "r" -> RE
                "CE", "C", "ce", "c" -> CE
                else -> Unknown
            }
        }
    }

    override fun toString(): String {
        return displayName
    }
}

data class CompileResult(
    val result: Either<String, String>,
    val encoding: Encoding,
    val javaSource: File,
    val studentBase: File,
    val base: File,
    val dstDirectory: Path
)

data class InfoForJudge(
    val studentID: String,
    val taskName: String?,
    val classFileDir: Path,
    val className: String?,
    val packageName: String?,
    val compileResult: Either<String, String>,
    val javaSource: File
)

data class TestResult(
    val output: String = "",
    val errorOutput: String = "",
    val exitCode: Int = -1,
    val judge: Judge = Judge.Unknown,
    val judgeInfo: InfoForJudge,
    val testCase: TestCase = TestCase(""),
    val command: String = "",
    val runTimeNano: Long? = null
)

@Serializable
data class TestCase(
    val name: String,
    val arg: String = "",
    val input: List<String> = listOf(),
    val expect: List<String> = listOf()
) {
    @Transient
    val expectRegex = expect.map { Regex(it) }
}

@Serializable
data class Task(
    val word: List<String>,
    val testcase: List<TestCase> = listOf()
)

@Serializable
data class Config(
    val compileTimeout: Long,
    val runningTimeout: Long,
    val outputFileName: String = "output",
    val tasks: Map<String, Task>
)


suspend fun main(args: Array<String>) {
    val parser = ArgParser("TeachingAssistantAssistant")
    val rootPath by parser.argument(
        ArgType.String,
        fullName = "rootPath",
        description = "MoodleからダウンロードしたZipを展開したディレクトリを指定する。~~~_assignsubmission_file_が並んでいるところ"
    )
    val configPath by parser.argument(
        ArgType.String,
        fullName = "configFile",
        description = "コンフィグファイル(yaml)のファイルパスを指定する。"
    )
    val outputPath by parser.argument(
        ArgType.String,
        fullName = "outputDirectory",
        description = "結果の出力先ディレクトリを指定する。"
    )
    val nonKeepOriginalDirectory by parser.option(
        ArgType.Boolean,
        fullName = "nonKeepDirectory",
        shortName = "k",
        description = "コンパイル時に元のディレクトリ構成を維持しないでコンパイルする(packageが使えなくなる)"
    )
    val stepLevelArg by parser.option(
        ArgType.Int,
        fullName = "stepLevel",
        shortName = "l",
        description = "実行ステップの指定。0=zip展開まで  1=コンパイルまで  2=実行&テストまで(デフォルト)  展開/コンパイルの結果は指定したrootPath直下workspaceにあります"
    )
    val runTestsSerial by parser.option(
        ArgType.Boolean,
        fullName = "runTestsSerial",
        shortName = "t",
        description = "テストを並列実行しないようにする。"
    ).default(false)
    val manualJudge by parser.option(
        ArgType.Boolean,
        fullName = "manualJudge",
        shortName = "m",
        description = "手動で判定する。"
    ).default(false)
    val outputToCsv by parser.option(
        ArgType.Boolean,
        fullName = "outputToCsv",
        shortName = "c",
        description = "結果ファイルをcsvに出力する。"
    ).default(false)
    val outputWithSJIS by parser.option(
        ArgType.Boolean,
        fullName = "outputWithSJIS",
        shortName = "s",
        description = "csvの結果ファイルをShift_JISで出力する"
    ).default(false)

    parser.parse(args)

    if(manualJudge && !runTestsSerial) {
        println("手動判定するには-tオプションも指定してください")
        return
    }

    println("rootPath: $rootPath")
    if(Files.notExists(Path(rootPath))) {
        println("指定されたrootPathは存在しないようです")
        return
    }

    val stepLevel = stepLevelArg ?: 2
    val keepOriginalDirectory = nonKeepOriginalDirectory != true
    val config = parseConfigFile(configPath)
    val outputCharset = if(outputWithSJIS) Charset.forName("Shift_JIS") else Charset.defaultCharset()
    val submissionDirectories = getAllTargetDirectories(rootPath)
    val workspacePath = Path(rootPath, "workspace")
    if(Files.notExists(workspacePath)) {
        runCatching {
            Files.createDirectory(workspacePath)
        }.onFailure {
            println("ワークスペースディレクトリの作成に失敗しました")
            return
        }
    }

    println("unzipping...")
    val directoryNameRegex = Regex("""^.{8} .*_assignsubmission_file_""")
    val studentNameTable = submissionDirectories.filter {
        directoryNameRegex.matches(it.name)
    }.associate {
        unzipSubmissionZipToWorkspace(it, workspacePath)
    }
    println("unzip done!")
    println()
    if(stepLevel <= 0)
        return

    println("compiling...")
    val compileResults = compile(workspacePath, config, keepOriginalDirectory)
    println("compile done!")
    println()
    if(stepLevel == 1)
        return

    println("testing...")
    val testResults = runTests(config, compileResults, runTestsSerial, manualJudge)
    println("test done!")
    println()


    val resultTable = makeResultTable(testResults)
    if(outputToCsv){
        val outFile = Path(outputPath).resolve(config.outputFileName+"-detail.csv").toFile()
        runCatching {
            outFile.bufferedWriter(outputCharset)
        }.getOrElse {
            println("結果詳細ファイルのオープンに失敗しました")
            println("ファイルがロックされている可能性があります")
            return
        }.use {
            writeDetail(it, testResults, workspacePath, resultTable, studentNameTable)
        }
        val summaryFile = Path(outputPath).resolve(config.outputFileName+"-summary.csv").toFile()
        runCatching {
            summaryFile.bufferedWriter(outputCharset)
        }.getOrElse {
            println("結果概要ファイルのオープンに失敗しました")
            println("ファイルがロックされている可能性があります")
            return
        }.use {
            writeSummary(config.tasks, it, resultTable, studentNameTable)
        }
        println("result file wrote to  ${outFile.absolutePath}")
        println("summary file wrote to ${summaryFile.absolutePath}")
    } else {
        val outFile = Path(outputPath).resolve(config.outputFileName.plus(".xlsx")).toFile()
        runCatching {
            outFile.outputStream()
        }.getOrElse {
            println("結果詳細ファイルのオープンに失敗しました")
            println("ファイルがロックされている可能性があります")
            return
        }.use {
            writeDetailAndSummaryExcel(it, testResults, workspacePath, resultTable, studentNameTable, config.tasks)
        }
        println("result file wrote to ${outFile.absolutePath}")
    }

}

fun printProgress(printProgress: AtomicInteger, n: Int, totalSize: Int) {
    printProgress.getAndUpdate {
        val p = (n.toFloat()/totalSize.toFloat()*100f).toInt()
        if(it <= p) {
            print("$it% ")
            it + 10
        } else {
            it
        }
    }
}

suspend fun compile(workspacePath: Path, config: Config, keepOriginalDirectory: Boolean): List<CompileResult> {
    val jobs = mutableListOf<Deferred<CompileResult>>()
    val counter = AtomicInteger(0)
    val printProgress = AtomicInteger(10)
    var totalSize = 1

    workspacePath.toFile().listFiles()?.forEach { studentBase ->
        val base = studentBase.resolve("sources")
        findJavaSourceFiles(base.toPath()).forEach { javaSource ->
            val dstPath = calcDstPath(keepOriginalDirectory, base, studentBase, javaSource)
            jobs.add(CoroutineScope(Dispatchers.Default).async {
                val res = compileJavaSource(javaSource, dstPath, config.compileTimeout)
                printProgress(printProgress, counter.addAndGet(1), totalSize)
                CompileResult(res.first, res.second, javaSource, studentBase, base, dstPath)
            })
        }
    }
    totalSize = jobs.size
    return jobs.awaitAll().also { println() }
}

fun getJudgeFromInput(): Judge {
    while(true) {
        print("judge?: ")
        val str = readLine()
        if(str == null) {
            println("input error")
            continue
        }
        val judge = Judge.fromStringInput(str)

        if(judge == Judge.Unknown) {
            println("invalid input")
            continue
        }
        return judge
    }
}

suspend fun runTests(
    config: Config,
    compileResults: List<CompileResult>,
    runTestsSerial: Boolean,
    manualJudge: Boolean
): List<TestResult> {

    val printProgress = AtomicInteger(10)
    var totalSize = 1

    val counter = AtomicInteger(0)

    fun makeTestJob(info: InfoForJudge): List<Pair<Deferred<TestResult>, Pair<InfoForJudge, TestCase?>>> {
        fun f(judge: Judge): List<Pair<Deferred<TestResult>, Pair<InfoForJudge, TestCase?>>> {
            return listOf(CoroutineScope(Dispatchers.Default).async {
                if(!runTestsSerial)
                    printProgress(printProgress, counter.addAndGet(1), totalSize)
                TestResult(judgeInfo = info, judge = judge)
            } to (info to null))
        }
        // NF
        if(info.taskName == null) {
            return f(Judge.NF)
        }
        val testcases = config.tasks[info.taskName]
        // CE
        if(info.compileResult.isLeft()) {
            return f(Judge.CE)
        }
        // ??
        if(testcases?.testcase == null || info.className == null) {
            return f(Judge.Unknown)
        }
        // run test
        return testcases.testcase.map { testcase ->
            CoroutineScope(Dispatchers.Default).async {
                codeTest(info, testcase, config.runningTimeout).getOrElse {
                    TestResult(
                        errorOutput = "internal error: ${it.stackTraceToString().replace("\r", "").escaped()}",
                        judgeInfo = info,
                        judge = Judge.IE
                    )
                }.also {
                    if(!runTestsSerial)
                        printProgress(printProgress, counter.addAndGet(1), totalSize)
                }
            } to (info to testcase)
        }
    }

    val judgeInfo = compileResults.map {
        val t = determineTaskNumberAndPackageName(config, it.javaSource, it.encoding.charset)
        InfoForJudge(
            studentID = it.studentBase.name,
            taskName = t.first,
            classFileDir = it.dstDirectory,
            className = it.result.orNull(),
            packageName = t.second,
            compileResult = it.result,
            javaSource = it.javaSource
        )
    }


    val testJobs = judgeInfo.flatMap { info -> makeTestJob(info) }
    totalSize = testJobs.size.takeIf { it != 0 } ?: 1
    if(!runTestsSerial)
        return testJobs.map { it.first }.awaitAll().also { println() }

    suspend fun jobTransform(pair: Pair<Deferred<TestResult>, Pair<InfoForJudge, TestCase?>>): TestResult {
        val job = pair.first
        val info = pair.second.first
        val testcase = pair.second.second
        if(testcase != null && info.className != null)
            print("running: ${info.studentID}:${info.className}  \t${info.taskName}:${testcase.name}\t-> ")
        if(!manualJudge || (testcase == null || info.className == null)) {
            return job.await().also { r ->
                if(testcase != null && info.className != null)
                    println("${r.judge}")
            }
        }

        println()
        val result = job.await()
        testcase.expect.joinToString("\n").takeIf { s -> s.isNotBlank() }?.let { s ->
            println("expected:")
            println(s)
        }
        result.errorOutput.takeIf { s -> s.isNotBlank() }?.let { s ->
            println("stderr:")
            println(s)
        }
        result.output.takeIf { s -> s.isNotBlank() }?.let { s ->
            println("stdout:")
            println(s.trimEnd { t -> t == '\n' || t == '\n' || t == ' ' || t == '\t' })
        }

        val judge = getJudgeFromInput()
        println()
        return result.copy(judge = judge)
    }

    return testJobs.map {
        jobTransform(it)
    }
}

fun makeResultTable(testResults: List<TestResult>): Map<String, Map<String, Judge>> {
    val resultTable = mutableMapOf<String, MutableMap<String, Judge>>()
    for(result in testResults) {

        val studentId = result.judgeInfo.studentID
        val taskName = result.judgeInfo.taskName
        val testCaseName = result.testCase.name
        val stat = result.judge

        resultTable.computeIfAbsent(studentId) {
            mutableMapOf()
        }["$taskName:$testCaseName"] = stat
    }
    return resultTable
}

val detailHeader = listOf(
    "studentID",
    "studentName",
    "sourcePath",
    "taskName",
    "testCase",
    "arg",
    "input",
    "stat",
    "time(ms)",
    "expect",
    "stdout",
    "stderr",
    "compileError",
    "package",
    "class",
    "runCommand"
)

fun writeDetailAndSummaryExcel(
    outputFileStream: FileOutputStream,
    testResults: List<TestResult>,
    workspacePath: Path,
    resultTable: Map<String, Map<String, Judge>>,
    studentNameTable: Map<String, String>,
    tasks: Map<String, Task>
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

        testResults.forEachIndexed { index, result ->
            val studentId = result.judgeInfo.studentID
            val studentName = studentNameTable[result.judgeInfo.studentID] ?: ""
            val sourcePath =
                result.judgeInfo.javaSource.absolutePath.removeRange(0..workspacePath.absolutePathString().length)
            val packageName = result.judgeInfo.packageName ?: ""
            val className = result.judgeInfo.className ?: ""
            val taskName = result.judgeInfo.taskName ?: ""
            val compileError = result.judgeInfo.compileResult.fold({ it }, { "" }).trimEnd().replace(',', ' ').escaped()
            val command = result.command
            val arg = result.testCase.arg
            val input = result.testCase.input.joinToString("\n").escaped()
            val expect = result.testCase.expect.joinToString("\n").escaped()
            val output = result.output.trimEnd().escaped()
            val errorOutput = result.errorOutput.trimEnd().escaped()
            val testCaseName = result.testCase.name
            val time = result.runTimeNano?.let { it/1000000f }
            val stat =
                if(result.judgeInfo.taskName == null) Judge.NF.toString() else resultTable[studentId]?.get("$taskName:$testCaseName")
                    ?.toString() ?: "__"
            sheet.createRow(index + 1).let { row ->
                row.createCell(0, CellType.STRING).setCellValue(studentId)
                row.createCell(1, CellType.STRING).setCellValue(studentName)
                row.createCell(2, CellType.STRING).setCellValue(sourcePath)
                row.createCell(3, CellType.STRING).setCellValue(taskName)
                row.createCell(4, CellType.STRING).setCellValue(testCaseName)
                row.createCell(5, CellType.STRING).setCellValue(arg)
                row.createCell(6, CellType.STRING).setCellValue(input)
                row.createCell(7, CellType.STRING).setCellValue(stat)
                if(time == null) {
                    row.createCell(8, CellType.BLANK).setBlank()
                } else {
                    row.createCell(8, CellType.NUMERIC).apply {
                        setCellValue(time.toDouble())
                        this.cellStyle = timeStyle
                    }
                }
                row.createCell(9, CellType.STRING).setCellValue(expect)
                row.createCell(10, CellType.STRING).setCellValue(output)
                row.createCell(11, CellType.STRING).setCellValue(errorOutput)
                row.createCell(12, CellType.STRING).setCellValue(compileError)
                row.createCell(13, CellType.STRING).setCellValue(packageName)
                row.createCell(14, CellType.STRING).setCellValue(className)
                row.createCell(15, CellType.STRING).setCellValue(command)
            }
        }

        val summarySheet = workBook.createSheet("Summary")
        val taskNames = tasks.mapValues {
            it.value.testcase.size
        }
        val taskNameList = tasks.flatMap {
            (it.value.testcase).map { testCase ->
                "${it.key}:${testCase.name}"
            }
        }
        val nl = taskNames.map { "${it.key}%" }
        summarySheet.createRow(0).let { row ->
            detailHeader.forEachIndexed { index, s ->
                row.createCell(index, CellType.STRING).apply {
                    setCellValue(s)
                }
            }
            row.createCell(0, CellType.STRING).setCellValue("ID")
            row.createCell(1, CellType.STRING).setCellValue("Name")
            nl.forEachIndexed { index, s ->
                row.createCell(index + 2, CellType.STRING).setCellValue(s)
            }
            taskNameList.forEachIndexed { index, s ->
                row.createCell(index + 2 + nl.size, CellType.STRING).setCellValue(s)
            }
        }
        var count = 1
        for(row in resultTable) {
            val studentID = row.key
            val studentName = studentNameTable[studentID] ?: ""
            val data = row.value
            val stats = taskNameList.map {
                data[it] ?: data[it.takeWhile { c -> c != ':' }.plus(':')] ?: data["unknown:"] ?: Judge.NF
            }

            val ll = taskNames.mapValues { n ->
                taskNameList.filter { it.startsWith(n.key) }.count { data[it] == Judge.AC } to n.value
            }.map { (it.value.first.toFloat()/it.value.second.toFloat()*100f).toInt() }
            summarySheet.createRow(count).apply {
                createCell(0, CellType.STRING).setCellValue(studentID)
                createCell(1, CellType.STRING).setCellValue(studentName)
                ll.forEachIndexed { index, i ->
                    createCell(index + 2, CellType.NUMERIC).setCellValue(i.toDouble())
                }
                stats.forEachIndexed { index, judge ->
                    createCell(index + 2 + ll.size, CellType.STRING).setCellValue(judge.toString())
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

        sheet.setStatCond(CellRangeAddress(1, testResults.size, 7, 7))
        sheet.setAutoFilter(CellRangeAddress(0, 0, 0, detailHeader.size))
        detailHeader.indices.forEach { sheet.autoSizeColumn(it) }

        val summaryLastCol = taskNames.size + 1 + taskNameList.size
        summarySheet.setStatCond(
            CellRangeAddress(1, resultTable.size, taskNames.size + 2, summaryLastCol)
        )
        summarySheet.setAutoFilter(CellRangeAddress(0, 0, 0, summaryLastCol))
        val color = workBook.creationHelper.createExtendedColor().apply {
            this.rgb = byteArrayOf(99,142.toByte(),198.toByte())
        }
        applyDataBars(summarySheet.sheetConditionalFormatting,CellRangeAddress(1,resultTable.size,2,taskNames.size+1),color)
        workBook.write(outputFileStream)
    }
}

@Throws(Exception::class)
fun applyDataBars(sheetCF: SheetConditionalFormatting, region: CellRangeAddress, color: ExtendedColor?) {
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

fun writeDetail(
    outputStream: BufferedWriter,
    testResults: List<TestResult>,
    workspacePath: Path,
    resultTable: Map<String, Map<String, Judge>>,
    studentNameTable: Map<String, String>
) {
    outputStream.appendLine(detailHeader.joinToString(","))
    for(result in testResults) {

        val studentId = result.judgeInfo.studentID
        val studentName = studentNameTable[result.judgeInfo.studentID] ?: ""
        val sourcePath =
            result.judgeInfo.javaSource.absolutePath.removeRange(0..workspacePath.absolutePathString().length)
        val packageName = result.judgeInfo.packageName ?: ""
        val className = result.judgeInfo.className ?: ""
        val taskName = result.judgeInfo.taskName ?: ""
        val compileError = result.judgeInfo.compileResult.fold({ it }, { "" }).trimEnd().replace(',', ' ').escaped()
        val command = result.command
        val arg = result.testCase.arg
        val input = result.testCase.input.joinToString("\n").escaped()
        val expect = result.testCase.expect.joinToString("\n").escaped()
        val output = result.output.trimEnd().escaped()
        val errorOutput = result.errorOutput.trimEnd().escaped()
        val testCaseName = result.testCase.name
        val time = result.runTimeNano?.let { String.format("%.3f", it/1000000f) } ?: ""

        val stat =
            if(result.judgeInfo.taskName == null) Judge.NF.toString() else resultTable[studentId]?.get("$taskName:$testCaseName")
                ?.toString() ?: "__"

        outputStream.appendLine(
            """$studentId,$studentName,$sourcePath,$taskName,$testCaseName,$arg,$input,$stat,$time,$expect,$output,$errorOutput,$compileError,$packageName,$className,$command"""
        )
    }
}

fun writeSummary(
    tasks: Map<String, Task>,
    outputStream: BufferedWriter,
    resultTable: Map<String, Map<String, Judge>>,
    studentNameTable: Map<String, String>
) {
    val taskNames = tasks.mapValues {
        it.value.testcase.size
    }
    val taskNameList = tasks.flatMap {
        (it.value.testcase).map { testCase ->
            "${it.key}:${testCase.name}"
        }
    }
    val nl = taskNames.map { "${it.key}%" }
    outputStream.appendLine("""ID,Name,${nl.joinToString()},${taskNameList.joinToString()}""")
    for(row in resultTable) {
        val studentID = row.key
        val studentName = studentNameTable[studentID] ?: ""
        val data = row.value
        val stats = taskNameList.map {
            data[it] ?: data[it.takeWhile { c -> c != ':' }.plus(':')] ?: data["unknown:"] ?: Judge.NF
        }

        val ll = taskNames.mapValues { n ->
            taskNameList.filter { it.startsWith(n.key) }.count { data[it] == Judge.AC } to n.value
        }.map { (it.value.first.toFloat()/it.value.second.toFloat()*100f).toInt().toString() }
        outputStream.appendLine("""$studentID,$studentName,${ll.joinToString()},${stats.joinToString()}""")
    }
}

fun parseConfigFile(filepath: String): Config {
    return Yaml.default.decodeFromStream(Config.serializer(), Path(filepath).toFile().inputStream())
}

fun codeTest(infoForJudge: InfoForJudge, testCase: TestCase, runningTimeOutMilli: Long) = runCatching {
    val runtime = Runtime.getRuntime()
    val classDir = infoForJudge.classFileDir
//    val n = infoForJudge.packageName?.count { it == '.' }?:0
//    repeat(n) {
//        classDir = classDir.parent
//    }
    val command =
        """java -Duser.language=en -classpath "$classDir" ${infoForJudge.packageName?.plus(".") ?: ""}${infoForJudge.className} ${testCase.arg}"""
    //println(command)
    val process = runtime.exec(command)
    val startTime = System.nanoTime()
    val outputStream = process.outputStream.buffered().writer()
    if(testCase.input.isNotEmpty()) {
        testCase.input.forEach { inputString ->
            outputStream.write(inputString)
        }
    }
    outputStream.flush()
    val isTle = !process.waitFor(runningTimeOutMilli, TimeUnit.MILLISECONDS)
    val runTime = System.nanoTime() - startTime
    process.destroyForcibly()
    val errorOutput = String(process.errorStream.readBytes(), Charset.forName("Shift_JIS"))
    val output = String(process.inputStream.readBytes(), Charset.forName("Shift_JIS"))


    val out = output.replace('\r', ' ').replace('\n', ' ')
    val pass = testCase.expectRegex.firstOrNull()?.matches(out) ?: false

    val runningError = errorOutput.isNotBlank()
    val isCompileError = infoForJudge.compileResult.isLeft()

    val stat = when {
        isTle -> Judge.TLE
        isCompileError -> Judge.CE
        runningError -> Judge.RE
        !pass -> Judge.WA
        pass -> Judge.AC
        else -> Judge.Unknown
    }

    TestResult(
        output,
        errorOutput,
        process.exitValue(),
        stat,
        infoForJudge,
        testCase,
        command,
        runTime
    )
}


val packageRegex = Regex("""^package\s+(.*)\s*;\s*$""")
fun determineTaskNumberAndPackageName(
    config: Config,
    file: File,
    charset: Charset
): Pair<String?, String?> {
    var taskName: String? = null
    var packageName: String? = null
    for(line in file.readLines(charset)) {
        for(info in config.tasks) {
            for(word in info.value.word) {
                if(line.contains(word)) {
                    taskName = info.key
                    if(packageName != null)
                        return taskName to packageName
                }
            }
        }
        val match = packageRegex.find(line) ?: continue
        packageName = match.groups[1]?.value
        if(packageName != null && taskName != null)
            return taskName to packageName
    }
    return taskName to (packageName)
}

fun getAllTargetDirectories(path: String): List<File> {
    return File(path).listFiles()?.filter { it.isDirectory && it.name.endsWith("assignsubmission_file_") } ?: listOf()
}

fun unzipSubmissionZipToWorkspace(submissionDirectory: File, workspacePath: Path): Pair<String, String> {
    val studentID = submissionDirectory.name.slice(0..7)
    val studentName = submissionDirectory.name.drop(9).takeWhile { it != '_' }
    val dstPath = workspacePath.resolve(studentID).resolve("sources")
    if(Files.notExists(dstPath))
        Files.createDirectories(dstPath)
    submissionDirectory.listFiles()?.filter { it.name.lowercase().endsWith("zip") }?.forEach { zipFile ->
        unzipFile(zipFile.toPath(), dstPath)
    }
    return studentID to studentName
}

fun findJavaSourceFiles(targetPath: Path): Sequence<File> {
    return targetPath.toFile().walk().filter { it.name.endsWith(".java") }
}

fun calcDstPath(keepOriginalDirectory: Boolean?, base: File, studentBase: File, javaSource: File): Path {
    fun calcRelativePath(base: Path, target: Path): String {
        return target.absolutePathString().drop(base.absolutePathString().length).let {
            if(it.firstOrNull() == '/' || it.firstOrNull() == '\\')
                it.drop(1)
            else
                it
        }
    }
    return if(keepOriginalDirectory == true) {
        val tmp = calcRelativePath(base.toPath(), javaSource.toPath().parent)
        studentBase.toPath().resolve("compile").resolve(tmp)
    } else {
        studentBase.toPath().resolve("compile")
    }
}


fun compileJavaSource(
    sourceFile: File,
    dstDirectory: Path,
    compileTimeOutMilli: Long
): Pair<Either<String, String>, Encoding> {

    data class InternalCompileResult(
        val exitCode: Int,
        val compiledFilePath: Path?,
        val compiledName: String?,
        val errorMessage: String = ""
    )

    fun compileWithEncoding(encoding: String): Result<InternalCompileResult> = runCatching {
        val runtime = Runtime.getRuntime()
        val command =
            """javac -J-Duser.language=en -d "${dstDirectory.absolutePathString()}" -encoding $encoding "${sourceFile.absolutePath}""""
        val process = runtime.exec(command)
        process.waitFor(compileTimeOutMilli, TimeUnit.MILLISECONDS)

        val errorOutput = String(process.errorStream.readBytes())
        process.destroy()
        if(errorOutput.isEmpty()) {
            InternalCompileResult(
                process.exitValue(),
                dstDirectory,
                sourceFile.name.dropLast(5)
            )
        } else {
            InternalCompileResult(
                process.exitValue(),
                null,
                null,
                errorOutput
            )
        }
    }

    if(Files.notExists(dstDirectory))
        Files.createDirectories(dstDirectory)

    val utf8Result = compileWithEncoding("UTF-8")
    utf8Result.onSuccess {
        if(it.exitCode == 0 && it.compiledName != null)
            return it.compiledName.right() to Encoding.UTF8
    }
    val sJisResult = compileWithEncoding("Shift_JIS")
    sJisResult.onSuccess {
        if(it.exitCode == 0 && it.compiledName != null)
            return it.compiledName.right() to Encoding.ShiftJIS
    }

    val isNotUtf8 = utf8Result.map { it.errorMessage.contains("error: unmappable character") }.getOrDefault(false)
    val isNotSJis = sJisResult.map { it.errorMessage.contains("error: unmappable character") }.getOrDefault(false)
    if(isNotUtf8 && isNotSJis)
        return "Unknown encoding".left() to Encoding.Unknown
    if(isNotUtf8)
        return sJisResult.fold({ it.errorMessage }, { it.message ?: "exception occurred" }).left() to Encoding.ShiftJIS
    if(isNotSJis)
        return utf8Result.fold({ it.errorMessage }, { it.message ?: "exception occurred" }).left() to Encoding.UTF8
    println("""ERROR: SJis:${sJisResult.fold({ it }, { it })}   utf8:${utf8Result.fold({ it }, { it })}""")
    return "unreachable condition".left() to Encoding.Unknown
}