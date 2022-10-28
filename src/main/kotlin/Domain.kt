import java.nio.charset.Charset

enum class Judge(private val displayName: String) {
    AC("AC"),
    WA("WA"),
    TLE("TLE"),
    RE("RE"),
    CE("CE"),
    IE("IE"),
    NF("NF"),
    CF("CF"),
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

enum class Encoding(val charset: Charset) {
    UTF8(Charset.forName("UTF-8")),
    ShiftJIS(Charset.forName("Shift_JIS")),
    Unknown(Charset.defaultCharset())
}

@JvmInline
value class PackageName(
    val name: String
)

@JvmInline
value class StudentID(
    val id: String
) {
    override fun toString(): String {
        return id
    }
}