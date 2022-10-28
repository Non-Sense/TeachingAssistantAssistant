import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Config(
    val compileTimeout: Long,
    val runningTimeout: Long,
    val outputFileName: String = "output",
    val disablePackage: Boolean = false,
    val allowAmbiguousClassPath: Boolean = false,
    val tasks: Map<TaskName, Task>
)

@Serializable
data class Task(
    val word: List<String>,
    val excludeWord: List<String> = listOf(),
    val testcase: List<TestCase> = listOf()
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