package dev.firsttake.probe

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.abs

data class TrackTiming(
    val mime: String,
    val sampleCount: Int,
    val firstTimestampUs: Long?,
    val lastTimestampUs: Long?,
    val medianDeltaUs: Long?,
    val p95DeltaUs: Long?,
    val maximumDeltaUs: Long?,
    val largeGapCount: Int,
    val width: Int? = null,
    val height: Int? = null,
    val declaredFrameRate: Int? = null,
)

data class Mp4IntegrityReport(
    val readable: Boolean,
    val video: TrackTiming?,
    val audio: TrackTiming?,
    val audioVideoEndDeltaUs: Long?,
    val error: String?,
)

object Mp4IntegrityInspector {
    private data class TrackMetadata(
        val index: Int,
        val mime: String,
        val width: Int?,
        val height: Int?,
        val declaredFrameRate: Int?,
    )

    fun inspect(file: File): Mp4IntegrityReport {
        return try {
            val tracks = inspectTracks(file)
            val video = tracks.firstOrNull { it.mime.startsWith("video/") }
            val audio = tracks.firstOrNull { it.mime.startsWith("audio/") }
            val endDelta = if (
                video?.lastTimestampUs != null &&
                audio?.lastTimestampUs != null
            ) {
                abs(video.lastTimestampUs - audio.lastTimestampUs)
            } else {
                null
            }
            Mp4IntegrityReport(
                readable = video != null && video.sampleCount > 0,
                video = video,
                audio = audio,
                audioVideoEndDeltaUs = endDelta,
                error = null,
            )
        } catch (error: Exception) {
            Mp4IntegrityReport(
                readable = false,
                video = null,
                audio = null,
                audioVideoEndDeltaUs = null,
                error = error.stackTraceToString(),
            )
        }
    }

    private fun inspectTracks(file: File): List<TrackTiming> {
        val metadataExtractor = MediaExtractor()
        metadataExtractor.setDataSource(file.absolutePath)
        val trackMetadata = (0 until metadataExtractor.trackCount).map { index ->
            val format = metadataExtractor.getTrackFormat(index)
            TrackMetadata(
                index = index,
                mime = format.getString(MediaFormat.KEY_MIME)
                    ?: "application/octet-stream",
                width = format.integerOrNull(MediaFormat.KEY_WIDTH),
                height = format.integerOrNull(MediaFormat.KEY_HEIGHT),
                declaredFrameRate =
                    format.integerOrNull(MediaFormat.KEY_FRAME_RATE),
            )
        }
        metadataExtractor.release()

        return trackMetadata.map { metadata ->
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(metadata.index)
            val timestamps = ArrayList<Long>()
            while (true) {
                val timestamp = extractor.sampleTime
                if (timestamp < 0) {
                    break
                }
                timestamps += timestamp
                if (!extractor.advance()) {
                    break
                }
            }
            extractor.release()
            timing(
                mime = metadata.mime,
                timestamps = timestamps,
                width = metadata.width,
                height = metadata.height,
                declaredFrameRate = metadata.declaredFrameRate,
            )
        }
    }

    private fun timing(
        mime: String,
        timestamps: List<Long>,
        width: Int?,
        height: Int?,
        declaredFrameRate: Int?,
    ): TrackTiming {
        val deltas = timestamps.zipWithNext { first, second -> second - first }
            .filter { it > 0 }
            .sorted()
        val median = percentile(deltas, 0.50)
        val largeGapThreshold = median?.let { (it * 1.5).toLong() }
        return TrackTiming(
            mime = mime,
            sampleCount = timestamps.size,
            firstTimestampUs = timestamps.firstOrNull(),
            lastTimestampUs = timestamps.lastOrNull(),
            medianDeltaUs = median,
            p95DeltaUs = percentile(deltas, 0.95),
            maximumDeltaUs = deltas.lastOrNull(),
            largeGapCount = if (largeGapThreshold == null) {
                0
            } else {
                deltas.count { it > largeGapThreshold }
            },
            width = width,
            height = height,
            declaredFrameRate = declaredFrameRate,
        )
    }

    private fun MediaFormat.integerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun percentile(sortedValues: List<Long>, ratio: Double): Long? {
        if (sortedValues.isEmpty()) {
            return null
        }
        val index = ((sortedValues.size - 1) * ratio).toInt()
        return sortedValues[index]
    }
}
