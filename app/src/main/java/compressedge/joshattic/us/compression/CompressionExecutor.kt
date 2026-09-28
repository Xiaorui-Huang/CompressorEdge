package compressedge.joshattic.us.compression

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import compressedge.joshattic.us.R
import compressedge.joshattic.us.utils.VolumeAudioProcessor
import java.io.File
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs a single Media3 Transformer export for either the foreground or background path. */
@OptIn(UnstableApi::class)
object CompressionExecutor {

    data class Params(
        val inputUri: Uri,
        val outputPath: String,
        val videoMimeType: String,
        val outputHeight: Int,
        val outputFps: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val originalFps: Float,
        val targetBitrate: Int,
        val audioBitrate: Int,
        val audioCodec: String,
        val removeAudio: Boolean,
        val audioVolume: Float,
        val onHdrToneMap: (() -> Unit)? = null,
        val onMetadataResult: ((MetadataPreservationResult) -> Unit)? = null
    )

    /**
     * Per-field result of trying to carry the source video's creation/modification date and GPS
     * location into the compressed output. `null` means the field wasn't present on the source
     * (nothing to preserve, not a failure); `true`/`false` reflect whether re-reading the finished
     * output confirmed it actually landed there.
     */
    data class MetadataPreservationResult(
        val timestampPreserved: Boolean?,
        val locationPreserved: Boolean?
    )

    /** Seconds between the MP4/QuickTime epoch (1904-01-01 UTC) and the Unix epoch (1970-01-01 UTC). */
    private const val MP4_EPOCH_OFFSET_SECONDS = 2_082_844_800L

    /**
     * Reads the source's creation date and GPS location (if present) via [MediaMetadataRetriever],
     * which works for any content/file uri regardless of whether it's MediaStore-backed. Returns
     * `null` per field when it's missing or unparseable — the compression still proceeds, just
     * without preserving that field.
     */
    private fun readSourceMp4Metadata(context: Context, uri: Uri): Pair<Mp4TimestampData?, Mp4LocationData?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val timestamp = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.let { parseMp4Date(it) }
                ?.let { unixSeconds -> Mp4TimestampData(unixSeconds + MP4_EPOCH_OFFSET_SECONDS, unixSeconds + MP4_EPOCH_OFFSET_SECONDS) }
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                ?.let { parseIso6709Location(it) }
                ?.let { (lat, lon) -> Mp4LocationData(lat, lon) }
            Pair(timestamp, location)
        } catch (e: Exception) {
            Pair(null, null)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    /** Parses MediaMetadataRetriever's METADATA_KEY_DATE format ("yyyyMMdd'T'HHmmss.SSS'Z'") into Unix epoch seconds. */
    private fun parseMp4Date(raw: String): Long? {
        return try {
            val format = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.parse(raw)?.time?.let { it / 1000L }
        } catch (e: Exception) {
            null
        }
    }

    /** Parses an ISO 6709 location string (e.g. "+37.4219-122.0840/") into (latitude, longitude). */
    private fun parseIso6709Location(raw: String): Pair<Float, Float>? {
        return try {
            val match = Regex("^([+-][0-9.]+)([+-][0-9.]+)").find(raw.trim()) ?: return null
            val lat = match.groupValues[1].toFloatOrNull() ?: return null
            val lon = match.groupValues[2].toFloatOrNull() ?: return null
            Pair(lat, lon)
        } catch (e: Exception) {
            null
        }
    }

    /** Re-reads the just-written output file and checks whether the metadata we tried to inject actually landed there. */
    private fun verifyOutputMp4Metadata(
        outputPath: String,
        expectedTimestamp: Mp4TimestampData?,
        expectedLocation: Mp4LocationData?
    ): MetadataPreservationResult {
        if (expectedTimestamp == null && expectedLocation == null) {
            return MetadataPreservationResult(timestampPreserved = null, locationPreserved = null)
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(outputPath)
            val timestampPreserved = expectedTimestamp?.let {
                val actual = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { d -> parseMp4Date(d) }
                actual != null && actual + MP4_EPOCH_OFFSET_SECONDS == it.creationTimestampSeconds
            }
            val locationPreserved = expectedLocation?.let {
                val actual = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let { l -> parseIso6709Location(l) }
                actual != null && kotlin.math.abs(actual.first - it.latitude) < 0.001f && kotlin.math.abs(actual.second - it.longitude) < 0.001f
            }
            MetadataPreservationResult(timestampPreserved, locationPreserved)
        } catch (e: Exception) {
            MetadataPreservationResult(
                timestampPreserved = expectedTimestamp?.let { false },
                locationPreserved = expectedLocation?.let { false }
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    fun execute(
        context: Context,
        params: Params,
        onCompleted: (Long) -> Unit,
        onError: (ExportException) -> Unit
    ): Transformer {
        val videoMimeType = params.videoMimeType

        val decoderFactory = DefaultDecoderFactory.Builder(context)
            .setEnableDecoderFallback(true)
            .build()

        val cbrEncoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(params.targetBitrate)
                    .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    .build()
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder()
                    .setBitrate(params.audioBitrate)
                    .build()
            )
            .build()

        val vbrEncoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(true)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(params.targetBitrate)
                    .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build()
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder()
                    .setBitrate(params.audioBitrate)
                    .build()
            )
            .build()

        val isMediaTek = isMediaTekDeviceOrEncoder(videoMimeType)
        val primaryEncoderFactory = if (isMediaTek) vbrEncoderFactory else cbrEncoderFactory
        val fallbackEncoderFactory = if (isMediaTek) cbrEncoderFactory else vbrEncoderFactory

        val audioMimeType = when (params.audioCodec) {
            MimeTypes.AUDIO_OPUS -> MimeTypes.AUDIO_OPUS
            else -> MimeTypes.AUDIO_AAC
        }

        // Avoid re-encoding audio (which can introduce quality loss and takes extra time)
        // when the source track already matches what we'd produce: same codec (AAC-LC),
        // same bitrate, no volume change, and audio isn't being removed.
        val audioProbe = probeAudioTrack(context, params.inputUri)
        val audioPassthrough = audioProbe.hasAudio &&
            !params.removeAudio &&
            audioMimeType == MimeTypes.AUDIO_AAC &&
            audioProbe.mime == MimeTypes.AUDIO_AAC &&
            (audioProbe.aacProfile == -1 || audioProbe.aacProfile == android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC) &&
            params.audioVolume == 1f &&
            audioProbe.bitrate > 0 &&
            params.audioBitrate == audioProbe.bitrate

        val shouldIncludeAudio = !params.removeAudio && audioProbe.hasAudio

        val (sourceTimestamp, sourceLocation) = readSourceMp4Metadata(context, params.inputUri)

        val encoderFactory = object : Codec.EncoderFactory {
            override fun createForAudioEncoding(
                format: androidx.media3.common.Format,
                logSessionId: android.media.metrics.LogSessionId?
            ): Codec {
                return primaryEncoderFactory.createForAudioEncoding(format, logSessionId)
            }

            override fun createForVideoEncoding(
                format: androidx.media3.common.Format,
                logSessionId: android.media.metrics.LogSessionId?
            ): Codec {
                val targetFps = if (params.outputFps > 0) params.outputFps.toFloat() else params.originalFps
                var modifiedFormatBuilder = format.buildUpon()
                if (targetFps > 0f) {
                    modifiedFormatBuilder.setFrameRate(targetFps)
                }
                if (format.colorInfo == null || !androidx.media3.common.ColorInfo.isTransferHdr(format.colorInfo)) {
                    modifiedFormatBuilder.setColorInfo(null)
                }
                val modifiedFormat = modifiedFormatBuilder.build()

                return try {
                    primaryEncoderFactory.createForVideoEncoding(modifiedFormat, logSessionId)
                } catch (e: Exception) {
                    fallbackEncoderFactory.createForVideoEncoding(modifiedFormat, logSessionId)
                }
            }

            override fun audioNeedsEncoding(): Boolean =
                !audioPassthrough && primaryEncoderFactory.audioNeedsEncoding()
            override fun videoNeedsEncoding(): Boolean = primaryEncoderFactory.videoNeedsEncoding()
        }

        val transformerBuilder = Transformer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .setVideoMimeType(videoMimeType)
            .setMaxDelayBetweenMuxerSamplesMs(30_000)
            .apply {
                if (!audioPassthrough) {
                    setAudioMimeType(audioMimeType)
                }
            }
            .setMuxerFactory(
                InAppMp4Muxer.Factory { metadataEntries ->
                    sourceTimestamp?.let { metadataEntries.add(it) }
                    sourceLocation?.let { metadataEntries.add(it) }
                }
            )
            .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoderFactory, Clock.DEFAULT, null))
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    params.onMetadataResult?.invoke(
                        verifyOutputMp4Metadata(params.outputPath, sourceTimestamp, sourceLocation)
                    )
                    onCompleted(File(params.outputPath).length())
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    onError(exportException)
                }
            })

        val transformer = transformerBuilder.build()

        val effectsList = mutableListOf<Effect>()

        if (params.outputHeight > 0 && params.outputHeight != params.originalHeight) {
            val aspectRatio =
                if (params.originalHeight > 0) params.originalWidth.toFloat() / params.originalHeight else 16f / 9f
            var width = (params.outputHeight * aspectRatio).toInt()
            var height = params.outputHeight

            if (width % 2 != 0) width -= 1
            if (height % 2 != 0) height -= 1

            if (width > 0 && height > 0) {
                effectsList.add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
        }

        val mediaItem = MediaItem.fromUri(params.inputUri)

        val audioProcessors: List<AudioProcessor> = if (shouldIncludeAudio && !audioPassthrough) {
            val volumeProcessor = VolumeAudioProcessor().apply { setVolume(params.audioVolume) }
            listOf(volumeProcessor, SonicAudioProcessor())
        } else {
            emptyList()
        }
        val editedMediaItemBuilder = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, effectsList))
            .setRemoveAudio(!shouldIncludeAudio)
        if (params.outputFps > 0) {
            editedMediaItemBuilder.setFrameRate(params.outputFps)
        }
        val editedMediaItem = editedMediaItemBuilder.build()

        var hdrMode = Composition.HDR_MODE_KEEP_HDR
        if (Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.MODEL.contains("Pixel 10")) {
            if (videoMimeType == MimeTypes.VIDEO_H265 || videoMimeType == MimeTypes.VIDEO_H264) {
                if (isHdr(context, params.inputUri)) {
                    hdrMode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                    params.onHdrToneMap?.invoke()
                }
            }
        }

        val sequence = if (shouldIncludeAudio) {
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedMediaItem))
        } else {
            EditedMediaItemSequence.withVideoFrom(listOf(editedMediaItem))
        }

        val composition = Composition.Builder(
            listOf(sequence)
        )
            .setHdrMode(hdrMode)
            .build()

        transformer.start(composition, params.outputPath)
        return transformer
    }

    private data class AudioProbe(
        val hasAudio: Boolean,
        val mime: String?,
        val bitrate: Int,
        val aacProfile: Int
    )

    private fun probeAudioTrack(context: Context, uri: Uri): AudioProbe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else 0
                    val aacProfile = if (format.containsKey("aac-profile")) format.getInteger("aac-profile") else -1
                    return AudioProbe(hasAudio = true, mime = mime, bitrate = bitrate, aacProfile = aacProfile)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }
        return AudioProbe(hasAudio = false, mime = null, bitrate = 0, aacProfile = -1)
    }

    suspend fun executeSuspend(
        context: Context,
        params: Params,
        onProgress: (androidx.media3.transformer.ProgressHolder, Transformer) -> Unit
    ): Long = withContext(Dispatchers.Main) {
        var progressJob: Job? = null
        try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val transformer = try {
                    execute(
                        context,
                        params,
                        onCompleted = { size -> if (cont.isActive) cont.resume(size) },
                        onError = { err -> if (cont.isActive) cont.resumeWithException(err) }
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return@suspendCancellableCoroutine
                }

                progressJob = launch {
                    while (isActive) {
                        val holder = androidx.media3.transformer.ProgressHolder()
                        val state = transformer.getProgress(holder)
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            onProgress(holder, transformer)
                        }
                        delay(200)
                    }
                }

                cont.invokeOnCancellation {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        try {
                            transformer.cancel()
                        } catch (_: Exception) {}
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                transformer.cancel()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } finally {
            progressJob?.cancel()
        }
    }

    fun errorMessage(context: Context, exception: ExportException): String {
        val isCodecError = exception.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED ||
            exception.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED
        val isDecoderInitError = exception.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED
        val isEncoderInitError = exception.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED
        val isMuxerError = exception.errorCode == ExportException.ERROR_CODE_MUXING_FAILED
        val isHuawei = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

        return when {
            isMuxerError && isHuawei -> context.getString(R.string.error_huawei_muxer)
            isDecoderInitError -> context.getString(R.string.error_decoder_config_unsupported)
            isEncoderInitError -> context.getString(R.string.error_encoder_config_unsupported)
            isCodecError -> context.getString(R.string.error_codec_unsupported)
            else -> exception.localizedMessage ?: context.getString(R.string.error_unknown)
        }
    }

    private fun isMediaTekDeviceOrEncoder(mimeType: String): Boolean {
        try {
            val hardware = Build.HARDWARE.lowercase()
            val board = Build.BOARD.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.lowercase() else ""

            if (hardware.contains("mediatek") || board.contains("mediatek") || manufacturer.contains("mediatek") ||
                soc.contains("mediatek") || soc.contains("dimensity") ||
                hardware.matches(Regex(""".*mt\d{4}.*""")) || board.matches(Regex(""".*mt\d{4}.*"""))
            ) {
                return true
            }

            val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    val name = info.name.lowercase()
                    if (name.contains("mtk") || name.contains("mediatek")) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun isHdr(context: Context, uri: Uri): Boolean {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= 30) {
                val transfer =
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
                return transfer == "6" || transfer == "7"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        return false
    }
}
