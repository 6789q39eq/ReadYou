package me.ash.reader.domain.model.filter

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A named filter rule owned by either the account (global, when [feedId] is
 * null) or a specific feed. Stored as JSON expression tree — see
 * [FilterExpression].
 */
@Entity(
    tableName = "filter_rule",
)
data class FilterRule(
    @PrimaryKey
    val id: String,
    @ColumnInfo(index = true)
    val accountId: Int,
    /** null ⇒ global (account-level) rule. */
    @ColumnInfo(index = true)
    val feedId: String?,
    val name: String,
    val isEnabled: Boolean = true,
    val action: FilterAction,
    val expressionJson: String,
    val createdAt: Long,
)
