package net.activitywatch.tv.sync

import android.os.Build
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.activitywatch.tv.data.AWDatabase
import net.activitywatch.tv.data.Event
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream

private const val SYNC_PORT = 5606
private const val MIME_JSON = "application/json"
private val BUCKET_EVENTS_RE = Regex("^/api/v0/buckets/([^/]+)/events$")

class SyncServer(
    private val db: AWDatabase,
    private val buffer: SyncEventBuffer
) : NanoHTTPD(SYNC_PORT) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startTime = System.currentTimeMillis()

    fun stopServer() {
        stop()
        scope.cancel()
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.uri == "/api/v0/info" -> handleInfo()
        session.uri == "/api/v0/buckets" -> handleBuckets()
        BUCKET_EVENTS_RE.matches(session.uri) -> handleBucketEvents(session)
        session.uri == "/api/v0/sync/stream" -> handleSseStream(session)
        session.uri == "/api/v0/sync/ack" && session.method == Method.POST -> handleAck(session)
        else -> jsonError(Response.Status.NOT_FOUND, "not found")
    }

    private fun handleInfo(): Response = jsonOk(JSONObject().apply {
        put("hostname", Build.MODEL)
        put("version", "1.0")
        put("uptimeMs", System.currentTimeMillis() - startTime)
        put("port", SYNC_PORT)
    }.toString())

    private fun handleBuckets(): Response {
        val buckets = runBlocking { db.bucketDao().getAllBuckets() }
        val arr = JSONArray()
        buckets.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id); put("type", b.type); put("client", b.client)
                put("hostname", b.hostname); put("created", b.created)
                b.name?.let { put("name", it) }
            })
        }
        return jsonOk(arr.toString())
    }

    private fun handleBucketEvents(session: IHTTPSession): Response {
        val bucketId = BUCKET_EVENTS_RE.find(session.uri)!!.groupValues[1]
        val since = session.parms["since"]?.toLongOrNull() ?: 0L
        val limit = session.parms["limit"]?.toIntOrNull() ?: 1000
        val events = runBlocking {
            db.eventDao().getTimelineEvents(bucketId, since, Long.MAX_VALUE)
        }.takeLast(limit)
        val arr = JSONArray()
        events.forEach { arr.put(eventToJson(it)) }
        return jsonOk(arr.toString())
    }

    private fun handleSseStream(session: IHTTPSession): Response {
        val lastSeq = session.headers["last-event-id"]?.toLongOrNull() ?: -1L
        val bucketFilter = session.parms["buckets"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
        val since = session.parms["since"]?.toLongOrNull() ?: 0L

        val channel = Channel<SseEvent>(Channel.UNLIMITED)
        val listener: (SseEvent) -> Unit = { evt ->
            if (bucketFilter == null || evt.bucket in bucketFilter) channel.trySend(evt)
        }

        val pipeIn = PipedInputStream(8192)
        val pipeOut = PipedOutputStream(pipeIn)

        scope.launch {
            val writer = pipeOut.bufferedWriter()
            try {
                buffer.addListener(listener)

                if (lastSeq >= 0) {
                    // Reconnect: replay buffered events after lastSeq
                    buffer.eventsAfter(lastSeq).forEach { evt ->
                        if (bucketFilter == null || evt.bucket in bucketFilter)
                            writeLiveEvent(writer, evt)
                    }
                } else {
                    // Initial connect: send historical DB events since timestamp
                    val targetBuckets = if (bucketFilter != null) {
                        bucketFilter.mapNotNull { db.bucketDao().getBucketById(it) }
                    } else {
                        db.bucketDao().getAllBuckets()
                    }
                    var maxHistoricalId = 0L
                    for (bucket in targetBuckets) {
                        db.eventDao().getTimelineEvents(bucket.id, since, Long.MAX_VALUE).forEach { e ->
                            writeHistoricalEvent(writer, bucket.id, e)
                            if (e.id > maxHistoricalId) maxHistoricalId = e.id
                        }
                    }
                    // Discard live events already covered by the historical query
                    var stale = channel.tryReceive().getOrNull()
                    while (stale != null && stale.event.id <= maxHistoricalId) {
                        stale = channel.tryReceive().getOrNull()
                    }
                    stale?.let { writeLiveEvent(writer, it) }
                }

                // Stream live events, keepalive comment every 15 s
                while (true) {
                    val evt = withTimeoutOrNull(15_000) { channel.receive() }
                    if (evt != null) {
                        writeLiveEvent(writer, evt)
                        var next = channel.tryReceive().getOrNull()
                        while (next != null) { writeLiveEvent(writer, next); next = channel.tryReceive().getOrNull() }
                    } else {
                        writer.write(": keepalive\n\n")
                        writer.flush()
                    }
                }
            } catch (_: Exception) {
                // client disconnected or pipe broken — normal exit
            } finally {
                buffer.removeListener(listener)
                channel.close()
                try { pipeOut.close() } catch (_: Exception) {}
            }
        }

        return newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn).also {
            it.addHeader("Cache-Control", "no-cache")
            it.addHeader("X-Accel-Buffering", "no")
            it.addHeader("Connection", "keep-alive")
        }
    }

    private fun handleAck(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val seq = try {
            JSONObject(body).getLong("seq")
        } catch (_: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, """expected {"seq": N}""")
        }
        buffer.ack(seq)
        return jsonOk(JSONObject().put("acked", seq).toString())
    }

    private fun writeLiveEvent(writer: BufferedWriter, evt: SseEvent) {
        writer.write("id: ${evt.seq}\nevent: event\ndata: ${sseDataJson(evt.seq, evt.bucket, evt.event)}\n\n")
        writer.flush()
    }

    private fun writeHistoricalEvent(writer: BufferedWriter, bucket: String, e: Event) {
        writer.write("event: event\ndata: ${sseDataJson(-1L, bucket, e)}\n\n")
        writer.flush()
    }

    private fun sseDataJson(seq: Long, bucket: String, e: Event) = JSONObject().apply {
        if (seq >= 0) put("seq", seq)
        put("bucket", bucket)
        put("event", eventToJson(e))
    }.toString()

    private fun eventToJson(e: Event) = JSONObject().apply {
        put("id", e.id)
        put("timestamp", e.timestamp)
        put("duration", e.duration)
        put("bucketId", e.bucketId)
        put("data", try { JSONObject(e.data) } catch (_: Exception) { e.data })
    }

    private fun jsonOk(body: String) =
        newFixedLengthResponse(Response.Status.OK, MIME_JSON, body)

    private fun jsonError(status: Response.Status, msg: String) =
        newFixedLengthResponse(status, MIME_JSON, JSONObject().put("error", msg).toString())
}
