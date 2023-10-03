import arrow.core.escaped
import com.charleskorn.kaml.Yaml
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.coroutines.*
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

data class CompileResult(
    val result: CompileResultDetail,
    val studentID: StudentID,
    val javaSource: File,
    val studentBase: Path,
    val sourceBase: Path,
    val compileBase: Path,
    val id: Int = 0
)

sealed class CompileResultDetail {
    data class Success(
        val compileCommand: String,
        val encoding: Encoding,
        val className: String,
        val packageName: PackageName?,
        val classPath: Path
    ): CompileResultDetail()

    data class Failure(
        val compileCommand: String,
        val encoding: Encoding,
        val errorMessage: String,
        val packageName: PackageName?
    ): CompileResultDetail()

    data class Error(
        val utf8Error: CompileErrorDetail,
        val sJisError: CompileErrorDetail
    ): CompileResultDetail()

    fun encoding(): Encoding? {
        return when(this) {
            is Error -> null
            is Failure -> encoding
            is Success -> encoding
        }
    }

    fun packageName(): PackageName? {
        return when(this) {
            is Error -> null
            is Failure -> packageName
            is Success -> packageName
        }
    }

    fun classPath(): Path? {
        return when(this) {
            is Error -> null
            is Failure -> null
            is Success -> classPath
        }
    }
}

data class CompileErrorDetail(
    val compileCommand: String,
    val error: String
)

data class InfoForJudge(
    val studentID: StudentID,
    val taskName: TaskName?,
    val classPath: Path?,
    val className: String?,
    val compileResult: CompileResultDetail,
    val javaSource: File
)

data class TestInfo(
    val compileResultId: Int,
    val taskName: TaskName?
)

data class TestInfoJoined(
    val taskName: TaskName?,
    val packageName: PackageName?,
    val compileResult: CompileResult,
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
    val config = parseConfigFile(configPath)
    val outputCharset = if(outputWithSJIS) Charset.forName("Shift_JIS") else Charset.defaultCharset()
    val submissionDirectories = getAllTargetDirectories(rootPath)
    val workspacePath = Path(rootPath, "workspace")
    if(config.disablePackage && Files.exists(workspacePath)) {
        runCatching {
            workspacePath.toFile().walk().forEach {
                if(it.isFile)
                    it.delete()
            }
        }.onFailure {
            println("ワークスペースディレクトリの削除に失敗しました")
            return
        }
    }

    if(Files.notExists(workspacePath)) {
        runCatching {
            Files.createDirectory(workspacePath)
        }.onFailure {
            println("ワークスペースディレクトリの作成に失敗しました")
            return
        }
    }
    TADatabase.connect(workspacePath.resolve("database.db").absolutePathString())
    TADatabase.init()

    if(stepLevel >= 2 && !checkOutputFile(outputToCsv, outputPath, config, outputCharset))
        return

    println("unzipping...")
    val directoryNameRegex = Regex("""^.{8} .*_assignsubmission_file_""")
    val studentNameTable = submissionDirectories.filter {
        directoryNameRegex.matches(it.name)
    }.associate {
        unzipSubmissionZipToWorkspace(it, workspacePath)
    }
    TADatabase.addAllStudent(studentNameTable)
    println("unzip done!")
    println()
    if(stepLevel <= 0)
        return

    println("compiling...")
    compile(workspacePath, config)?.let {
        TADatabase.addAllCompileResult(it)
    }
    println("compile done!")
    println()
    if(stepLevel == 1)
        return

    makeTestInfo(config)

    println("testing...")
    val testResults = runTests(config, runTestsSerial, manualJudge)
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
            println("結果出力ファイルのオープンに失敗しました")
            println("ファイルがロックされている可能性があります")
            return
        }.use {
            writeDetailAndSummaryExcel(
                it,
                testResults,
                workspacePath,
                resultTable,
                studentNameTable,
                config.tasks
            )
        }
        println("result file wrote to ${outFile.absolutePath}")
    }

}

fun checkOutputFile(outputToCsv: Boolean, outputPath: String, config: Config, outputCharset: Charset): Boolean {
    if(outputToCsv) {
        val outFile = Path(outputPath).resolve(config.outputFileName + "-detail.csv").toFile()
        runCatching {
            outFile.bufferedWriter(outputCharset)
        }.getOrElse {
            println("結果詳細ファイルのオープンに失敗しました")
            println("ファイルがロックされている可能性があります")
            return false
        }.close()
        val summaryFile = Path(outputPath).resolve(config.outputFileName + "-summary.csv").toFile()
        runCatching {
            summaryFile.bufferedWriter(outputCharset)
        }.getOrElse {
            println("結果概要ファイルのオープンに失敗しました")
            println("ファイルがロックされている可能性があります")
            return false
        }.close()
    } else {
        val outFile = Path(outputPath).resolve(config.outputFileName.plus(".xlsx")).toFile()
        runCatching {
            outFile.outputStream()
        }.getOrElse {
            println("結果出力ファイルのオープンに失敗しました")
            println("ファイルがロックされている可能性があります")
            return false
        }.close()
    }
    return true
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

fun makeTestInfo(config: Config): List<TestInfo> {
    return TADatabase.flatMapCompileResult {
        val tasks = determineTaskName(config, it.javaSource, it.result.encoding()?.charset)
        tasks.map { taskName ->
            TestInfo(it.id, taskName)
        }
    }
}

suspend fun runTests(
    config: Config,
    runTestsSerial: Boolean,
    manualJudge: Boolean
): List<TestResult> {
    var printer = ProgressPrinter(1)

    fun makeTestJob(info: InfoForJudge): List<Pair<Deferred<TestResult>, Pair<InfoForJudge, TestCase?>>> {
        fun f(judge: Judge): List<Pair<Deferred<TestResult>, Pair<InfoForJudge, TestCase?>>> {
            return listOf(CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
                if(!runTestsSerial)
                    printer.add()
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
            CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
                codeTest(info, testcase, config.runningTimeout).getOrElse {
                    TestResult(
                        errorOutput = "internal error: ${it.stackTraceToString().replace("\r", "").escaped()}",
                        judgeInfo = info,
                        judge = Judge.IE
                    )
                }.also {
                    if(!runTestsSerial)
                        printer.add()
                }
            } to (info to testcase)
        }
    }

    val judgeInfo: List<InfoForJudge> = run {
        val tmp = mutableListOf<InfoForJudge>()
        TADatabase.forEachCompileResult {
            val tasks = determineTaskName(config, it.javaSource, it.result.encoding()?.charset)
            tmp += tasks.map { taskName ->
                InfoForJudge(
                    studentID = it.studentID,
                    taskName = taskName,
                    classPath = it.result.classPath(),
                    className = (it.result as? CompileResultDetail.Success)?.className,
                    compileResult = it.result,
                    javaSource = it.javaSource
                )
            }
        }
        tmp
    }


    val testJobs = judgeInfo.flatMap { info -> makeTestJob(info) }
    printer = ProgressPrinter(testJobs.size.takeIf { it != 0 } ?: 1)
    if(!runTestsSerial)
        return testJobs.map { it.first }.awaitAll()

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

fun parseConfigFile(filepath: String): Config {
    return Yaml.default.decodeFromStream(Config.serializer(), Path(filepath).toFile().inputStream())
}

suspend fun codeTest(infoForJudge: InfoForJudge, testCase: TestCase, runningTimeOutMilli: Long) = runCatching {
    val runtime = Runtime.getRuntime()
    val classDir = infoForJudge.classPath
//    val n = infoForJudge.packageName?.count { it == '.' }?:0
//    repeat(n) {
//        classDir = classDir.parent
//    }
    val command =
        """java -D"user.language=en" -classpath "$classDir" ${infoForJudge.compileResult.packageName()?.name?.plus(".") ?: ""}${infoForJudge.className} ${testCase.arg}"""
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
    val inputStream = process.inputStream
    var output = ""
    val job = CoroutineScope(Dispatchers.Default).launch {
        while(true) {
            if(inputStream.available() < 0) {
                delay(100)
                continue
            }
            output += String(inputStream.readBytes(), Charset.forName("Shift_JIS"))
            //delay(100)
        }
    }
    val isTle = if(runningTimeOutMilli < 0){
        process.waitFor()
        false
    } else {
        !process.waitFor(runningTimeOutMilli, TimeUnit.MILLISECONDS)
    }
    //val isTle = !process.waitFor(runningTimeOutMilli, TimeUnit.MILLISECONDS)
    val runTime = System.nanoTime() - startTime
    process.destroyForcibly()
    val errorOutput = String(process.errorStream.readBytes(), Charset.forName("Shift_JIS"))
    //val output = String(process.inputStream.readBytes(), Charset.forName("Shift_JIS"))
    job.cancel()
    output += String(inputStream.readBytes(), Charset.forName("Shift_JIS"))


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


private fun getAllTargetDirectories(path: String): List<File> {
    return File(path).listFiles()?.filter { it.isDirectory && it.name.endsWith("assignsubmission_file_") } ?: listOf()
}

private fun unzipSubmissionZipToWorkspace(submissionDirectory: File, workspacePath: Path): Pair<StudentID, String> {
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

