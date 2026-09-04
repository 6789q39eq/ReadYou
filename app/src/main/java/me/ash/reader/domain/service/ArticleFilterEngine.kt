package me.ash.reader.domain.service

import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterCondition
import me.ash.reader.domain.model.filter.FilterExpression
import me.ash.reader.domain.model.filter.FilterField
import me.ash.reader.domain.model.filter.FilterMatchType

/**
 * A [FilterRule] with its expression parsed and its regexes pre-compiled.
 *
 * Regexes are compiled once per sync batch (instead of per article) and kept
 * in [regexes]; invalid patterns map to null and never match, so a bad
 * pattern can never crash sync.
 */
data class CompiledFilterRule(
    val id: String,
    val action: FilterAction,
    val expression: FilterExpression,
    val regexes: Map<FilterCondition, Regex?> = compileRegexes(expression),
)

/**
 * Pure evaluation engine for filter rules — no Android dependencies.
 *
 * Semantics (see docs/plans/advanced-feed-filtering.md §2.1):
 * 1. Any matching BLOCK rule ⇒ drop.
 * 2. Otherwise, if any ALLOW rule exists ⇒ keep only when some ALLOW matches.
 * 3. No rules / no ALLOW rules ⇒ keep.
 */
object ArticleFilterEngine {

    /** Hard cap on user-supplied patterns as a light ReDoS mitigation. */
    const val MAX_PATTERN_LENGTH = 500

    fun shouldKeep(
        article: ArticleSnapshot,
        rules: List<CompiledFilterRule>,
    ): Boolean {
        // Block wins over allow regardless of rule order: check every BLOCK
        // rule first, then fall back to ALLOW semantics.
        for (rule in rules) {
            if (rule.action == FilterAction.BLOCK && matches(article, rule)) return false
        }
        var hasAllow = false
        for (rule in rules) {
            if (rule.action == FilterAction.ALLOW) {
                hasAllow = true
                if (matches(article, rule)) return true
            }
        }
        return !hasAllow
    }

    /**
     * Evaluates a single condition against the snapshot's target field.
     * Exposed internally for tests and the UI's live "test your pattern" box.
     *
     * When [precompiled] is supplied (sync path) it is reused instead of
     * compiling a new [Regex] per article; a null entry means the pattern was
     * invalid at compile time and never matches.
     */
    fun matchesCondition(
        article: ArticleSnapshot,
        condition: FilterCondition,
        precompiled: Regex? = null,
        hasPrecompiled: Boolean = false,
    ): Boolean {
        if (condition.pattern.length > MAX_PATTERN_LENGTH) return false
        return try {
            when (condition.matchType) {
                FilterMatchType.GLOB ->
                    valuesFor(article, condition.field).any { value ->
                        globRegex(condition, precompiled, hasPrecompiled)
                            ?.containsMatchIn(value) == true
                    }
                FilterMatchType.NOT_GLOB ->
                    valuesFor(article, condition.field).none { value ->
                        globRegex(condition, precompiled, hasPrecompiled)
                            ?.containsMatchIn(value) == true
                    } && valuesFor(article, condition.field).isNotEmpty()
                FilterMatchType.CONTAINS ->
                    valuesFor(article, condition.field).any { value ->
                        value.contains(condition.pattern, ignoreCase = true)
                    }
                FilterMatchType.NOT_CONTAINS ->
                    valuesFor(article, condition.field).all { value ->
                        !value.contains(condition.pattern, ignoreCase = true)
                    }
                FilterMatchType.WORD_MATCH -> {
                    valuesFor(article, condition.field).any { value ->
                        if (!isAsciiWordPattern(condition.pattern)) {
                            // CJK/complex patterns have no word boundaries (continuous
                            // script), so whole-word degrades to substring matching.
                            value.contains(condition.pattern, ignoreCase = true)
                        } else {
                            val regex =
                                if (hasPrecompiled) precompiled
                                else compileWordRegex(condition.pattern)
                            regex?.containsMatchIn(value) == true
                        }
                    }
                }
                FilterMatchType.REGEX -> {
                    val regex =
                        if (hasPrecompiled) precompiled
                        else compileUserRegex(condition.pattern)
                    valuesFor(article, condition.field).any { value ->
                        regex?.containsMatchIn(value) == true
                    }
                }
                FilterMatchType.NOT_REGEX -> {
                    val regex =
                        if (hasPrecompiled) precompiled
                        else compileUserRegex(condition.pattern)
                    // Invalid regex degrades to no-match, so NOT_REGEX with a
                    // bad pattern is false (nothing is excluded).
                    regex != null &&
                        valuesFor(article, condition.field).all { value ->
                            !regex.containsMatchIn(value)
                        }
                }
            }
        } catch (_: Exception) {
            // Invalid regex or unexpected failure: never crash sync, treat as no-match.
            false
        }
    }

    private fun matches(
        article: ArticleSnapshot,
        rule: CompiledFilterRule,
    ): Boolean = matches(article, rule.expression, rule.regexes)

    private fun matches(
        article: ArticleSnapshot,
        expression: FilterExpression,
        regexes: Map<FilterCondition, Regex?> = emptyMap(),
    ): Boolean =
        when (expression) {
            is FilterExpression.Condition ->
                if (regexes.isEmpty()) {
                    matchesCondition(article, expression.condition)
                } else {
                    matchesCondition(
                        article,
                        expression.condition,
                        precompiled = regexes[expression.condition],
                        hasPrecompiled = true,
                    )
                }
            is FilterExpression.AllOf ->
                expression.children.all { matches(article, it, regexes) }
            is FilterExpression.AnyOf ->
                expression.children.any { matches(article, it, regexes) }
            is FilterExpression.NoneOf ->
                expression.children.none { matches(article, it, regexes) }
        }

    private fun valueFor(article: ArticleSnapshot, field: FilterField): String =
        when (field) {
            FilterField.TITLE -> article.title
            FilterField.AUTHOR -> article.author.orEmpty()
            FilterField.URL -> article.link
            FilterField.CONTENT -> article.content
            FilterField.ALL -> article.title
        }

    /**
     * All values a condition applies to. [FilterField.ALL] covers title,
     * author and content (URL is intentionally excluded).
     */
    private fun valuesFor(article: ArticleSnapshot, field: FilterField): List<String> =
        when (field) {
            FilterField.TITLE -> listOf(article.title)
            FilterField.AUTHOR -> listOf(article.author.orEmpty())
            FilterField.URL -> listOf(article.link)
            FilterField.CONTENT -> listOf(article.content)
            FilterField.ALL ->
                listOf(article.title, article.author.orEmpty(), article.content)
        }

    private val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE)

    /**
     * Word boundaries are ASCII-only (`[A-Za-z0-9_]`), like Java's default
     * `\b`: this keeps Latin whole-word matching working inside CJK text
     * (CJK characters act as separators, not word characters). Patterns that
     * are not pure ASCII words have no meaningful boundaries and are handled
     * with substring semantics at the call site.
     */
    internal fun compileWordRegex(pattern: String): Regex? =
        if (pattern.length > MAX_PATTERN_LENGTH || !isAsciiWordPattern(pattern)) {
            null
        } else {
            runCatching {
                Regex(
                    "(?<![A-Za-z0-9_])${Regex.escape(pattern)}(?![A-Za-z0-9_])",
                    REGEX_OPTIONS,
                )
            }.getOrNull()
        }

    internal fun isAsciiWordPattern(pattern: String): Boolean =
        pattern.isNotEmpty() &&
            pattern.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }

    internal fun compileUserRegex(pattern: String): Regex? =
        if (pattern.length > MAX_PATTERN_LENGTH) {
            null
        } else {
            runCatching { Regex(pattern, REGEX_OPTIONS) }.getOrNull()
        }

    /**
     * Compiles a user-supplied glob (`*`, `?`, `[...]`, `{a,b}`, `\` escape)
     * into a case-insensitive [Regex]. Matching uses `containsMatchIn`, so a
     * plain `kotlin` behaves like a substring search while `*kotlin*`,
     * `kotlin*` etc. give prefix/suffix control.
     */
    internal fun compileGlob(pattern: String): Regex? =
        if (pattern.length > MAX_PATTERN_LENGTH) {
            null
        } else {
            runCatching { Regex(globToRegex(pattern), REGEX_OPTIONS) }.getOrNull()
        }

    /**
     * Converts glob syntax to an equivalent regex source. Supports `*` (any
     * sequence), `?` (any single char), `[...]` character classes (with `!`
     * negation), `{a,b}` alternations and `\` escaping. Anything else is
     * treated literally.
     */
    internal fun globToRegex(glob: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> out.append(".*")
                '?' -> out.append('.')
                '\\' -> {
                    if (i + 1 < glob.length) {
                        out.append(Regex.escape(glob[i + 1].toString()))
                        i++
                    } else {
                        out.append("\\\\")
                    }
                }
                '[' -> {
                    val end = glob.indexOf(']', i + 1)
                    if (end == -1) {
                        out.append("\\[")
                    } else {
                        var content = glob.substring(i + 1, end)
                        val negated = content.startsWith("!") || content.startsWith("^")
                        if (negated) content = content.drop(1)
                        // Escape backslashes inside the class, keep ranges.
                        content = content.replace("\\", "\\\\")
                        out.append('[')
                        if (negated) out.append('^')
                        out.append(content)
                        out.append(']')
                        i = end
                    }
                }
                '{' -> {
                    val end = findClosingBrace(glob, i)
                    if (end == -1) {
                        out.append("\\{")
                    } else {
                        val options = splitBraceOptions(glob.substring(i + 1, end))
                        out.append("(?:")
                        options.forEachIndexed { index, option ->
                            if (index > 0) out.append('|')
                            out.append(globToRegex(option))
                        }
                        out.append(')')
                        i = end
                    }
                }
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return out.toString()
    }

    private fun findClosingBrace(glob: String, open: Int): Int {
        var depth = 0
        var j = open
        while (j < glob.length) {
            when (glob[j]) {
                '\\' -> j++ // skip escaped char
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
            j++
        }
        return -1
    }

    private fun splitBraceOptions(content: String): List<String> {
        val options = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var j = 0
        while (j < content.length) {
            val c = content[j]
            when {
                c == '\\' && j + 1 < content.length -> {
                    current.append(c).append(content[j + 1])
                    j++
                }
                c == '{' -> {
                    depth++
                    current.append(c)
                }
                c == '}' -> {
                    depth--
                    current.append(c)
                }
                c == ',' && depth == 0 -> {
                    options += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            j++
        }
        options += current.toString()
        return options
    }

    private fun globRegex(
        condition: FilterCondition,
        precompiled: Regex?,
        hasPrecompiled: Boolean,
    ): Regex? = if (hasPrecompiled) precompiled else compileGlob(condition.pattern)
}

/**
 * Walks [expression] and compiles the regex of every condition once, so sync
 * evaluation never pays regex-construction cost per article. Conditions that
 * need no regex map to null; invalid patterns map to null and never match.
 */
private fun compileRegexes(expression: FilterExpression): Map<FilterCondition, Regex?> {
    val conditions = mutableListOf<FilterCondition>()
    fun collect(e: FilterExpression) {
        when (e) {
            is FilterExpression.Condition -> conditions += e.condition
            is FilterExpression.AllOf -> e.children.forEach(::collect)
            is FilterExpression.AnyOf -> e.children.forEach(::collect)
            is FilterExpression.NoneOf -> e.children.forEach(::collect)
        }
    }
    collect(expression)
    return conditions.distinct().associateWith { condition ->
        when (condition.matchType) {
            FilterMatchType.GLOB, FilterMatchType.NOT_GLOB ->
                ArticleFilterEngine.compileGlob(condition.pattern)
            FilterMatchType.WORD_MATCH ->
                ArticleFilterEngine.compileWordRegex(condition.pattern)
            FilterMatchType.REGEX, FilterMatchType.NOT_REGEX ->
                ArticleFilterEngine.compileUserRegex(condition.pattern)
            FilterMatchType.CONTAINS, FilterMatchType.NOT_CONTAINS -> null
        }
    }
}
