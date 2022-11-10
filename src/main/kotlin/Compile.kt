import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString


private fun calcDstPath(studentBase: File): Path {
    return studentBase.toPath().resolve("compile")
}

private fun findJavaSourceFiles(targetPath: Path): Sequence<File> {
    return targetPath.toFile().walk().filter { it.name.endsWith(".java") }
}

suspend fun compile(workspacePath: Path, config: Config): List<CompileResult>? {

    class Tmp(
        val javaSource: File,
        val studentBase: File,
        val dstPath: Path,
        val base: File
    )

    val sources = workspacePath.toFile().listFiles()?.flatMap { studentBase ->
        val base = studentBase.resolve("sources")
        findJavaSourceFiles(base.toPath()).map {
            Tmp(it, studentBase, calcDstPath(studentBase), base)
        }
    }
    val totalSize = sources?.size ?: 10

    val printer = ProgressPrinter(totalSize)

    val jobs = sources?.map { p ->
        CoroutineScope(Dispatchers.Default).async {
            val res = compileJavaSource(p.javaSource, p.dstPath, config.compileTimeout, p.base.toPath(), config)
            printer.add()
            CompileResult(res, StudentID(p.studentBase.name), p.javaSource, p.studentBase, p.base, p.dstPath)
        }
    }

    return jobs?.awaitAll()
}

private data class Detail(
    val exitCode: Int,
    val compiledFilePath: Path?,
    val compiledName: String?,
    val encoding: Encoding,
    val prevCompileCommand: String?,
    val encodingError: Boolean = false,
    val errorMessage: String = ""
)

private data class InternalCompileResult(
    val command: String,
    val detail: Result<Detail>,
    val packageName: PackageName?
)

private fun InternalCompileResult.toDetail(): CompileResultDetail? {
    val detail = detail.getOrNull() ?: return null
    if(detail.exitCode == 0 && detail.compiledName != null)
        return CompileResultDetail.Success(
            command,
            detail.encoding,
            detail.compiledName,
            detail.prevCompileCommand,
            packageName
        )
    if(!detail.encodingError)
        return CompileResultDetail.Failure(
            command,
            detail.encoding,
            detail.errorMessage,
            detail.prevCompileCommand,
            packageName
        )
    return null
}

private fun compileWithEncoding(
    file: File,
    encoding: Encoding,
    classPath: Path,
    dstDirectory: Path,
    compileTimeOutMilli: Long,
    retry: Boolean = false,
    prevCompileCommand: String? = null
): InternalCompileResult {
    val packageName = determinePackageName(file, encoding.charset)
    val command = runCatching {
        """javac -J-Duser.language=en -d "${dstDirectory.absolutePathString()}" -encoding ${encoding.charset.name()} -cp "${classPath.absolutePathString()}" "${file.absolutePath}""""
    }.fold({ it }, { return InternalCompileResult("", Result.failure(it), packageName) })
    val detail = runCatching {
        val runtime = Runtime.getRuntime()

        val process = runtime.exec(command)
        process.waitFor(compileTimeOutMilli, TimeUnit.MILLISECONDS)

        val errorOutput = String(process.errorStream.readBytes())
        process.destroy()
        if(errorOutput.isEmpty()) {
            Detail(
                exitCode = process.exitValue(),
                compiledFilePath = dstDirectory,
                compiledName = file.name.dropLast(5),
                encoding = encoding,
                prevCompileCommand = prevCompileCommand,
            )
        } else {
            if(retry && errorOutput.contains("error: cannot find symbol")) {
                return compileWithEncoding(
                    file,
                    encoding,
                    Path(file.parent),
                    dstDirectory,
                    compileTimeOutMilli,
                    false,
                    command
                )
            }
            Detail(
                exitCode = process.exitValue(),
                compiledFilePath = null,
                compiledName = null,
                encoding = encoding,
                prevCompileCommand = prevCompileCommand,
                encodingError = errorOutput.contains("error: unmappable character"),
                errorMessage = errorOutput
            )
        }
    }
    return InternalCompileResult(command, detail, packageName)
}

private fun compileJavaSource(
    sourceFile: File,
    dstDirectory: Path,
    compileTimeOutMilli: Long,
    classPath: Path,
    config: Config
): CompileResultDetail {


    if(Files.notExists(dstDirectory))
        Files.createDirectories(dstDirectory)

//    fun commentOutPackage(file: File, charset: Charset): File {
//        val filepath = file.absolutePath.dropLast(7)
//        val outputFile = File(filepath)
//        val output = outputFile.bufferedWriter(charset)
//        file.forEachLine(charset) {
//            val s = if(packageRegex.matches(it))
//                "// $it"
//            else
//                it
//            output.appendLine(s)
//        }
//        output.close()
//        return outputFile
//    }

    var targetFile = sourceFile
    if(config.disablePackage) {
        targetFile = File(sourceFile.absolutePath + ".origin")
        sourceFile.renameTo(targetFile)
    }
//    val utf8File = if(config.disablePackage) {
//        commentOutPackage(targetFile, Encoding.UTF8.charset)
//    } else {
//        targetFile
//    }
    val utf8File = targetFile
    val utf8Result = compileWithEncoding(
        utf8File,
        Encoding.UTF8,
        classPath,
        dstDirectory,
        compileTimeOutMilli,
        config.allowAmbiguousClassPath
    )
    utf8Result.toDetail()?.let {
        if(config.disablePackage)
            targetFile.delete()
        return it
    }

//    val sJisFile = if(config.disablePackage) {
//        commentOutPackage(targetFile, Encoding.UTF8.charset)
//    } else {
//        targetFile
//    }
    val sJisFile = targetFile
    val sJisResult = compileWithEncoding(
        sJisFile,
        Encoding.ShiftJIS,
        classPath,
        dstDirectory,
        compileTimeOutMilli,
        config.allowAmbiguousClassPath
    )
    sJisResult.toDetail()?.let {
        if(config.disablePackage)
            targetFile.delete()
        return it
    }

    if(config.disablePackage)
        targetFile.delete()
    return CompileResultDetail.Error(
        utf8Error = CompileErrorDetail(
            utf8Result.command,
            (utf8Result.detail.exceptionOrNull() ?: Throwable("unreachable")).stackTraceToString()
        ),
        sJisError = CompileErrorDetail(
            sJisResult.command,
            (sJisResult.detail.exceptionOrNull() ?: Throwable("unreachable")).stackTraceToString()
        ),
    ).also {
        System.err.println("InternalCompileError: ")
        System.err.println(it.utf8Error.error)
        System.err.println(it.sJisError.error)
    }
}