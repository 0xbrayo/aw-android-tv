package net.activitywatch.tv.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BucketDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bucket: Bucket): Long

    @Query("SELECT * FROM bucket WHERE id = :id LIMIT 1")
    suspend fun getBucketById(id: String): Bucket?

    @Query("SELECT * FROM bucket")
    suspend fun getAllBuckets(): List<Bucket>
}
