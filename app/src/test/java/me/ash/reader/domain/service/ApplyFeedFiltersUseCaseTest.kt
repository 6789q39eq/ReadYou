package me.ash.reader.domain.service

import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterCondition
import me.ash.reader.domain.model.filter.FilterExpression
import me.ash.reader.domain.model.filter.FilterField
import me.ash.reader.domain.model.filter.FilterMatchType
import me.ash.reader.domain.model.filter.FilterRule
import me.ash.reader.domain.model.filter.toJson
import me.ash.reader.domain.repository.FilterRuleDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any

@RunWith(MockitoJUnitRunner::class)
class ApplyFeedFiltersUseCaseTest {

    private val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    private val dao = Mockito.mock(FilterRuleDao::class.java)
    private val useCase = ApplyFeedFiltersUseCase(dao, testDispatcher)

    private fun article(title: String, id: String = title) =
        Article(
            id = id,
            date = java.util.Date(0),
            title = title,
            author = null,
            rawDescription = "",
            shortDescription = "content of $title",
            link = "https://example.com/$id",
            feedId = "feed-1",
            accountId = 1,
        )

    private fun rule(action: FilterAction, pattern: String, feedId: String? = null): FilterRule {
        val expression =
            FilterExpression.Condition(
                FilterCondition(FilterField.TITLE, FilterMatchType.CONTAINS, pattern)
            )
        return FilterRule(
            id = "${action.name}-$pattern",
            accountId = 1,
            feedId = feedId,
            name = "${action.name} $pattern",
            action = action,
            expressionJson = expression.toJson(),
            createdAt = 0,
        )
    }

    @Test
    fun `no rules is a passthrough`() = runBlocking {
        Mockito.`when`(dao.findEnabledForAccountAndFeed(any(), any())).thenReturn(emptyList())
        val input = listOf(article("a"), article("b"))
        assertEquals(input, useCase(1, "feed-1", input))
    }

    @Test
    fun `block rule drops matching articles`() = runBlocking {
        Mockito.`when`(dao.findEnabledForAccountAndFeed(any(), any()))
            .thenReturn(listOf(rule(FilterAction.BLOCK, "spam")))
        val result =
            useCase(1, "feed-1", listOf(article("buy spam now"), article("real news")))
        assertEquals(listOf("real news"), result.map { it.title })
    }

    @Test
    fun `allow rule keeps only matches`() = runBlocking {
        Mockito.`when`(dao.findEnabledForAccountAndFeed(any(), any()))
            .thenReturn(listOf(rule(FilterAction.ALLOW, "kotlin")))
        val result =
            useCase(1, "feed-1", listOf(article("kotlin tips"), article("java tips")))
        assertEquals(listOf("kotlin tips"), result.map { it.title })
    }

    @Test
    fun `corrupt expression json degrades to passthrough`() = runBlocking {
        val bad =
            FilterRule(
                id = "bad",
                accountId = 1,
                feedId = null,
                name = "bad",
                action = FilterAction.BLOCK,
                expressionJson = "{not json",
                createdAt = 0,
            )
        Mockito.`when`(dao.findEnabledForAccountAndFeed(any(), any()))
            .thenReturn(listOf(bad))
        val input = listOf(article("anything"))
        assertEquals(input, useCase(1, "feed-1", input))
    }

    @Test
    fun `dao failure keeps all articles`() = runBlocking {
        Mockito.`when`(dao.findEnabledForAccountAndFeed(any(), any()))
            .thenThrow(RuntimeException("db"))
        val input = listOf(article("keep me"))
        assertEquals(input, useCase(1, "feed-1", input))
    }

    @Test
    fun `partition returns kept and dropped separately`() = runBlocking {
        Mockito.`when`(dao.findEnabledForAccountAndFeed(any(), any()))
            .thenReturn(listOf(rule(FilterAction.BLOCK, "spam")))
        val input = listOf(article("buy spam now"), article("real news"))
        val (kept, dropped) = useCase.partition(1, "feed-1", input)
        assertEquals(listOf("real news"), kept.map { it.title })
        assertEquals(listOf("buy spam now"), dropped.map { it.title })
    }

    @Test
    fun `filterMixedFeeds applies per-feed rules and preserves order`() = runBlocking {
        Mockito.`when`(dao.findEnabledForAccountAndFeed(any(), any()))
            .thenAnswer { invocation ->
                val feedId = invocation.getArgument(1, String::class.java)
                if (feedId == "feed-1") listOf(rule(FilterAction.BLOCK, "spam")) else emptyList()
            }
        fun feedArticle(title: String, feedId: String) = article(title).copy(feedId = feedId)
        val input =
            listOf(
                feedArticle("real news", "feed-1"),
                feedArticle("buy spam now", "feed-1"),
                feedArticle("buy spam now", "feed-2"),
            )
        val result = useCase.filterMixedFeeds(1, input)
        assertEquals(
            listOf("real news" to "feed-1", "buy spam now" to "feed-2"),
            result.map { it.title to it.feedId },
        )
    }

    @Test
    fun `pathological regex times out and article is kept`() = runBlocking {
        // Construct a pattern that catastrophically backtracks on a long title.
        val slowPattern = "a".repeat(30) + "!"
        val pattern = "(a+)+b"
        val article =
            article(
                title = slowPattern,
                id = "slow",
            )
        val slowRule =
            FilterRule(
                id = "slow",
                accountId = 1,
                feedId = null,
                name = "slow",
                action = FilterAction.BLOCK,
                expressionJson =
                    FilterExpression.Condition(
                        FilterCondition(
                            field = FilterField.TITLE,
                            matchType = FilterMatchType.REGEX,
                            pattern = pattern,
                        )
                    ).toJson(),
                createdAt = 0,
            )
        Mockito.`when`(dao.findEnabledForAccountAndFeed(any(), any()))
            .thenReturn(listOf(slowRule))
        val start = System.currentTimeMillis()
        val result = useCase(1, "feed-1", listOf(article))
        val elapsed = System.currentTimeMillis() - start
        // The article must be kept (timeout treated as "no match") and the
        // elapsed time must be bounded by the per-article budget plus a small
        // fudge factor for coroutine scheduling.
        assertEquals(listOf(article), result)
        assertTrue(
            "expected elapsed < ${ApplyFeedFiltersUseCase.PER_ARTICLE_TIMEOUT_MS * 10}ms but was ${elapsed}ms",
            elapsed < ApplyFeedFiltersUseCase.PER_ARTICLE_TIMEOUT_MS * 10,
        )
    }
}
