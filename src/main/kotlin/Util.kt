import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger

class ProgressPrinter(private val totalSize: Int) {
    private val counter = AtomicInteger(0)
    private val v = (1..10).map { (totalSize.toFloat()/10f*it).toInt() }
    fun add() {
        counter.incrementAndGet().let {
            val i = v.indexOf(it)
            if(i != -1)
                print("${(i + 1)*10}% ")
            if(i == 9)
                println()
        }
    }
}


private val packageRegex = Regex("""^package\s+(.*)\s*;\s*$""")
fun determinePackageName(file: File, charset: Charset): PackageName? {
    runCatching {
        for(line in file.readLines(charset)) {
            packageRegex.find(line)?.groups?.get(1)?.value?.let {
                return PackageName(it)
            }
        }
    }
    return null
}

private fun String.contains(strings: Collection<String>): Boolean {
    return strings.firstOrNull { this.contains(it) } != null
}

fun determineTaskName(config: Config, file: File, charset: Charset?): List<TaskName?> {
    charset ?: return listOf(null)
    val taskNames = mutableSetOf<TaskName>()
    val excludedTasks = mutableSetOf<TaskName>()
    for(line in file.readLines(charset)) {
        for((taskName, task) in config.tasks) {
            if(line.contains(task.excludeWord)) {
                taskNames.remove(taskName)
                excludedTasks.add(taskName)
            }
            if(!excludedTasks.contains(taskName) && line.contains(task.word)) {
                taskNames.add(taskName)
            }
        }
    }
    if(taskNames.isEmpty())
        return listOf(null)
    return taskNames.toList()
}