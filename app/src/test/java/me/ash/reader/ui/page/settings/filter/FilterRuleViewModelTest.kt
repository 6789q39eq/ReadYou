package me.ash.reader.ui.page.settings.filter

import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterCondition
import me.ash.reader.domain.model.filter.FilterExpression
import me.ash.reader.domain.model.filter.FilterField
import me.ash.reader.domain.model.filter.FilterMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the simple-mode flatten/advanced-detection logic in
 * [FilterRuleViewModel]. The ViewModel itself is not instantiated here — it
 * requires a Hilt-injected [me.ash.reader.domain.repository.FilterRuleDao].
 * These tests cover the two pure helpers that decide whether an edit would
 * silently change rule semantics.
 */
class FilterRuleViewModelTest {

    private fun leaf(pattern: String) =
        FilterExpression.Condition(
            FilterCondition(FilterField.TITLE, FilterMatchType.CONTAINS, pattern)
        )

    @Test
    fun `single leaf expression is flat`() {
        assertTrue(leaf("kotlin").isFlatConditionList())
    }

    @Test
    fun `all of leaves is flat`() {
        val expr =
            FilterExpression.AllOf(listOf(leaf("a"), leaf("b"), leaf("c")))
        assertTrue(expr.isFlatConditionList())
    }

    @Test
    fun `any of leaves is flat`() {
        val expr =
            FilterExpression.AnyOf(listOf(leaf("a"), leaf("b")))
        assertTrue(expr.isFlatConditionList())
    }

    @Test
    fun `none of leaves is flat`() {
        val expr =
            FilterExpression.NoneOf(listOf(leaf("a")))
        assertTrue(expr.isFlatConditionList())
    }

    @Test
    fun `nested groups are not flat`() {
        val expr =
            FilterExpression.AllOf(
                listOf(
                    leaf("title contains kotlin"),
                    FilterExpression.AnyOf(
                        listOf(
                            leaf("author contains a"),
                            leaf("author contains b"),
                        )
                    ),
                )
            )
        assertFalse(expr.isFlatConditionList())
    }

    @Test
    fun `flatten loses grouping`() {
        val expr =
            FilterExpression.AnyOf(
                listOf(
                    leaf("a"),
                    leaf("b"),
                )
            )
        val flat = expr.flattenToConditions()
        assertEquals(2, flat.size)
        // The two leaves are flattened, but the OR/AND distinction is gone —
        // that's the whole point of the advanced-rule warning.
        assertEquals("a", flat[0].pattern)
        assertEquals("b", flat[1].pattern)
    }

    @Test
    fun `block simple mode ORs multiple conditions`() {
        val conditions =
            listOf(
                FilterCondition(FilterField.TITLE, FilterMatchType.CONTAINS, "a"),
                FilterCondition(FilterField.TITLE, FilterMatchType.CONTAINS, "b"),
            )
        val expr = FilterExpression.simple(conditions, FilterAction.BLOCK)
        assertTrue("BLOCK should OR conditions", expr is FilterExpression.AnyOf)
    }

    @Test
    fun `allow simple mode ANDs multiple conditions`() {
        val conditions =
            listOf(
                FilterCondition(FilterField.TITLE, FilterMatchType.CONTAINS, "a"),
                FilterCondition(FilterField.TITLE, FilterMatchType.CONTAINS, "b"),
            )
        val expr = FilterExpression.simple(conditions, FilterAction.ALLOW)
        assertTrue("ALLOW should AND conditions", expr is FilterExpression.AllOf)
    }
}
