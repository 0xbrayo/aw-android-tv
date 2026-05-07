package net.activitywatch.tv.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bucket")
data class Bucket(
    @PrimaryKey
    val id: String,
    val type: String,
    val client: String,
    val hostname: String,
    val created: Long,
    val name: String? = null
)
