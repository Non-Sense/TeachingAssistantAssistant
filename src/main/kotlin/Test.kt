//import arrow.core.escaped
//import kotlinx.coroutines.*
//import java.io.File
//import java.nio.charset.Charset
//import java.nio.file.Path
//import java.util.concurrent.TimeUnit
//
//private sealed class TestJob {
//    data class Auto(
//        val job: Deferred<TestResult>
//    ): TestJob()
//
//    data class Manual(
//        val job: Deferred<TestResult>
//    ): TestJob()
//}
//
//private fun makeErrorTestResult(testInfoId: Int, it: Throwable, testCase: TestCase, taskName: TaskName): TestResult {
//    return TestResult(
//        testInfoId = testInfoId,
//        stderr = "internal error: ${it.stackTraceToString().replace("\r", "").escaped()}",
//        judge = Judge.IE,
//        testCase = testCase,
//        taskName = taskName
//    )
//}
//
//private fun makeAutoTestJob(
//    config: Config,
//    info: CompileResultDetail.Success,
//    testCase: TestCase,
//    testInfoId: Int,
//    taskName: TaskName
//): TestJob.Auto {
//    return TestJob.Auto(CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
//        codeTest(config, info, testCase, testInfoId, taskName).getOrElse { makeErrorTestResult(testInfoId, it, testCase, taskName) }
//    })
//}
//
//private fun makeManualTestJob(config: Config, info: TestInfo, testCase: TestCase): TestJob.Manual {
//    fun makeResult(): TestResult {
//        if(info.compileResult.result !is CompileResultDetail.Success)
//            return makeErrorTestResult(info.id, Throwable("unreachable"), testCase, info.taskName)
//        println("running: ${info.compileResult.studentID}:${info.compileResult.result.packageName?.name?.plus('.') ?: ""}${info.compileResult.result.className}  \t${info.taskName}:${testCase.name}\t")
//        val result = codeTest(config, info.compileResult.result, testCase, info.id, info.taskName).getOrElse {
//            return makeErrorTestResult(info.id, it, testCase, info.taskName)
//        }
//        println("command: ${result.command}")
//        println("time: ${result.runTimeNano?.let { it/1000000 }} ms ${if(result.judge == Judge.TLE) ": TLE" else ""}")
//        println("exitCode: ${result.exitCode}")
//        testCase.expect.joinToString("\n").takeIf { it.isNotEmpty() }?.let {
//            println("expected:")
//            println(it)
//            println()
//        }
//        result.stderr.takeIf { it.isNotEmpty() }?.let {
//            println("stderr:")
//            println(it)
//            println()
//        }
//        result.stdout.takeIf { it.isNotEmpty() }?.let {
//            println("stdout:")
//            println(it.trimEnd('\n', '\r', ' ', '\t', '　'))
//            println()
//        }
//        val judge = getJudgeFromInput()
//        println()
//        return result.copy(judge = judge)
//    }
//
//    return TestJob.Manual(CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
//        makeResult()
//    })
//}
//
//private fun makeTestJob(config: Config, info: TestInfo): List<TestJob> {
//    val testCases = config.tasks[info.taskName]?.testcase ?: return listOf()
//    if(info.compileResult.result !is CompileResultDetail.Success)
//        return testCases.map { testCase ->
//            TestJob.Auto(CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.LAZY) {
//                TestResult(
//                    testInfoId = info.id,
//                    judge = Judge.CE,
//                    testCase = testCase,
//                    taskName = info.taskName
//                )
//            })
//        }
//    return testCases.map { testCase ->
//        if(testCase.manualJudge ?: config.defaultConfig.manualJudge) {
//            makeManualTestJob(config, info, testCase)
//        } else {
//            makeAutoTestJob(config, info.compileResult.result, testCase, info.id, info.taskName)
//        }
//    }
//}
//
//suspend fun runTests(
//    config: Config
//): List<TestResult> {
//    val testJobs = TADatabase.flatMapTestInfo {
//        makeTestJob(config, it)
//    }
//    val autoTestJobs = testJobs.filterIsInstance<TestJob.Auto>()
//    val manualTestJobs = testJobs.filterIsInstance<TestJob.Manual>()
//
//    val results = mutableListOf<TestResult>()
//
//    if(autoTestJobs.isNotEmpty()) {
//        val jobs = autoTestJobs.map { it.job }
//        val printer = ProgressPrinter(jobs.size)
//        jobs.forEach { it.job.invokeOnCompletion { printer.add() } }
//        println("running auto judge")
//        results.addAll(jobs.awaitAll())
//    }
//    if(manualTestJobs.isNotEmpty()) {
//        val jobs = manualTestJobs.map { it.job }
//        results.addAll(jobs.map {
//            it.await()
//        })
//    }
//    return results
//}
//
//private fun getJudgeFromInput(): Judge {
//    while(true) {
//        print("judge?: ")
//        val str = readLine()
//        if(str == null) {
//            println("input error")
//            continue
//        }
//        val judge = Judge.fromStringInput(str)
//
//        if(judge == Judge.Unknown) {
//            println("invalid input")
//            continue
//        }
//        return judge
//    }
//}
//
//private fun codeTest(config: Config, info: CompileResultDetail.Success, testCase: TestCase, testInfoId: Int, taskName: TaskName): Result<TestResult> {
//    return runCatching {
//        val runtime = Runtime.getRuntime()
//        val classDir = info.classPath
//        val runClassname = "${info.packageName?.name?.plus('.') ?: ""}${info.className}"
//        val command =
//            """java -D"user.language=en" -classpath "$classDir" $runClassname ${testCase.arg}"""
//        val process = runtime.exec(command)
//        val startTime = System.nanoTime()
//        val outputStream = process.outputStream.buffered().writer()
//        if(testCase.input.isNotEmpty()) {
//            testCase.input.forEach { inputString ->
//                outputStream.write(inputString)
//            }
//        }
//        outputStream.flush()
//        val timeOut = testCase.runningTimeout ?: config.defaultConfig.runningTimeout
//        val isTle = if(timeOut >= 0) {
//            !process.waitFor(timeOut, TimeUnit.MILLISECONDS)
//        } else {
//            process.waitFor()
//            false
//        }
//        val runTime = System.nanoTime() - startTime
//        process.destroyForcibly()
//        val stderr = String(process.errorStream.readBytes(), Charset.forName("Shift_JIS"))
//        val stdout = String(process.inputStream.readBytes(), Charset.forName("Shift_JIS"))
//
//
//        val out = stdout.replace('\r', ' ').replace('\n', ' ')
//        val pass = testCase.expectRegex.firstOrNull()?.matches(out) ?: false
//
//        val runningError =
//            stderr.contains("Exception in thread") || stderr.contains("Error: Main method not found in class")
//
//        val stat = when {
//            isTle -> Judge.TLE
//            runningError -> Judge.RE
//            !pass -> Judge.WA
//            pass -> Judge.AC
//            else -> Judge.Unknown
//        }
//
//        TestResult(
//            testInfoId = testInfoId,
//            stdout = stdout,
//            stderr = stderr,
//            exitCode = process.exitValue(),
//            judge = stat,
//            testCase = testCase,
//            taskName = taskName,
//            command = command,
//            runTimeNano = runTime,
//        )
//    }
//}
