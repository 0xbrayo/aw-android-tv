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
        SELECT COALESCE(json_extract(data, '$.appLabel'), json_extract(data, '$.app'), 'Unknown') AS appLabel,
               COALESCE(json_extract(data, '$.app'), '')                                          AS packageName,
               SUM(duration)                                                                       AS totalDurationMs
        FROM event
        WHERE bucketId = :bucketId
          AND timestamp >= :startMs
        GROUP BY json_extract(data, '$.appLabel')
        ORDER BY totalDurationMs DESC
        LIMIT 15
    """)
    suspend fun getTopApps(bucketId: String, startMs: Long): List<AppUsageSummary>

    @Query("""
        SELECT * FROM event
        WHERE bucketId = :bucketId
          AND timestamp >= :startMs
          AND timestamp < :endMs
        ORDER BY timestamp ASC
    """)
    suspend fun getTimelineEvents(bucketId: String, startMs: Long, endMs: Long): List<Event>
}
