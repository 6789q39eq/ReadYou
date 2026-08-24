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

    @Query(
        """
        SELECT * FROM filter_rule
        WHERE accountId = :accountId AND isEnabled = 1
        ORDER BY createdAt ASC
        """
    )
    suspend fun findEnabledByAccount(accountId: Int): List<FilterRule>

    @Query(
        """
        SELECT * FROM filter_rule
        WHERE feedId = :feedId
        ORDER BY createdAt ASC
        """
    )
    fun observeByFeed(feedId: String): Flow<List<FilterRule>>

    @Query(
        """
        SELECT * FROM filter_rule
        WHERE feedId = :feedId AND isEnabled = 1
        ORDER BY createdAt ASC
        """
    )
    suspend fun findEnabledByFeed(feedId: String): List<FilterRule>

    @Query("SELECT * FROM filter_rule WHERE id = :id")
    suspend fun findById(id: String): FilterRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vararg rules: FilterRule)

    @Update
    suspend fun update(rule: FilterRule)

    @Delete
    suspend fun delete(rule: FilterRule)

    @Query("DELETE FROM filter_rule WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM filter_rule WHERE feedId = :feedId")
    suspend fun deleteByFeed(feedId: String)
}
