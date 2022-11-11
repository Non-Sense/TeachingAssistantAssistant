import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.InsertStatement
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
                SchemaUtils.drop(StudentTable, CompileResultTable, TestInfoTable)
                SchemaUtils.create(StudentTable, CompileResultTable, TestInfoTable)
            }
        }

        fun addAllStudent(students: Map<StudentID, String>) {
            transaction {
                StudentTable.batchInsert(students.asIterable()) {
                    this[StudentTable.id] = it.key.id
                    this[StudentTable.name] = it.value
                }
            }
        }

        fun addCompileResult(result: CompileResult): Unit = transaction {
            CompileResultTable.insert {
                fromCompileResult(it, result)
            }
        }

        fun addAllCompileResult(result: Collection<CompileResult>): Unit = transaction {
            CompileResultTable.batchInsert(result) {
                CompileResultTable.fromCompileResult(this, it)
            }
        }

        fun getCompileResult(id: Int): CompileResult? {
            return transaction {
                CompileResultTable.select {
                    CompileResultTable.id eq id
                }
            }.firstOrNull()?.toCompileResult()
        }

        fun forEachCompileResult(action: (CompileResult) -> Unit) {
            transaction {
                CompileResultTable.selectAll().forEach {
                    action(it.toCompileResult())
                }
            }
        }

        fun <R> flatMapCompileResult(transform: (CompileResult) -> Iterable<R>): List<R> {
            return transaction {
                CompileResultTable.selectAll().flatMap {
                    transform(it.toCompileResult())
                }
            }
        }

        fun addAllTestInfo(list: Collection<TestInfo>) {
            transaction {
                TestInfoTable.batchInsert(list) {
                    this[TestInfoTable.compileResultId] = it.compileResultId
                    this[TestInfoTable.taskName] = it.taskName?.name
                }
            }
        }

        private fun testInfoJoined() = transaction {
            TestInfoTable.leftJoin(
                otherTable = CompileResultTable,
                onColumn = { compileResultId },
                otherColumn = { CompileResultTable.id }
            )
        }

        private fun forEachTestInfoJoined() {
            transaction {
                testInfoJoined().selectAll().forEach {

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
    val packageName = text("package_name").nullable()
    val className = text("class_name").default("")
    val errorMessage = text("error_message").default("")
    val utf8ErrorCommand = text("utf8_error_command").default("")
    val utf8Error = text("utf8_error").default("")
    val sJisErrorCommand = text("sjis_error_command").default("")
    val sJisError = text("sjis_error").default("")
}

private object TestInfoTable: IntIdTable("test_info", "id") {
    val compileResultId = integer("compile_result_id").references(CompileResultTable.id)
    val taskName = text("task_name").nullable()
}

private fun <T: InsertStatement<*>> CompileResultTable.fromCompileResult(it: T, result: CompileResult) {
    it[javaSource] = result.javaSource.absolutePath
    it[studentBase] = result.studentBase.absolutePath
    it[studentID] = result.studentID.id
    it[base] = result.base.absolutePath
    it[dstDirectory] = result.dstDirectory.absolutePathString()
    it[packageName] = result.result.packageName()?.name
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
        }
        is CompileResultDetail.Success -> {
            it[compileStatus] = "Success"
            it[compileCommand] = result.result.compileCommand
            it[encoding] = result.result.encoding.name
            it[className] = result.result.className
        }
    }
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
                    it[CompileResultTable.packageName]?.let { PackageName(it) }
                )
            }
            "Success" -> {
                CompileResultDetail.Success(
                    it[CompileResultTable.compileCommand],
                    Encoding.valueOf(it[CompileResultTable.encoding]),
                    it[CompileResultTable.className],
                    it[CompileResultTable.packageName]?.let { PackageName(it) }
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
        Path(it[CompileResultTable.dstDirectory]),
        it[CompileResultTable.id].value
    )
}

private fun ResultRow.toTestInfoJoined(): TestInfoJoined {
    return TestInfoJoined(
        taskName = this[TestInfoTable.taskName]?.let { TaskName(it) },
        packageName = this[CompileResultTable.packageName]?.let { PackageName(it) },
        compileResult = toCompileResult(),
    )
}