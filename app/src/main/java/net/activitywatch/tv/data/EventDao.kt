package net.activitywatch.tv.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: Event): Long

    @Update
    suspend fun update(event: Event)

    @Query("SELECT * FROM event WHERE bucketId = :bucketId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastEventForBucket(bucketId: String): Event?

    @Query("SELECT * FROM event WHERE bucketId = :bucketId AND data = :data ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMatchingEvent(bucketId: String, data: String): Event?

    @Query("SELECT * FROM event WHERE bucketId = :bucketId ORDER BY timestamp ASC")
    suspend fun getAllEventsForBucket(bucketId: String): List<Event>

    @Query("""
        SELECT * FROM event
        WHERE bucketId = :bucketId
          AND timestamp >= :startMs
          AND timestamp < :endMs
        ORDER BY timestamp ASC
    """)
    suspend fun getTimelineEvents(bucketId: String, startMs: Long, endMs: Long): List<Event>

    @Query("SELECT * FROM event WHERE bucketId = :bucketId AND id > :afterId ORDER BY id ASC")
    suspend fun getEventsAfterIdForBucket(bucketId: String, afterId: Long): List<Event>
}
