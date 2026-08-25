package me.ash.reader.domain.service

import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterCondition
import me.ash.reader.domain.model.filter.FilterExpression
import me.ash.reader.domain.model.filter.FilterField
import me.ash.reader.domain.model.filter.FilterMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class ArticleFilterEngineTest {

    private val article =
        ArticleSnapshot(
            title = "Kotlin 2.0 Released Today",
            author = "Jane Doe",
            link = "https://example.com/blog/kotlin-2-release",
            content = "The Kotlin team announced the stable release of Kotlin 2.0 with K2 compiler.",
        )

    private fun rule(
        action: FilterAction,
        expression: FilterExpression,
        id: String = "r",
    ) = CompiledFilterRule(id = id, action = action, expression = expression)

    private fun cond(
        field: FilterField,
        matchType: FilterMatchType,
        pattern: String,
    ) = FilterExpression.Condition(FilterCondition(field, matchType, pattern))

    // --- boolean semantics -------------------------------------------------

    @Test
    fun `no rules keeps everything`() {
        assertTrue(ArticleFilterEngine.shouldKeep(article, emptyList()))
    }

    @Test
    fun `matching block rule drops article`() {
        val rules =
            listOf(
                rule(
                    FilterAction.BLOCK,
                    cond(FilterField.TITLE, FilterMatchType.CONTAINS, "released"),
                )
            )
        assertFalse(ArticleFilterEngine.shouldKeep(article, rules))
    }

    @Test
    fun `non-matching block rule keeps article`() {
        val rules =
            listOf(
                rule(
                    FilterAction.BLOCK,
                    cond(FilterField.TITLE, FilterMatchType.CONTAINS, "rust"),
                )
            )
        assertTrue(ArticleFilterEngine.shouldKeep(article, rules))
    }

    @Test
    fun `allow rule without match drops when allow exists`() {
        val rules =
            listOf(rule(FilterAction.ALLOW, cond(FilterField.TITLE, FilterMatchType.CONTAINS, "rust")))
        assertFalse(ArticleFilterEngine.shouldKeep(article, rules))
    }

    @Test
    fun `allow rule with match keeps article`() {
        val rules =
            listOf(
                rule(FilterAction.ALLOW, cond(FilterField.TITLE, FilterMatchType.CONTAINS, "kotlin"))
            )
        assertTrue(ArticleFilterEngine.shouldKeep(article, rules))
    }

    @Test
    fun `block wins over allow even when allow matches`() {
        val rules =
            listOf(
                rule(
                    FilterAction.BLOCK,
                    cond(FilterField.TITLE, FilterMatchType.CONTAINS, "released"),
                    id = "block",
                ),
                rule(
                    FilterAction.ALLOW,
                    cond(FilterField.TITLE, FilterMatchType.CONTAINS, "kotlin"),
                    id = "allow",
                ),
            )
        assertFalse(ArticleFilterEngine.shouldKeep(article, rules))
    }

    // --- fields ------------------------------------------------------------

    @Test
    fun `matches author field`() {
        val rules =
            listOf(
                rule(FilterAction.BLOCK, cond(FilterField.AUTHOR, FilterMatchType.CONTAINS, "jane"))
            )
        assertFalse(ArticleFilterEngine.shouldKeep(article, rules))
    }

    @Test
    fun `matches url field`() {
        val rules =
            listOf(
                rule(FilterAction.BLOCK, cond(FilterField.URL, FilterMatchType.CONTAINS, "/blog/"))
            )
        assertFalse(ArticleFilterEngine.shouldKeep(article, rules))
    }

    @Test
    fun `matches content field`() {
        val rules =
            listOf(
                rule(FilterAction.BLOCK, cond(FilterField.CONTENT, FilterMatchType.CONTAINS, "K2 compiler"))
            )
        assertFalse(ArticleFilterEngine.shouldKeep(article, rules))
    }

    @Test
    fun `null author treated as empty and never matches`() {
        val noAuthor = article.copy(author = null)
        val rules =
            listOf(
                rule(FilterAction.BLOCK, cond(FilterField.AUTHOR, FilterMatchType.CONTAINS, "jane"))
            )
        assertTrue(ArticleFilterEngine.shouldKeep(noAuthor, rules))
    }

    // --- match types ---------------------------------------------------------

    @Test
    fun `contains is case-insensitive substring`() {
        val c = FilterCondition(FilterField.TITLE, FilterMatchType.CONTAINS, "KOTLIN")
        assertTrue(ArticleFilterEngine.matchesCondition(article, c))
    }

    @Test
    fun `not_contains inverts contains`() {
        val hit = FilterCondition(FilterField.TITLE, FilterMatchType.NOT_CONTAINS, "kotlin")
        val miss = FilterCondition(FilterField.TITLE, FilterMatchType.NOT_CONTAINS, "rust")
        assertFalse(ArticleFilterEngine.matchesCondition(article, hit))
        assertTrue(ArticleFilterEngine.matchesCondition(article, miss))
    }

    @Test
    fun `word_match respects word boundaries`() {
        val whole = FilterCondition(FilterField.CONTENT, FilterMatchType.WORD_MATCH, "stable")
        val partial = FilterCondition(FilterField.CONTENT, FilterMatchType.WORD_MATCH, "stab")
        assertTrue(ArticleFilterEngine.matchesCondition(article, whole))
        assertFalse(ArticleFilterEngine.matchesCondition(article, partial))
    }

    @Test
    fun `regex matches with alternation`() {
        val c = FilterCondition(FilterField.TITLE, FilterMatchType.REGEX, "kotlin|swift")
        assertTrue(ArticleFilterEngine.matchesCondition(article, c))
    }

    @Test
    fun `not_regex inverts regex`() {
        val miss = FilterCondition(FilterField.TITLE, FilterMatchType.NOT_REGEX, "^kotlin")
        val hit = FilterCondition(FilterField.TITLE, FilterMatchType.NOT_REGEX, "rust")
        assertFalse(ArticleFilterEngine.matchesCondition(article, miss))
        assertTrue(ArticleFilterEngine.matchesCondition(article, hit))
    }

    // --- resilience ----------------------------------------------------------

    @Test
    fun `invalid regex never throws and does not match`() {
        val bad = FilterCondition(FilterField.TITLE, FilterMatchType.REGEX, "[unclosed")
        assertFalse(ArticleFilterEngine.matchesCondition(article, bad))

        val rules = listOf(rule(FilterAction.BLOCK, FilterExpression.Condition(bad)))
        assertTrue(ArticleFilterEngine.shouldKeep(article, rules))
    }

    @Test
    fun `oversized pattern is rejected safely`() {
        val huge = FilterCondition(FilterField.TITLE, FilterMatchType.CONTAINS, "a".repeat(501))
        assertFalse(ArticleFilterEngine.matchesCondition(article, huge))
        val atLimit = FilterCondition(FilterField.TITLE, FilterMatchType.CONTAINS, "a".repeat(500))
        assertEquals(false, ArticleFilterEngine.matchesCondition(article, atLimit))
    }

    // --- groups ----------------------------------------------------------------

    @Test
    fun `all_of requires every child to match`() {
        val expr =
            FilterExpression.AllOf(
                listOf(
                    cond(FilterField.TITLE, FilterMatchType.CONTAINS, "kotlin"),
                    cond(FilterField.AUTHOR, FilterMatchType.CONTAINS, "nobody"),
                )
            )
        val rules = listOf(rule(FilterAction.BLOCK, expr))
        assertTrue(ArticleFilterEngine.shouldKeep(article, rules))
    }

    @Test
    fun `any_of needs one child to match`() {
        val expr =
            FilterExpression.AnyOf(
                listOf(
                    cond(FilterField.TITLE, FilterMatchType.CONTAINS, "rust"),
                    cond(FilterField.AUTHOR, FilterMatchType.CONTAINS, "jane"),
                )
            )
        val rules = listOf(rule(FilterAction.BLOCK, expr))
        assertFalse(ArticleFilterEngine.shouldKeep(article, rules))
    }

    @Test
    fun `none_of is group negation`() {
        val expr =
            FilterExpression.NoneOf(
                listOf(cond(FilterField.TITLE, FilterMatchType.CONTAINS, "rust"))
            )
        val rules = listOf(rule(FilterAction.ALLOW, expr))
        assertTrue(ArticleFilterEngine.shouldKeep(article, rules))

        // As a BLOCK rule, NoneOf drops articles matching *none* of its children:
        // this article has no "rust", so it is dropped.
        val blockingExpr =
            FilterExpression.NoneOf(
                listOf(cond(FilterField.TITLE, FilterMatchType.CONTAINS, "rust"))
            )
        val blockingRules = listOf(rule(FilterAction.BLOCK, blockingExpr))
        assertFalse(ArticleFilterEngine.shouldKeep(article, blockingRules))
    }

    @Test
    fun `deep nested groups evaluate correctly`() {
        val expr =
            FilterExpression.AllOf(
                listOf(
                    FilterExpression.AnyOf(
                        listOf(
                            cond(FilterField.TITLE, FilterMatchType.CONTAINS, "rust"),
                            FilterExpression.NoneOf(
                                listOf(
                                    cond(FilterField.URL, FilterMatchType.CONTAINS, "spam.example")
                                )
                            ),
                        )
                    ),
                    cond(FilterField.AUTHOR, FilterMatchType.REGEX, "jane\\s+doe"),
                )
            )
        val rules = listOf(rule(FilterAction.BLOCK, expr))
        assertFalse(ArticleFilterEngine.shouldKeep(article, rules))
    }
}
