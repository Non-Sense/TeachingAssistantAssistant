import arrow.core.escaped
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
    CF("CF"),
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
    val result: CompileResultDetail,
    val javaSource: File,
    val studentBase: File,
    val base: File,
    val dstDirectory: Path
)

data class InfoForJudge(
    val studentID: StudentID,
    val taskName: TaskName?,
    val classFileDir: Path,
    val className: String?,
    val packageName: PackageName?,
    val compileResult: CompileResultDetail,
    val javaSource: File
)

data class TestResult(
    val output: String = "",
    val errorOutput: String = "",
    val exitCode: Int = -1,
    val judge: Judge = Judge.Unknown,
    val judgeInfo: InfoForJudge,
    val testCase: TestCase? = null,
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
    val tasks: Map<TaskName, Task>
)

@Serializable
@JvmInline
value class TaskName(
    val name: String
) {
    override fun toString(): String {
        return name
    }
}

@JvmInline
value class PackageName(
    val name: String
)

@JvmInline
value class StudentID(
    val id: String
) {
    override fun toString(): String {
        return id
    }
}


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
    if(outputToCsv) {
        val outFile = Path(outputPath).resolve(config.outputFileName + "-detail.csv").toFile()
        runCatching {
            outFile.bufferedWriter(outputCharset)
        }.getOrElse {
            println("結果詳細ファイルのオープンに失敗しました")
            println("ファイルがロックされている可能性があります")
            return
        }.use {
            writeDetail(it, testResults, workspacePath, resultTable, studentNameTable)
        }
        val summaryFile = Path(outputPath).resolve(config.outputFileName + "-summary.csv").toFile()
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
                val res = compileJavaSource(javaSource, dstPath, config.compileTimeout, base.toPath())
                printProgress(printProgress, counter.addAndGet(1), totalSize)
                CompileResult(res, javaSource, studentBase, base, dstPath)
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
        if(info.compileResult !is CompileResultDetail.Success) {
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

    val judgeInfo = compileResults.flatMap {
        val t = determineTaskNumberAndPackageName(config, it.javaSource, it.result)
        t.first.map { task ->
            InfoForJudge(
                studentID = StudentID(it.studentBase.name),
                taskName = task,
                classFileDir = it.dstDirectory,
                className = (it.result as? CompileResultDetail.Success)?.className,
                packageName = t.second,
                compileResult = it.result,
                javaSource = it.javaSource
            )
        }
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
            print("running: ${info.studentID.id}:${info.className}  \t${info.taskName}:${testcase.name}\t-> ")
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

data class TestResultTableKey(
    val taskName: TaskName?,
    val testCase: TestCase?
)

class TestResultTable {
    data class Element(
        val studentID: StudentID,
        val value: Map<TestResultTableKey, Map<Path, Judge>>
    )

    private val table = mutableMapOf<StudentID, MutableMap<TestResultTableKey, MutableMap<Path, Judge>>>()
    fun size() = table.size
    fun add(studentID: StudentID, taskName: TaskName?, testCase: TestCase?, stat: Judge, javaFilePath: Path) {
        val k = TestResultTableKey(
            taskName,
            testCase
        )

        table.computeIfAbsent(studentID) {
            mutableMapOf()
        }.computeIfAbsent(k) {
            mutableMapOf()
        }[javaFilePath] = stat
    }

    fun get(studentID: StudentID, taskName: TaskName?, testCase: TestCase?, javaFilePath: Path): Judge? {
        val k = TestResultTableKey(
            taskName,
            testCase
        )
        return table[studentID]?.get(k)?.get(javaFilePath)
    }

    operator fun iterator() = table.iterator()

    fun forEach(action: (Element) -> Unit) {
        table.forEach { (t, u) ->
            action(Element(t, u))
        }
    }
}

fun makeResultTable(testResults: List<TestResult>): TestResultTable {
    val resultTable = TestResultTable()
    for(result in testResults) {

        val studentId = result.judgeInfo.studentID
        val taskName = result.judgeInfo.taskName
        val testCase = result.testCase
        val stat = result.judge

        resultTable.add(studentId, taskName, testCase, stat, result.judgeInfo.javaSource.toPath())
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
    "runCommand",
    "compileCommand"
)

fun String.removeCarriageReturn(): String {
    return this.replace("\r", "")
}

fun String.csvFormat(): String {
    return this.replace(',', ' ').escaped()
}

class DetailRow(
    result: TestResult,
    studentNameTable: Map<StudentID, String>,
    workspacePath: Path,
    resultTable: TestResultTable
) {
    val studentId = result.judgeInfo.studentID.id
    val studentName = studentNameTable[result.judgeInfo.studentID] ?: ""
    val sourcePath = result.judgeInfo.javaSource.absolutePath.removeRange(0..workspacePath.absolutePathString().length)
    val packageName = result.judgeInfo.packageName?.name ?: ""
    val className = result.judgeInfo.className ?: ""
    val taskName = result.judgeInfo.taskName ?: TaskName("")
    val compileError = when(val it = result.judgeInfo.compileResult) {
        is CompileResultDetail.Error -> "InternalError:\nUTF8:\n${it.utf8Error.error.stackTraceToString()}\nShiftJIS:\n${it.sJisError.error.stackTraceToString()}".removeCarriageReturn()
        is CompileResultDetail.Failure -> it.errorMessage.removeCarriageReturn()
        is CompileResultDetail.Success -> ""
    }
    val compileCommand = when(val it = result.judgeInfo.compileResult) {
        is CompileResultDetail.Error -> "UTF8:${it.utf8Error.compileCommand}\tShiftJIS:${it.sJisError.compileCommand}"
        is CompileResultDetail.Failure -> it.compileCommand
        is CompileResultDetail.Success -> it.compileCommand
    }
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

    fun getCsvString(): String {
        return """$studentId,$studentName,$sourcePath,$taskName,$testCaseName,$arg,${input.csvFormat()},$stat,$time,${expect.csvFormat()},${output.csvFormat()},${errorOutput.csvFormat()},${compileError.csvFormat()},$packageName,$className,$command,$compileCommand"""
    }
}

class SummaryHeader(
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

class SummaryRow(
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
        summaryHeader.testCaseList.filter { it.first == n.key }.count { data[TestResultTableKey(it.first, it.second)]?.count { (_,k) -> k==Judge.AC } == 1 } to n.value
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
    tasks: Map<TaskName, Task>
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

        sheet.setStatCond(CellRangeAddress(1, testResults.size, 7, 7))
        sheet.setAutoFilter(CellRangeAddress(0, 0, 0, detailHeader.size))
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
    resultTable: TestResultTable,
    studentNameTable: Map<StudentID, String>
) {
    outputStream.appendLine(detailHeader.joinToString(","))
    for(result in testResults) {
        val detailRow = DetailRow(result, studentNameTable, workspacePath, resultTable)
        outputStream.appendLine(detailRow.getCsvString())
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
        """java -D"user.language=en" -classpath "$classDir" ${infoForJudge.packageName?.name?.plus(".") ?: ""}${infoForJudge.className} ${testCase.arg}"""
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

    val runningError =
        errorOutput.contains("Exception in thread") || errorOutput.contains("Error: Main method not found in class")
    val isCompileError = infoForJudge.compileResult !is CompileResultDetail.Success

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
    compileResult: CompileResultDetail
): Pair<List<TaskName?>, PackageName?> {
    val charset = when(compileResult) {
        is CompileResultDetail.Error -> return listOf(null) to null
        is CompileResultDetail.Failure -> compileResult.encoding.charset
        is CompileResultDetail.Success -> compileResult.encoding.charset
    }

    val taskNames = mutableListOf<TaskName?>()
    var packageName: PackageName? = null
    for(line in file.readLines(charset)) {
        for((taskName, task) in config.tasks) {
            if(line.contains(task.word)) {
                taskNames.add(taskName)
            }
        }
        val match = packageRegex.find(line) ?: continue
        match.groups[1]?.value?.let {
            packageName = PackageName(it)
        }
    }
    if(taskNames.isEmpty())
        taskNames += null
    return taskNames.distinct() to packageName
}

fun String.contains(strings: Collection<String>): Boolean {
    return strings.firstOrNull { this.contains(it) } != null
}

fun getAllTargetDirectories(path: String): List<File> {
    return File(path).listFiles()?.filter { it.isDirectory && it.name.endsWith("assignsubmission_file_") } ?: listOf()
}

fun unzipSubmissionZipToWorkspace(submissionDirectory: File, workspacePath: Path): Pair<StudentID, String> {
    val studentID = submissionDirectory.name.slice(0..7)
    val studentName = submissionDirectory.name.drop(9).takeWhile { it != '_' }
    val dstPath = workspacePath.resolve(studentID).resolve("sources")
    if(Files.notExists(dstPath))
        Files.createDirectories(dstPath)
    submissionDirectory.listFiles()?.filter { it.name.lowercase().endsWith("zip") }?.forEach { zipFile ->
        unzipFile(zipFile.toPath(), dstPath)
    }
    return StudentID(studentID) to studentName
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

sealed class CompileResultDetail {
    data class Success(
        val compileCommand: String,
        val encoding: Encoding,
        val className: String
    ): CompileResultDetail()

    data class Failure(
        val compileCommand: String,
        val encoding: Encoding,
        val errorMessage: String
    ): CompileResultDetail()

    data class Error(
        val utf8Error: CompileErrorDetail,
        val sJisError: CompileErrorDetail
    ): CompileResultDetail()
}

data class CompileErrorDetail(
    val compileCommand: String,
    val error: Throwable
)

fun compileJavaSource(
    sourceFile: File,
    dstDirectory: Path,
    compileTimeOutMilli: Long,
    classPath: Path
): CompileResultDetail {
    data class Detail(
        val exitCode: Int,
        val compiledFilePath: Path?,
        val compiledName: String?,
        val encoding: Encoding,
        val encodingError: Boolean = false,
        val errorMessage: String = ""
    )

    data class InternalCompileResult(
        val command: String,
        val detail: Result<Detail>
    )

    fun InternalCompileResult.toDetail(): CompileResultDetail? {
        val detail = detail.getOrNull() ?: return null
        if(detail.exitCode == 0 && detail.compiledName != null)
            return CompileResultDetail.Success(command, detail.encoding, detail.compiledName)
        if(!detail.encodingError)
            return CompileResultDetail.Failure(command, detail.encoding, detail.errorMessage)
        return null
    }

    fun compileWithEncoding(encoding: Encoding): InternalCompileResult {
        val command = runCatching {
            """javac -J-Duser.language=en -d "${dstDirectory.absolutePathString()}" -encoding ${encoding.charset.name()} -cp "${classPath.absolutePathString()}" "${sourceFile.absolutePath}""""
        }.fold({ it }, { return InternalCompileResult("", Result.failure(it)) })
        val detail = runCatching {
            val runtime = Runtime.getRuntime()

            val process = runtime.exec(command)
            process.waitFor(compileTimeOutMilli, TimeUnit.MILLISECONDS)

            val errorOutput = String(process.errorStream.readBytes())
            process.destroy()
            if(errorOutput.isEmpty()) {
                Detail(
                    process.exitValue(),
                    dstDirectory,
                    sourceFile.name.dropLast(5),
                    encoding
                )
            } else {
                Detail(
                    process.exitValue(),
                    null,
                    null,
                    encoding,
                    errorOutput.contains("error: unmappable character"),
                    errorOutput
                )
            }
        }
        return InternalCompileResult(command, detail)
    }

    if(Files.notExists(dstDirectory))
        Files.createDirectories(dstDirectory)

    val utf8Result = compileWithEncoding(Encoding.UTF8)
    utf8Result.toDetail()?.let {
        return it
    }

    val sJisResult = compileWithEncoding(Encoding.ShiftJIS)
    sJisResult.toDetail()?.let {
        return it
    }

    return CompileResultDetail.Error(
        utf8Error = CompileErrorDetail(
            utf8Result.command,
            utf8Result.detail.exceptionOrNull() ?: Throwable("unreachable")
        ),
        sJisError = CompileErrorDetail(
            sJisResult.command,
            sJisResult.detail.exceptionOrNull() ?: Throwable("unreachable")
        ),
    ).also {
        System.err.println("InternalCompileError: ")
        it.utf8Error.error.printStackTrace()
        it.sJisError.error.printStackTrace()
    }
}