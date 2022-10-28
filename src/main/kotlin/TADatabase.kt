import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

class TADatabase private constructor(private val db: Database) {
    companion object {
        fun connect(dbFilePath: String): TADatabase {
            val db = Database.connect("jdbc:sqlite:$dbFilePath", "org.sqlite.JDBC")
            return TADatabase(db)
        }

        fun init() {
            transaction {
                SchemaUtils.drop(StudentTable, CompileResultTable)
                SchemaUtils.create(StudentTable, CompileResultTable)
            }
        }

        fun addCompileResult(result: CompileResult): Unit = transaction {
            CompileResultTable.insert {
                it[javaSource] = result.javaSource.absolutePath
                it[studentBase] = result.studentBase.absolutePath
                it[studentID] = result.studentID.id
                it[base] = result.base.absolutePath
                it[dstDirectory] = result.dstDirectory.absolutePathString()
                when(result.result) {
                    is CompileResultDetail.Error -> {
                        it[compileStatus] = "Error"
                        it[utf8ErrorCommand] = result.result.utf8Error.compileCommand
                        it[utf8Error] = result.result.utf8Error.error
                        it[sJisErrorCommand] = result.result.sJisError.compileCommand
                        it[sJisError] = result.result.sJisError.error
                    }
                    is CompileResultDetail.Failure -> {
                        it[compileStatus] = "Failure"
                        it[compileCommand] = result.result.compileCommand
                        it[encoding] = result.result.encoding.name
                        it[errorMessage] = result.result.errorMessage
                        it[prevCompileCommand] = result.result.prevCompileCommand
                    }
                    is CompileResultDetail.Success -> {
                        it[compileStatus] = "Success"
                        it[compileCommand] = result.result.compileCommand
                        it[encoding] = result.result.encoding.name
                        it[className] = result.result.className
                        it[prevCompileCommand] = result.result.prevCompileCommand
                    }
                }
            }
        }

        fun getCompileResult(id: Int): CompileResult? {
            return transaction {
                CompileResultTable.select {
                    CompileResultTable.id eq id
                }
            }.firstOrNull()?.toCompileResult()
        }

        fun forEachCompileResult(action: (CompileResult, Int)-> Unit) {
            transaction {
                CompileResultTable.selectAll().forEach {
                    action(it.toCompileResult(), it[CompileResultTable.id].value)
                }
            }
        }

    }
}

private object StudentTable: IdTable<String>("student") {
    override val id = text("id").uniqueIndex().entityId()
    override val primaryKey: PrimaryKey = PrimaryKey(id)
    val name = text("name")
}

private object CompileResultTable: IntIdTable("compile_result", "id") {
    val javaSource = text("java_source")
    val studentBase = text("student_base")
    val studentID = text("student_id")
    val base = text("base")
    val dstDirectory = text("dst_directory")
    val compileStatus = text("compile_status")
    val compileCommand = text("compile_command")
    val encoding = text("encoding")
    val className = text("class_name").default("")
    val prevCompileCommand = text("prev_compile_command").nullable()
    val errorMessage = text("error_message").default("")
    val utf8ErrorCommand = text("utf8_error_command").default("")
    val utf8Error = text("utf8_error").default("")
    val sJisErrorCommand = text("sjis_error_command").default("")
    val sJisError = text("sjis_error").default("")
}

private object TestInfoTable: IntIdTable("test_info", "id") {
    val compileResultId = integer("compile_result_id").references(CompileResultTable.id)

}

private fun ResultRow.toCompileResult(): CompileResult {
    val it = this
    val r = kotlin.runCatching {
        when(it[CompileResultTable.compileStatus]) {
            "Error" -> {
                CompileResultDetail.Error(
                    CompileErrorDetail(
                        it[CompileResultTable.utf8ErrorCommand],
                        it[CompileResultTable.utf8Error]
                    ),
                    CompileErrorDetail(
                        it[CompileResultTable.sJisErrorCommand],
                        it[CompileResultTable.sJisError]
                    )
                )
            }
            "Failure" -> {
                CompileResultDetail.Failure(
                    it[CompileResultTable.compileCommand],
                    Encoding.valueOf(it[CompileResultTable.encoding]),
                    it[CompileResultTable.errorMessage],
                    it[CompileResultTable.prevCompileCommand]
                )
            }
            "Success" -> {
                CompileResultDetail.Success(
                    it[CompileResultTable.compileCommand],
                    Encoding.valueOf(it[CompileResultTable.encoding]),
                    it[CompileResultTable.className],
                    it[CompileResultTable.prevCompileCommand]
                )
            }
            else -> null
        }
    }.getOrNull() ?: CompileResultDetail.Error(
        CompileErrorDetail("", "DB error: deserialize error"),
        CompileErrorDetail("", "")
    )
    return CompileResult(
        r,
        StudentID(it[CompileResultTable.studentID]),
        File(it[CompileResultTable.javaSource]),
        File(it[CompileResultTable.studentBase]),
        File(it[CompileResultTable.base]),
        Path(it[CompileResultTable.dstDirectory])
    )
}