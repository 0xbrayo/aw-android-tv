package net.activitywatch.tv.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "AWHeartbeat"

class HeartbeatService(private val database: AWDatabase) {

    private val mutex = Mutex()

    /**
     * Inserts an event using the heartbeat logic.
     * If the new event is identical (based on the JSON data) to the last event in the bucket,
     * and occurs within the pulsetime, the last event's duration is extended.
     * Otherwise, the new event is inserted as a new row.
     *
     * @param bucketId The ID of the bucket.
     * @param event The new event to insert.
     * @param pulseTimeMillis The maximum time (in milliseconds) between events to consider merging.
     */
    suspend fun insertHeartbeat(bucketId: String, event: Event, pulseTimeMillis: Long) {
        mutex.withLock {
        withContext(Dispatchers.IO) {
            val eventDao = database.eventDao()
            val candidate = eventDao.getLastMatchingEvent(bucketId, event.data)
            val title = try { org.json.JSONObject(event.data).optString("title") } catch (_: Exception) { event.data }

            if (candidate != null) {
                val endTimeOfLastEvent = candidate.timestamp + candidate.duration
                val timeDifference = event.timestamp - endTimeOfLastEvent

                if (timeDifference <= pulseTimeMillis) {
                    val newDuration = (event.timestamp + event.duration) - candidate.timestamp
                    Log.d(TAG, "MERGE  '$title'  id=${candidate.id}  gap=${timeDifference}ms  newDur=${newDuration}ms")
                    eventDao.update(candidate.copy(duration = newDuration))
                    return@withContext
                } else {
                    Log.d(TAG, "GAP    '$title'  gap=${timeDifference}ms > pulse=${pulseTimeMillis}ms → INSERT")
                }
            } else {
                Log.d(TAG, "NEW    '$title'  no candidate → INSERT")
            }

            eventDao.insert(event)
        }
        }
    }

    /**
     * Ensures a bucket exists before inserting events into it.
     */
    suspend fun ensureBucketExists(bucket: Bucket) {
        withContext(Dispatchers.IO) {
            val bucketDao = database.bucketDao()
            val existing = bucketDao.getBucketById(bucket.id)
            if (existing == null) {
                bucketDao.insert(bucket)
            }
        }
    }
}
