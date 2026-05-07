package net.activitywatch.tv.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "event",
    foreignKeys = [
        ForeignKey(
            entity = Bucket::class,
            parentColumns = ["id"],
            childColumns = ["bucketId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bucketId")]
)
data class Event(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val duration: Long,
    val datamodelVersion: Int = 1,
    val bucketId: String,
    val data: String // Stored as JSON string
)
