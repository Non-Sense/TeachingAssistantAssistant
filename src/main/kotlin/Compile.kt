import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.name

private fun findJavaSourceFiles(targetPath: Path): Sequence<File> {
    return targetPath.toFile().walk().filter { it.name.endsWith(".java") }
}

suspend fun compile(workspacePath: Path, config: Config): List<CompileResult>? {

    class Tmp(
        val javaSource: File,
        val studentBase: Path,
        val sourceBase: Path,
        val compileBase: Path
    )

    val sources = workspacePath.toFile().listFiles()?.flatMap { studentBase ->
        val base = studentBase.resolve("sources").toPath()
        val compileBase = studentBase.resolve("compile").toPath()
        findJavaSourceFiles(base).map {
            Tmp(it, studentBase.toPath(), base, compileBase)
        }
    }
    val totalSize = sources?.size ?: 10

    val printer = ProgressPrinter(totalSize)

    val jobs = sources?.map { p ->
        CoroutineScope(Dispatchers.Default).async {
            val res = compileJavaSource(p.javaSource, config.compileTimeout, p.sourceBase, p.compileBase)
            printer.add()
            CompileResult(
                result = res,
                studentID = StudentID(p.studentBase.name),
                javaSource = p.javaSource,
                studentBase = p.studentBase,
                sourceBase = p.sourceBase,
                compileBase = p.compileBase
            )
        }
    }

    return jobs?.awaitAll()
}

private data class Detail(
    val exitCode: Int,
    val compiledName: String?,
    val encoding: Encoding,
    val isEncodingError: Boolean = false,
    val errorMessage: String = "",
)

private data class InternalCompileResult(
    val command: String,
    val detail: Result<Detail>,
    val packageName: PackageName?,
    val classPath: Path
)

private fun InternalCompileResult.toDetail(): CompileResultDetail? {
    val detail = detail.getOrNull() ?: return null
    if(detail.exitCode == 0 && detail.compiledName != null)
        return CompileResultDetail.Success(
            command,
            detail.encoding,
            detail.compiledName,
            packageName,
            classPath
        )
    if(!detail.isEncodingError)
        return CompileResultDetail.Failure(
            command,
            detail.encoding,
            detail.errorMessage,
            packageName
        )
    return null
}

fun determineClassPath(sourceFile: File, packageName: PackageName?): Path? {
    if(packageName == null)
        return sourceFile.toPath().parent
    val packageHierarchy = packageName.name.split('.').reversed()
    if(packageHierarchy.isEmpty())
        return sourceFile.toPath().parent
    var path: Path? = sourceFile.toPath().parent
    packageHierarchy.forEach {
        if(path?.fileName?.name != it)
            return path
        path = path?.parent
    }
    return path
}

private fun calcDstPath(sourceBasePath: Path, sourceClassPath: Path, compileBasePath: Path): Path {
    val a = sourceClassPath.absolutePathString().drop(sourceBasePath.absolutePathString().length + 1)
    if(a.isEmpty())
        return compileBasePath
    return compileBasePath.resolve(a)
}

private fun compileWithEncoding(
    sourceFile: File,
    encoding: Encoding,
    compileTimeOutMilli: Long,
    sourceBasePath: Path,
    compileBasePath: Path
): InternalCompileResult {

    val packageName = determinePackageName(sourceFile, encoding.charset)
    val sourceClassPath = determineClassPath(sourceFile, packageName)

    val dstPath = sourceClassPath?.let { calcDstPath(sourceBasePath, it, compileBasePath) } ?: compileBasePath

    if(Files.notExists(dstPath))
        Files.createDirectories(dstPath)

    val command = runCatching {
        StringBuilder("javac -J-Duser.language=en ").apply {
            append("""-d "${dstPath.absolutePathString()}" """)
            append("""-encoding ${encoding.charset.name()} """)
            append("""-cp "${sourceClassPath?.absolutePathString() ?: sourceFile.toPath().parent.absolutePathString()}" """)
            append(""""${sourceFile.absolutePath}"""")
        }.toString()
    }.fold({ it }, { return InternalCompileResult("", Result.failure(it), packageName, dstPath) })

    val detail = runCatching {
        val runtime = Runtime.getRuntime()

        val process = runtime.exec(command)
        process.waitFor(compileTimeOutMilli, TimeUnit.MILLISECONDS)

        val errorOutput = String(process.errorStream.readBytes())
        process.destroy()
        if(errorOutput.isEmpty()) {
            Detail(
                exitCode = process.exitValue(),
                compiledName = sourceFile.name.dropLast(5),
                encoding = encoding,
            )
        } else {
            Detail(
                exitCode = process.exitValue(),
                compiledName = null,
                encoding = encoding,
                isEncodingError = errorOutput.contains("error: unmappable character"),
                errorMessage = errorOutput
            )
        }
    }

    return InternalCompileResult(command, detail, packageName, dstPath)
}

private fun compileJavaSource(
    sourceFile: File,
    compileTimeOutMilli: Long,
    sourceBasePath: Path,
    compileBasePath: Path
): CompileResultDetail {

    val utf8Result = compileWithEncoding(
        sourceFile = sourceFile,
        encoding = Encoding.UTF8,
        compileTimeOutMilli = compileTimeOutMilli,
        sourceBasePath = sourceBasePath,
        compileBasePath = compileBasePath
    )
    utf8Result.toDetail()?.let {
        return it
    }

    val sJisResult = compileWithEncoding(
        sourceFile = sourceFile,
        encoding = Encoding.ShiftJIS,
        compileTimeOutMilli = compileTimeOutMilli,
        sourceBasePath = sourceBasePath,
        compileBasePath = compileBasePath
    )
    sJisResult.toDetail()?.let {
        return it
    }

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