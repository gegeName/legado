package io.legado.app.data.entities

import android.os.Parcelable
import android.text.TextUtils
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.replace
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import splitties.init.appCtx
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

@Parcelize
@Entity(
    tableName = "replace_rules",
    indices = [(Index(value = ["id"]))]
)
data class ReplaceRule(
    @PrimaryKey(autoGenerate = true)
    var id: Long = System.currentTimeMillis(),
    //名称
    @ColumnInfo(defaultValue = "")
    var name: String = "",
    //分组
    var group: String? = null,
    //替换内容
    @ColumnInfo(defaultValue = "")
    var pattern: String = "",
    //替换为
    @ColumnInfo(defaultValue = "")
    var replacement: String = "",
    //作用范围
    var scope: String? = null,
    //作用于标题
    @ColumnInfo(defaultValue = "0")
    var scopeTitle: Boolean = false,
    //作用于正文
    @ColumnInfo(defaultValue = "1")
    var scopeContent: Boolean = true,
    //排除范围
    var excludeScope: String? = null,
    //是否启用
    @ColumnInfo(defaultValue = "1")
    var isEnabled: Boolean = true,
    //是否正则
    @ColumnInfo(defaultValue = "1")
    var isRegex: Boolean = true,
    //超时时间
    @ColumnInfo(defaultValue = "3000")
    var timeoutMillisecond: Long = 3000L,
    //排序
    @ColumnInfo(name = "sortOrder", defaultValue = "0")
    var order: Int = Int.MIN_VALUE
) : Parcelable {

    override fun equals(other: Any?): Boolean {
        if (other is ReplaceRule) {
            return other.id == id
        }
        return super.equals(other)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    val regex: Regex by lazy {
        pattern.toRegex()
    }

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    private val plainRegexPattern: String? by lazy {
        ReplaceRuleOptimizer.toPlainLiteral(pattern)
    }

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    private val quickSearch by lazy {
        ReplaceRuleOptimizer.buildQuickSearch(pattern)
    }

    fun getDisplayNameGroup(): String {
        return if (group.isNullOrBlank()) {
            name
        } else {
            String.format("%s (%s)", name, group)
        }
    }

    fun isValid(): Boolean {
        if (TextUtils.isEmpty(pattern)) {
            return false
        }
        //判断正则表达式是否正确
        if (isRegex) {
            try {
                Pattern.compile(pattern)
            } catch (ex: PatternSyntaxException) {
                AppLog.put("正则语法错误或不支持：${ex.localizedMessage}", ex)
                return false
            }
            // Pattern.compile测试通过，但是部分情况下会替换超时，报错，一般发生在修改表达式时漏删了
            if (pattern.endsWith('|') && !pattern.endsWith("\\|")) {
                return false
            }
        }
        return true
    }

    @Throws(NoStackTraceException::class)
    fun checkValid() {
        if (!isValid()) {
            throw NoStackTraceException(appCtx.getString(R.string.replace_rule_invalid))
        }
    }

    fun getValidTimeoutMillisecond(): Long {
        if (timeoutMillisecond <= 0) {
            return 3000L
        }
        return timeoutMillisecond
    }

    fun replaceIn(content: String): String {
        if (pattern.isEmpty()) {
            return content
        }
        if (!isRegex) {
            return if (content.contains(pattern)) {
                content.replace(pattern, replacement)
            } else {
                content
            }
        }
        val plainPattern = plainRegexPattern
        if (plainPattern != null && canUsePlainReplace()) {
            return if (content.contains(plainPattern)) {
                content.replace(plainPattern, replacement)
            } else {
                content
            }
        }
        if (!quickSearch.mayMatch(content)) {
            return content
        }
        return content.replace(regex, replacement, getValidTimeoutMillisecond())
    }

    private fun canUsePlainReplace(): Boolean {
        return !replacement.startsWith("@js:")
            && !replacement.contains('$')
            && !replacement.contains('\\')
    }
}

private object ReplaceRuleOptimizer {

    private val regexMetaChars = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')
    private val escapableLiteralChars = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}', '-')
    private val ignoreCaseFlagRegex = Regex("\\(\\?[idmsuxU-]*i[idmsuxU-]*(?=[:)])")
    private val inlineFlagRegex = Regex("^\\(\\?[idmsuxU-]+\\)")

    fun toPlainLiteral(regex: String): String? {
        if (regex.isEmpty()) {
            return regex
        }
        val builder = StringBuilder(regex.length)
        var index = 0
        while (index < regex.length) {
            when (val char = regex[index]) {
                '\\' -> {
                    if (index + 1 >= regex.length) {
                        return null
                    }
                    when (val next = regex[index + 1]) {
                        'Q' -> {
                            val end = regex.indexOf("\\E", index + 2)
                            if (end < 0) {
                                return null
                            }
                            builder.append(regex, index + 2, end)
                            index = end + 2
                        }
                        in escapableLiteralChars -> {
                            builder.append(next)
                            index += 2
                        }
                        else -> {
                            return null
                        }
                    }
                }
                in regexMetaChars -> return null
                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        return builder.toString()
    }

    fun buildQuickSearch(regex: String): QuickSearch {
        val plain = toPlainLiteral(regex)
        if (!plain.isNullOrEmpty()) {
            return QuickSearch(listOf(plain), regex.hasIgnoreCaseFlag())
        }
        val alternatives = regex.splitTopLevelAlternatives()
        if (alternatives.isEmpty()) {
            return QuickSearch.EMPTY
        }
        val literals = arrayListOf<String>()
        for (alternative in alternatives) {
            val literal = alternative.extractSimpleRequiredLiteral() ?: return QuickSearch.EMPTY
            if (literal.length < 2) {
                return QuickSearch.EMPTY
            }
            literals.add(literal)
        }
        return QuickSearch(literals.distinct(), regex.hasIgnoreCaseFlag())
    }

    private fun String.hasIgnoreCaseFlag(): Boolean {
        return ignoreCaseFlagRegex.containsMatchIn(this)
    }

    private fun String.splitTopLevelAlternatives(): List<String> {
        val alternatives = arrayListOf<String>()
        var start = 0
        var escaped = false
        var inCharClass = false
        var groupDepth = 0
        for (index in indices) {
            val char = this[index]
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                inCharClass -> if (char == ']') inCharClass = false
                char == '[' -> inCharClass = true
                char == '(' -> groupDepth++
                char == ')' && groupDepth > 0 -> groupDepth--
                char == '|' && groupDepth == 0 -> {
                    alternatives.add(substring(start, index))
                    start = index + 1
                }
            }
        }
        alternatives.add(substring(start))
        return alternatives
    }

    private fun String.extractSimpleRequiredLiteral(): String? {
        val source = trimInlineFlagsAndAnchors()
        if (source.isEmpty()) {
            return null
        }
        if (source.any { it == '(' || it == ')' || it == '[' || it == ']' || it == '{' || it == '}' }) {
            return null
        }
        val literals = arrayListOf<String>()
        val builder = StringBuilder()
        var index = 0
        while (index < source.length) {
            when (val char = source[index]) {
                '\\' -> {
                    if (index + 1 >= source.length) {
                        return null
                    }
                    val next = source[index + 1]
                    if (next in escapableLiteralChars) {
                        builder.append(next)
                        index += 2
                    } else {
                        return null
                    }
                }
                '.' -> {
                    flushLiteral(builder, literals)
                    index++
                    while (index < source.length && source[index].isRegexRepeatModifier()) {
                        index++
                    }
                }
                '^', '$' -> {
                    flushLiteral(builder, literals)
                    index++
                }
                '*', '+', '?' -> return null
                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        flushLiteral(builder, literals)
        return literals.maxByOrNull { it.length }
    }

    private fun String.trimInlineFlagsAndAnchors(): String {
        var value = this
        while (true) {
            val next = inlineFlagRegex.replace(value, "")
                .trimStart('^')
                .trimEnd('$')
            if (next == value) {
                return value
            }
            value = next
        }
    }

    private fun Char.isRegexRepeatModifier(): Boolean {
        return this == '*' || this == '+' || this == '?'
    }

    private fun flushLiteral(builder: StringBuilder, literals: MutableList<String>) {
        if (builder.isNotEmpty()) {
            literals.add(builder.toString())
            builder.clear()
        }
    }

    data class QuickSearch(
        val literals: List<String>,
        val ignoreCase: Boolean
    ) {
        fun mayMatch(content: CharSequence): Boolean {
            return literals.isEmpty() || literals.any {
                content.contains(it, ignoreCase)
            }
        }

        companion object {
            val EMPTY = QuickSearch(emptyList(), false)
        }
    }
}
