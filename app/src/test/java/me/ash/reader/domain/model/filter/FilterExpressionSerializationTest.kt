package me.ash.reader.domain.model.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class FilterExpressionSerializationTest {

    private val condition =
        FilterExpression.Condition(
            FilterCondition(
                field = FilterField.TITLE,
                matchType = FilterMatchType.CONTAINS,
                pattern = "kotlin",
            )
        )

    @Test
    fun `leaf round-trips`() {
        assertEquals(condition, condition.toJson().toFilterExpressionOrNull())
    }

    @Test
    fun `nested groups round-trip`() {
        val expr =
            FilterExpression.AllOf(
                listOf(
                    condition,
                    FilterExpression.AnyOf(
                        listOf(
                            FilterExpression.Condition(
                                condition = FilterCondition(
                                    field = FilterField.AUTHOR,
                                    matchType = FilterMatchType.REGEX,
                                    pattern = "(?i)john|jane",
                                )
                            )
                        )
                    ),
                    FilterExpression.NoneOf(
                        listOf(
                            FilterExpression.Condition(
                                condition = FilterCondition(
                                    field = FilterField.URL,
                                    matchType = FilterMatchType.NOT_CONTAINS,
                                    pattern = "ads.example.com",
                                )
                            )
                        )
                    ),
                )
            )
        assertEquals(expr, expr.toJson().toFilterExpressionOrNull())
    }

    @Test
    fun `simple mode ORs conditions for BLOCK and ANDs for ALLOW`() {
        val conditions =
            listOf(
                FilterCondition(
                    field = FilterField.TITLE,
                    matchType = FilterMatchType.CONTAINS,
                    pattern = "a",
                ),
                FilterCondition(
                    field = FilterField.TITLE,
                    matchType = FilterMatchType.CONTAINS,
                    pattern = "b",
                ),
            )

        val block = FilterExpression.simple(conditions, FilterAction.BLOCK)
        assertTrue(block is FilterExpression.AnyOf)

        val allow = FilterExpression.simple(conditions, FilterAction.ALLOW)
        assertTrue(allow is FilterExpression.AllOf)

        // Single condition stays a leaf regardless of action.
        val single = FilterExpression.simple(conditions.take(1), FilterAction.BLOCK)
        assertTrue(single is FilterExpression.Condition)
    }

    @Test
    fun `empty simple expression is null`() {
        assertNull(FilterExpression.simple(emptyList(), FilterAction.BLOCK))
    }

    @Test
    fun `corrupt json degrades to null instead of throwing`() {
        assertNull("not json at all".toFilterExpressionOrNull())
        assertNull("{\"type\": \"nope\"}".toFilterExpressionOrNull())
        assertNull("".toFilterExpressionOrNull())
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val futureJson =
            """
            {"type":"leaf","condition":{
                "field":"TITLE","matchType":"CONTAINS","pattern":"x",
                "someFutureField":42
            }}
            """.trimIndent()
        val decoded = futureJson.toFilterExpressionOrNull()
        assertEquals(condition.copy(condition = condition.condition.copy(pattern = "x")), decoded)
    }

    @Test
    fun `unknown enum values degrade to null safely`() {
        val badEnum =
            """
            {"type":"leaf","condition":{
                "field":"NOT_A_FIELD","matchType":"CONTAINS","pattern":"x"
            }}
            """.trimIndent()
        assertNull(badEnum.toFilterExpressionOrNull())
    }
}
