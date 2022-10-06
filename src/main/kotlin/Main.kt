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
import java.io.BufferedWriter
import java.io.File
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
    val detailFileName: String = "detail.csv",
    val summaryFileName: String = "summary.csv",
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
        description = "結果の出力先ディレクトリを指定する(csvが2つ生成される)。"
    )
    val nonKeepOriginalDirectory by parser.option(
        ArgType.Boolean,
        fullName = "nonKeepDirectory",
        shortName = "k",
        description = "コンパイル時に元のディレクトリ構成を維持しないでコンパイルする(packageが使えなくなる)"
    )
    val outputWithSJIS by parser.option(
        ArgType.Boolean,
        fullName = "outputWithSJIS",
        shortName = "s",
        description = "結果ファイルをShift_JISで出力する"
    ).default(false)
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


    val outFile = Path(outputPath).resolve(config.detailFileName).toFile()
    val resultTable = makeResultTable(testResults)
    runCatching {
        outFile.bufferedWriter(outputCharset)
    }.getOrElse {
        println("結果詳細ファイルのオープンに失敗しました")
        println("ファイルがロックされている可能性があります")
        return
    }.use {
        writeDetail(it, testResults, workspacePath, resultTable, studentNameTable)
    }

    val summaryFile = Path(outputPath).resolve(config.summaryFileName).toFile()
    runCatching {
        summaryFile.bufferedWriter(outputCharset)
    }.getOrElse {
        println("結果概要ファイルのオープンに失敗しました")
        println("ファイルがロックされている可能性があります")
        return
    }.use {
        writeSummary(config.tasks, it, resultTable, studentNameTable)
    }

    println("result file wrote to $outputPath")

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

fun writeDetail(
    outputStream: BufferedWriter,
    testResults: List<TestResult>,
    workspacePath: Path,
    resultTable: Map<String, Map<String, Judge>>,
    studentNameTable: Map<String, String>
) {
    outputStream.appendLine("studentID,studentName,sourcePath,taskName,testCase,arg,input,stat,time(ms),expect,output,errorOutput,compileErrorOutput,package,class,runCommand")
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

        val stat = resultTable[studentId]?.get("$taskName:$testCaseName") ?: "__"

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