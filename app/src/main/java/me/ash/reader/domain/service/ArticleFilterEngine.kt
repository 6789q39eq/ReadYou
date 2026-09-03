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
        val value = valueFor(article, condition.field)
        return try {
            when (condition.matchType) {
                FilterMatchType.CONTAINS ->
                    value.contains(condition.pattern, ignoreCase = true)
                FilterMatchType.NOT_CONTAINS ->
                    !value.contains(condition.pattern, ignoreCase = true)
                FilterMatchType.WORD_MATCH -> {
                    val regex =
                        if (hasPrecompiled) precompiled
                        else compileWordRegex(condition.pattern)
                    regex?.containsMatchIn(value) == true
                }
                FilterMatchType.REGEX -> {
                    val regex =
                        if (hasPrecompiled) precompiled
                        else compileUserRegex(condition.pattern)
                    regex?.containsMatchIn(value) == true
                }
                FilterMatchType.NOT_REGEX -> {
                    val regex =
                        if (hasPrecompiled) precompiled
                        else compileUserRegex(condition.pattern)
                    // Invalid regex degrades to no-match, so NOT_REGEX with a
                    // bad pattern is false (nothing is excluded).
                    regex != null && !regex.containsMatchIn(value)
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
        }

    private val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE)

    /**
     * Unicode-aware word boundary: ASCII `\b` never matches inside CJK text,
     * so whole-word matching uses lookarounds over Unicode letters, digits
     * and underscore instead.
     */
    internal fun compileWordRegex(pattern: String): Regex? =
        if (pattern.length > MAX_PATTERN_LENGTH) {
            null
        } else {
            runCatching {
                Regex(
                    "(?<![\\p{L}\\p{Nd}_])${Regex.escape(pattern)}(?![\\p{L}\\p{Nd}_])",
                    REGEX_OPTIONS,
                )
            }.getOrNull()
        }

    internal fun compileUserRegex(pattern: String): Regex? =
        if (pattern.length > MAX_PATTERN_LENGTH) {
            null
        } else {
            runCatching { Regex(pattern, REGEX_OPTIONS) }.getOrNull()
        }
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
            FilterMatchType.WORD_MATCH ->
                ArticleFilterEngine.compileWordRegex(condition.pattern)
            FilterMatchType.REGEX, FilterMatchType.NOT_REGEX ->
                ArticleFilterEngine.compileUserRegex(condition.pattern)
            FilterMatchType.CONTAINS, FilterMatchType.NOT_CONTAINS -> null
        }
    }
}
