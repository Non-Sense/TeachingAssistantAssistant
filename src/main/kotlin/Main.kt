import arrow.core.*
import com.charleskorn.kaml.Yaml
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.collections.flatMap
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

enum class Encoding(val charset: Charset) {
    UTF8(Charset.forName("UTF-8")),
    ShiftJIS(Charset.forName("Shift_JIS")),
    Unknown(Charset.defaultCharset())
}

data class CompileResultForAsync(
    val result: Either<String, String>,
    val encoding: Encoding,
    val javaSource: File,
    val studentBase: File,
    val base: File,
    val dstDirectory: Path
)

data class InfoForJudge(
    val studentID: String,
    val taskName: String,
    val classFileDir: Path,
    val className: String?,
    val packageName: String?,
    val compileResult: Either<String, String>,
    val javaSource: File
)

data class TestResult(
    val output: String,
    val errorOutput: String,
    val exitCode: Int,
    val isPassTest: Boolean,
    val judgeInfo: InfoForJudge,
    val testCase: TestCase,
    val command: String
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
    val testcase: List<TestCase>?
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
    )
    parser.parse(args)


    val keepOriginalDirectory = nonKeepOriginalDirectory != true

    println("rootdir: $rootPath")
    val config = parseConfigFile(configPath)

    val outFile = Path(outputPath).resolve(config.detailFileName).toFile()
    val summaryFile = Path(outputPath).resolve(config.summaryFileName).toFile()

    val workspacePath = Path(rootPath, "workspase")
    if(Files.notExists(workspacePath))
        Files.createDirectory(workspacePath)

    val submissionDirectories = getAllTargetDirectories(rootPath)

    submissionDirectories.forEach { file ->
        unzipSubmissionZipToWorkspace(file, workspacePath)
    }

    println("compiling...")

    val jobs = mutableListOf<Deferred<CompileResultForAsync>>()
    workspacePath.toFile().listFiles()?.forEach { studentBase ->
        val base = studentBase.resolve("sources")
        findJavaSourceFiles(base.toPath()).forEach { javaSource ->
            val dstPath = calcDstPath(keepOriginalDirectory, base, studentBase, javaSource)
            jobs.add(CoroutineScope(Dispatchers.Default).async {
                val res = compileJavaSource(javaSource, dstPath, config.compileTimeout)
                CompileResultForAsync(res.first, res.second, javaSource, studentBase, base, dstPath)
            })
        }
    }
    val results = jobs.awaitAll()

    println("compile done!")

    println("testing...")

    val judgeInfo = results.map {
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


    val testJobs = mutableListOf<Deferred<Result<TestResult>>>()
    for(info in judgeInfo) {
        val testcases = config.tasks[info.taskName]
        if(testcases?.testcase == null || info.className == null) {
            testJobs.add(CoroutineScope(Dispatchers.Default).async {
                Result.success(
                    TestResult(
                        output = "",
                        errorOutput = "",
                        exitCode = 0,
                        isPassTest = false,
                        judgeInfo = info,
                        testCase = TestCase(""),
                        ""
                    )
                )
            })
            continue
        }
        for(testcase in testcases.testcase) {
            testJobs.add(CoroutineScope(Dispatchers.Default).async {
                codeTest(info, testcase, config.runningTimeout).recover {
                    TestResult(
                        output = "",
                        errorOutput = "unnormal error ${it.stackTrace}",
                        exitCode = 0,
                        isPassTest = false,
                        judgeInfo = info,
                        testCase = TestCase(""),
                        ""
                    )
                }
            })
        }
    }
    val testResults = testJobs.awaitAll()

    println("test done!")


    val resultTable = mutableMapOf<String, MutableMap<String, String>>()
    val outputStream = BufferedWriter(
        FileWriter(
            outFile,
            if(outputWithSJIS == true) Charset.forName("Shift_JIS") else Charset.defaultCharset(),
            false
        )
    )
    outputStream.appendLine("studentID,studentName,sourcePath,taskName,testCase,compileErrorOutput,arg,input,stat,expect,output,errorOutput,package,class,runCommand")
    for(res in testResults) {
        val r = res.getOrNull()
        if(r == null) {
            println("unknown error")
            continue
        }

        val studentId = r.judgeInfo.studentID
        val studentName = studentNameTable[r.judgeInfo.studentID] ?: ""
        val sourcePath = r.judgeInfo.javaSource.absolutePath.removeRange(0..workspacePath.absolutePathString().length)
        val packageName = r.judgeInfo.packageName ?: ""
        val className = r.judgeInfo.className
        val taskName = r.judgeInfo.taskName
        val compileError = r.judgeInfo.compileResult.fold({ it }, { "" }).trimEnd().replace(',', ' ').escaped()
        val command = r.command
        val arg = r.testCase.arg
        val input = r.testCase.input.joinToString("\n").escaped()
        val expect = r.testCase.expect.joinToString("\n").escaped()
        val output = r.output.trimEnd().escaped()
        val errorOutput = r.errorOutput.trimEnd().escaped()
        val testCaseName = r.testCase.name
        val match = r.isPassTest
        val runningError = r.errorOutput.isNotBlank()
        val isCompileError = r.judgeInfo.compileResult.isLeft()
        val stat = when {
            taskName == "unknown" -> "??"
            isCompileError -> "CE"
            runningError -> "RE"
            !match -> "WA"
            match -> "AC"
            else -> "??"
        }

        resultTable.computeIfAbsent(studentId) {
            mutableMapOf()
        }["$taskName:$testCaseName"] = stat

        outputStream.appendLine(
            """$studentId,$studentName,$sourcePath,$taskName,$testCaseName,$compileError,$arg,$input,$stat,$expect,$output,$errorOutput,$packageName,$className,$command"""
        )
    }
    outputStream.close()

    val summaryOut = BufferedWriter(
        FileWriter(
            summaryFile,
            if(outputWithSJIS == true) Charset.forName("Shift_JIS") else Charset.defaultCharset(),
            false
        )
    )
    val taskNames = config.tasks.mapValues  {
        it.value.testcase?.size?:0
    }
    val taskNameList = config.tasks.flatMap {
        (it.value.testcase ?: listOf()).map { testCase ->
            "${it.key}:${testCase.name}"
        }
    }
    val nl = taskNames.map { "${it.key}%" }
    summaryOut.appendLine("""ID,Name,${nl.joinToString()},${taskNameList.joinToString()}""")
    for(row in resultTable) {
        val studentID = row.key
        val studentName = studentNameTable[studentID] ?: ""
        val data = row.value
        val stats = taskNameList.map { data[it] ?: data[it.takeWhile { c -> c != ':' }.plus(':')] ?: data["unknown:"] }
        val ll = taskNames.mapValues { n ->
            taskNameList.filter { it.startsWith(n.key) }.count { data[it] == "AC" } to n.value
        }.map { (it.value.first.toFloat()/it.value.second.toFloat()*100f).toInt().toString() }
        summaryOut.appendLine("""$studentID,$studentName,${ll.joinToString()},${stats.joinToString()}""")
    }

    summaryOut.close()
    println("result file wrote to $outputPath")

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
    val outputStream = process.outputStream.buffered().writer()
    if(testCase.input.isNotEmpty()) {
        testCase.input.forEach { inputString ->
            outputStream.write(inputString)
        }
    }
    outputStream.flush()
    outputStream.close()
    process.waitFor(runningTimeOutMilli, TimeUnit.MILLISECONDS)
    val errorOutput = String(process.errorStream.readAllBytes(), Charset.forName("Shift_JIS"))
    val output = String(process.inputStream.readAllBytes(), Charset.forName("Shift_JIS"))
    process.destroy()


    val out = output.replace('\r', ' ').replace('\n', ' ')
    val pass = testCase.expectRegex.firstOrNull()?.matches(out) ?: false

    TestResult(
        output,
        errorOutput,
        process.exitValue(),
        pass,
        infoForJudge,
        testCase,
        command
    )
}


val packageRegex = Regex("""^package\s+(.*)\s*;\s*$""")
fun determineTaskNumberAndPackageName(
    config: Config,
    file: File,
    charset: Charset
): Pair<String, String?> {
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
    return (taskName ?: "unknown") to (packageName)
}

fun getAllTargetDirectories(path: String): List<File> {
    return File(path).listFiles()?.filter { it.isDirectory && it.name.endsWith("assignsubmission_file_") } ?: listOf()
}

val studentNameTable = mutableMapOf<String, String>()
fun unzipSubmissionZipToWorkspace(submissionDirectory: File, workspacePath: Path) {
    if(submissionDirectory.name == "workspace")
        return
    val studentID = submissionDirectory.name.slice(0..7)
    val studentName = submissionDirectory.name.drop(9).takeWhile { it != '_' }
    studentNameTable[studentID] = studentName
    val dstPath = workspacePath.resolve(studentID).resolve("sources")
    if(Files.notExists(dstPath))
        Files.createDirectories(dstPath)
    submissionDirectory.listFiles()?.filter { it.name.lowercase().endsWith("zip") }?.forEach { zipFile ->
        unzipFile(zipFile.toPath(), dstPath)
    }
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
        val process = runtime.exec(
            """javac -J-Duser.language=en "${sourceFile.absolutePath}" -d "${dstDirectory.absolutePathString()}" -encoding $encoding"""
        )
        process.waitFor(compileTimeOutMilli, TimeUnit.MILLISECONDS)
        val errorOutput = String(process.errorStream.readAllBytes())
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
    return "unreachable condition".left() to Encoding.Unknown
}