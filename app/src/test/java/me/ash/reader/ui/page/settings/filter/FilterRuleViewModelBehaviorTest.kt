package me.ash.reader.ui.page.settings.filter

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterCondition
import me.ash.reader.domain.model.filter.FilterExpression
import me.ash.reader.domain.model.filter.FilterField
import me.ash.reader.domain.model.filter.FilterMatchType
import me.ash.reader.domain.model.filter.FilterRule
import me.ash.reader.domain.model.filter.toFilterExpressionOrNull
import me.ash.reader.domain.model.filter.toJson
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.FilterRuleDao
import me.ash.reader.domain.service.AccountService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Behavioural tests for [FilterRuleViewModel] — the ViewModel that backs the
 * filter rule list and editor. These are the regression guards for the
 * Settings → Filter rules flow:
 *
 * - [startEditing] resets / loads the editor state without races;
 * - [save] only persists complete rules and the persisted expression matches
 *   the simple-mode semantics (BLOCK ⇒ OR, ALLOW ⇒ AND);
 * - [setEnabled] and [delete] are forwarded to the DAO with the right
 *   arguments.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilterRuleViewModelBehaviorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val accountId = 7
    private lateinit var dao: FilterRuleDao
    private lateinit var feedDao: FeedDao
    private lateinit var accountService: AccountService
    private val viewModels = mutableListOf<FilterRuleViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dao = Mockito.mock(FilterRuleDao::class.java)
        feedDao = Mockito.mock(FeedDao::class.java)
        accountService = Mockito.mock(AccountService::class.java)
        Mockito.`when`(accountService.getCurrentAccountId()).thenReturn(accountId)
    }

    @After
    fun tearDown() {
        // Cancel any view-models created during the test so their background
        // coroutines don't leak into the next test's @Before.
        viewModels.forEach { it.clearPendingWork() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): FilterRuleViewModel {
        val vm = FilterRuleViewModel(dao, feedDao, accountService, testDispatcher)
        viewModels.add(vm)
        return vm
    }

    /** Cancel the ViewModel's [viewModelScope] so background coroutines don't
     *  leak into the next test. `onCleared` is protected, so use reflection. */
    private fun FilterRuleViewModel.clearPendingWork() {
        val onCleared = ViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(this)
    }

    private fun existingRule(
        id: String = "r1",
        name: String = "existing",
        action: FilterAction = FilterAction.BLOCK,
        pattern: String = "spam",
        isEnabled: Boolean = true,
        feedId: String? = null,
    ): FilterRule {
        val expression =
            FilterExpression.Condition(
                FilterCondition(FilterField.TITLE, FilterMatchType.GLOB, pattern)
            )
        return FilterRule(
            id = id,
            accountId = accountId,
            feedId = feedId,
            name = name,
            isEnabled = isEnabled,
            action = action,
            expressionJson = expression.toJson(),
            createdAt = 1_700_000_000L,
        )
    }

    @Test
    fun `startEditing null resets to a single empty default condition`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        viewModel.startEditing(null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("editingRuleId must be null for new rules", state.editingRuleId)
        assertEquals(FilterAction.BLOCK, state.action)
        assertEquals(1, state.conditions.size)
        val condition = state.conditions.single()
        assertEquals(FilterField.ALL, condition.field)
        assertEquals(FilterMatchType.GLOB, condition.matchType)
        assertEquals("", condition.pattern)
        assertTrue("new rules must start enabled", state.editingIsEnabled)
    }

    @Test
    fun `startEditing with existing id loads the rule into the editor state`() =
        runTest(testDispatcher) {
            val stored = existingRule(name = "Block spam", pattern = "spam")
            Mockito.`when`(dao.findById(stored.id)).thenReturn(stored)

            val viewModel = newViewModel()
            viewModel.startEditing(stored.id)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(stored.id, state.editingRuleId)
            assertEquals("Block spam", state.name)
            assertEquals(FilterAction.BLOCK, state.action)
            assertEquals(1, state.conditions.size)
            assertEquals("spam", state.conditions.single().pattern)
            assertFalse("flat single-leaf rules are not advanced", state.isAdvancedRule)
        }

    @Test
    fun `startEditing with missing id clears the editor state`() = runTest(testDispatcher) {
        Mockito.`when`(dao.findById("ghost")).thenReturn(null)

        val viewModel = newViewModel()
        viewModel.startEditing("ghost")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.editingRuleId)
    }

    @Test
    fun `second startEditing cancels the first load`() = runTest(testDispatcher) {
        val first = existingRule(id = "first", name = "first", pattern = "a")
        val second = existingRule(id = "second", name = "second", pattern = "b")
        Mockito.`when`(dao.findById("first")).thenReturn(first)
        Mockito.`when`(dao.findById("second")).thenReturn(second)

        val viewModel = newViewModel()
        viewModel.startEditing("first")
        // Don't advance: immediately start editing the second rule. The first
        // job must be cancelled so its result doesn't clobber the second.
        viewModel.startEditing("second")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("second", state.editingRuleId)
        assertEquals("second", state.name)
        assertEquals("b", state.conditions.single().pattern)
    }

    @Test
    fun `updateName, updateAction and addCondition mutate the state`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            viewModel.startEditing(null)
            advanceUntilIdle()

            viewModel.updateName("My rule")
            viewModel.updateAction(FilterAction.ALLOW)
            viewModel.updateCondition(0, EditableCondition(pattern = "kotlin"))
            viewModel.addCondition()

            val state = viewModel.uiState.value
            assertEquals("My rule", state.name)
            assertEquals(FilterAction.ALLOW, state.action)
            assertEquals(2, state.conditions.size)
            assertEquals("kotlin", state.conditions[0].pattern)
            assertEquals("", state.conditions[1].pattern)
        }

    @Test
    fun `removeCondition refuses to drop the last condition`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        viewModel.startEditing(null)
        advanceUntilIdle()

        viewModel.removeCondition(0)
        assertEquals(1, viewModel.uiState.value.conditions.size)
    }

    @Test
    fun `removeCondition drops a non-last condition`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        viewModel.startEditing(null)
        advanceUntilIdle()
        viewModel.updateCondition(0, EditableCondition(pattern = "keep"))
        viewModel.addCondition()
        viewModel.updateCondition(1, EditableCondition(pattern = "drop"))
        viewModel.removeCondition(1)

        val remaining = viewModel.uiState.value.conditions
        assertEquals(1, remaining.size)
        assertEquals("keep", remaining.single().pattern)
    }

    @Test
    fun `save with empty name returns false and does not touch the dao`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            viewModel.startEditing(null)
            advanceUntilIdle()
            viewModel.updateCondition(0, EditableCondition(pattern = "anything"))

            val saved = viewModel.save()
            advanceUntilIdle()

            assertFalse(saved)
            verify(dao, never()).upsert()
        }

    @Test
    fun `save with empty pattern returns false and does not touch the dao`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            viewModel.startEditing(null)
            advanceUntilIdle()
            viewModel.updateName("Has name")

            val saved = viewModel.save()
            advanceUntilIdle()

            assertFalse(saved)
            verify(dao, never()).upsert()
        }

    @Test
    fun `save persists a new BLOCK rule with OR of conditions`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            val saved = ArrayList<FilterRule>()
            // upsert is a vararg suspend method; capture rules via
            // thenAnswer wrapped in runBlocking (required for suspend stubbing).
            kotlinx.coroutines.runBlocking {
                Mockito.`when`(dao.upsert(*arrayOf(Mockito.any<FilterRule>())))
                    .thenAnswer { invocation ->
                        @Suppress("UNCHECKED_CAST")
                        val rules = invocation.arguments[0] as Array<out FilterRule>
                        saved.addAll(rules)
                        null
                    }
            }

            viewModel.startEditing(null)
            advanceUntilIdle()
            viewModel.updateName("Block spam")
            viewModel.updateAction(FilterAction.BLOCK)
            viewModel.updateCondition(0, EditableCondition(pattern = "spam"))
            viewModel.addCondition()
            viewModel.updateCondition(
                1,
                EditableCondition(field = FilterField.AUTHOR, pattern = "evil"),
            )

            assertTrue(viewModel.save())
            advanceUntilIdle()

            assertEquals(1, saved.size)
            val persisted = saved.single()
            assertEquals(accountId, persisted.accountId)
            assertNull("global rule has no feed", persisted.feedId)
            assertEquals("Block spam", persisted.name)
            assertEquals(FilterAction.BLOCK, persisted.action)
            assertTrue("new rules must be enabled", persisted.isEnabled)

            val expr = persisted.expressionJson.toFilterExpressionOrNull()
            assertNotNull("expression must round-trip through JSON", expr)
            assertTrue("BLOCK simple mode must OR conditions", expr is FilterExpression.AnyOf)
        }

    @Test
    fun `save persists a new ALLOW rule with AND of conditions`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            val saved = ArrayList<FilterRule>()
            kotlinx.coroutines.runBlocking {
                Mockito.`when`(dao.upsert(*arrayOf(Mockito.any<FilterRule>())))
                    .thenAnswer { invocation ->
                        @Suppress("UNCHECKED_CAST")
                        val rules = invocation.arguments[0] as Array<out FilterRule>
                        saved.addAll(rules)
                        null
                    }
            }

            viewModel.startEditing(null)
            advanceUntilIdle()
            viewModel.updateName("Allow kotlin")
            viewModel.updateAction(FilterAction.ALLOW)
            viewModel.updateCondition(0, EditableCondition(pattern = "kotlin"))
            viewModel.addCondition()
            viewModel.updateCondition(1, EditableCondition(pattern = "compose"))

            assertTrue(viewModel.save())
            advanceUntilIdle()

            assertEquals(1, saved.size)
            // With 2+ conditions, BLOCK ⇒ AnyOf and ALLOW ⇒ AllOf. A single
            // leaf falls through as a bare Condition, which is why this test
            // uses two patterns.
            val expr = saved.single().expressionJson.toFilterExpressionOrNull()
            assertTrue("ALLOW simple mode must AND conditions", expr is FilterExpression.AllOf)
        }

    @Test
    fun `save preserves rule id, feedId and isEnabled when editing an existing rule`() =
        runTest(testDispatcher) {
            val stored = existingRule(
                id = "rule-42",
                name = "old name",
                action = FilterAction.BLOCK,
                pattern = "old",
                isEnabled = false,
                feedId = "feed-9",
            )
            Mockito.`when`(dao.findById(stored.id)).thenReturn(stored)
            val saved = ArrayList<FilterRule>()
            kotlinx.coroutines.runBlocking {
                Mockito.`when`(dao.upsert(*arrayOf(Mockito.any<FilterRule>())))
                    .thenAnswer { invocation ->
                        @Suppress("UNCHECKED_CAST")
                        val rules = invocation.arguments[0] as Array<out FilterRule>
                        saved.addAll(rules)
                        null
                    }
            }

            val viewModel = newViewModel()
            viewModel.startEditing(stored.id)
            advanceUntilIdle()
            viewModel.updateName("new name")
            viewModel.updateCondition(0, EditableCondition(pattern = "new"))

            assertTrue(viewModel.save())
            advanceUntilIdle()

            assertEquals(1, saved.size)
            val persisted = saved.single()
            assertEquals("rule-42", persisted.id)
            assertEquals("feed-9", persisted.feedId)
            assertEquals("new name", persisted.name)
            assertFalse("enablement must round-trip", persisted.isEnabled)
        }

    @Test
    fun `delete forwards the rule to the dao`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        val rule = existingRule(id = "doomed")

        viewModel.delete(rule)
        advanceUntilIdle()

        verify(dao).delete(rule)
    }

    @Test
    fun `setEnabled updates the rule with the new enabled flag`() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        val rule = existingRule(id = "toggle", isEnabled = true)

        viewModel.setEnabled(rule, false)
        advanceUntilIdle()

        // setEnabled must persist a copy with the new enabled flag. Using the
        // expected copy as the verify argument avoids Mockito's "any() on
        // suspend fun" issue.
        verify(dao).update(rule.copy(isEnabled = false))
    }
}
