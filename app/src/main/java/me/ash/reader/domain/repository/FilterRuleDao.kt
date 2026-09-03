package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.filter.FilterRule

@Dao
interface FilterRuleDao {

    @Query(
        """
        SELECT * FROM filter_rule
        WHERE accountId = :accountId
        ORDER BY createdAt ASC
        """
    )
    fun observeByAccount(accountId: Int): Flow<List<FilterRule>>

    /**
     * Enabled rules applicable to one feed in evaluation order:
     * global rules (feedId IS NULL) first, then feed-specific rules.
     */
    @Query(
        """
        SELECT * FROM filter_rule
        WHERE accountId = :accountId AND isEnabled = 1
          AND (feedId IS NULL OR feedId = :feedId)
        ORDER BY createdAt ASC
        """
    )
    suspend fun findEnabledForAccountAndFeed(
        accountId: Int,
        feedId: String,
    ): List<FilterRule>

    @Query("SELECT * FROM filter_rule WHERE id = :id")
    suspend fun findById(id: String): FilterRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vararg rules: FilterRule)

    @Update
    suspend fun update(rule: FilterRule)

    @Delete
    suspend fun delete(rule: FilterRule)

    @Query("DELETE FROM filter_rule WHERE feedId = :feedId")
    suspend fun deleteByFeed(feedId: String)

    @Query("DELETE FROM filter_rule WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Int)
}
