package me.ash.reader.domain.service

import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterCondition
import me.ash.reader.domain.model.filter.FilterExpression
import me.ash.reader.domain.model.filter.FilterField
import me.ash.reader.domain.model.filter.FilterMatchType

/**
 * A [FilterRule] with its expression parsed and ready for evaluation.
 *
 * Compiling once per sync batch (instead of per article) keeps regex
 * construction cost off the hot path; invalid regexes degrade to a rule that
 * never matches so a bad pattern can never crash sync.
 */
data class CompiledFilterRule(
    val id: String,
    val action: FilterAction,
    val expression: FilterExpression,
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
        var hasAllow = false
        for (rule in rules) {
            when (rule.action) {
                FilterAction.BLOCK -> {
                    if (matches(article, rule.expression)) return false
                }
                FilterAction.ALLOW -> {
                    hasAllow = true
                    if (matches(article, rule.expression)) return true
                }
            }
        }
        // Block wins over allow: reaching here means nothing blocked us, and
        // every ALLOW rule (if any) failed to match.
        return !hasAllow
    }

    /**
     * Evaluates a single condition against the snapshot's target field.
     * Exposed internally for tests and the UI's live "test your pattern" box.
     */
    fun matchesCondition(
        article: ArticleSnapshot,
        condition: FilterCondition,
    ): Boolean {
        if (condition.pattern.length > MAX_PATTERN_LENGTH) return false
        val value = valueFor(article, condition.field)
        return try {
            when (condition.matchType) {
                FilterMatchType.CONTAINS ->
                    value.contains(condition.pattern, ignoreCase = true)
                FilterMatchType.NOT_CONTAINS ->
                    !value.contains(condition.pattern, ignoreCase = true)
                FilterMatchType.WORD_MATCH ->
                    Regex("\\b${Regex.escape(condition.pattern)}\\b", REGEX_OPTIONS)
                        .containsMatchIn(value)
                FilterMatchType.REGEX ->
                    Regex(condition.pattern, REGEX_OPTIONS).containsMatchIn(value)
                FilterMatchType.NOT_REGEX ->
                    !Regex(condition.pattern, REGEX_OPTIONS).containsMatchIn(value)
            }
        } catch (_: Exception) {
            // Invalid regex or unexpected failure: never crash sync, treat as no-match.
            false
        }
    }

    private fun matches(
        article: ArticleSnapshot,
        expression: FilterExpression,
    ): Boolean =
        when (expression) {
            is FilterExpression.Condition -> matchesCondition(article, expression.condition)
            is FilterExpression.AllOf -> expression.children.all { matches(article, it) }
            is FilterExpression.AnyOf -> expression.children.any { matches(article, it) }
            is FilterExpression.NoneOf -> expression.children.none { matches(article, it) }
        }

    private fun valueFor(article: ArticleSnapshot, field: FilterField): String =
        when (field) {
            FilterField.TITLE -> article.title
            FilterField.AUTHOR -> article.author.orEmpty()
            FilterField.URL -> article.link
            FilterField.CONTENT -> article.content
        }

    private val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE)
}
