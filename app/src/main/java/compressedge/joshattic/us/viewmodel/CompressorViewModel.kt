package compressedge.joshattic.us.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Transformer
import compressedge.joshattic.us.BuildConfig
import compressedge.joshattic.us.R
import compressedge.joshattic.us.compression.BackgroundCompressionManager
import compressedge.joshattic.us.compression.BackgroundCompressionService
import compressedge.joshattic.us.compression.CompressionExecutor
import compressedge.joshattic.us.model.CompressorUiState
import compressedge.joshattic.us.model.FilenameSegment
import compressedge.joshattic.us.model.ReplaceRollbackEntry
import compressedge.joshattic.us.model.DefaultAudioConfig
import compressedge.joshattic.us.model.DefaultVideoConfig
import compressedge.joshattic.us.model.QualityPreset
import compressedge.joshattic.us.model.QualityPresetConfig
import compressedge.joshattic.us.model.TargetSizePreset
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class CompressorViewModel(application: Application) : AndroidViewModel(application) {
    private data class VideoTrackInfo(
        val mimeType: String?,
        val width: Int,
        val height: Int,
        val frameRate: Float
    )

    private data class CompressionPlan(
        val outputVideoMimeType: String,
        val outputHeight: Int,
        val outputFps: Int,
        val warnings: List<String>,
        val blockingError: String?
    )

    private val _uiState = MutableStateFlow(CompressorUiState())
    val uiState = _uiState.asStateFlow()
    
    private val prefs: SharedPreferences by lazy {
        getApplication<Application>().getSharedPreferences("compressor_prefs", Context.MODE_PRIVATE)
    }

    private class TrackProbe(
        val video: VideoTrackInfo?,
        val audioMime: String?,
        val audioBitrate: Int,
        val aacProfile: Int
    ) {
        val hasAudio: Boolean get() = audioMime != null
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private val cachedCodecInfos: List<android.media.MediaCodecInfo> by lazy {
            try {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        private const val PREF_CUSTOM_OUTPUT_TREE_URI = "custom_output_tree_uri"
        private const val PREF_CUSTOM_OUTPUT_FOLDER_NAME = "custom_output_folder_name"
        private const val PREF_SAVE_NEXT_TO_ORIGINAL = "save_next_to_original"
        private const val PREF_REPLACE_ORIGINAL = "replace_original"
        private const val PREF_REPLACE_ROLLBACK = "replace_rollback_entries"
        private const val REPLACE_ROLLBACK_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        private const val PREF_SAVED_VERSION_CODE = "saved_app_version_code"
        private const val PREF_FILENAME_SEGMENTS = "filename_segments_v2"
        private const val DEFAULT_FILENAME_SEGMENTS = "token:original_name|token:compressed"
        private val CURRENT_VERSION_CODE = BuildConfig.VERSION_CODE
        private const val PERSIST_URI_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    init {
        val saved = prefs.getLong("total_saved_bytes", 0L)
        val showBitrate = prefs.getBoolean("show_bitrate", false)
        val useMbps = prefs.getBoolean("use_mbps", false)
        val showStorageSaved = prefs.getBoolean("show_storage_saved", true)
        val showTargetSizePreset = prefs.getBoolean("show_target_size_preset", true)
        val autoSaveSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val autoSaveToPhotos = autoSaveSupported && prefs.getBoolean("auto_save_photos", true)
        val customOutputTreeUri = prefs.getString(PREF_CUSTOM_OUTPUT_TREE_URI, null)
        val customOutputFolderName = prefs.getString(PREF_CUSTOM_OUTPUT_FOLDER_NAME, null)
        val saveNextToOriginal = prefs.getBoolean(PREF_SAVE_NEXT_TO_ORIGINAL, false)
        val replaceOriginal = prefs.getBoolean(PREF_REPLACE_ORIGINAL, false)
        val pendingReplaceRollback = loadReplaceRollback()
        val highConfig = loadQualityPresetConfig("preset_high", QualityPresetConfig(resolutionShortSide = 0, targetFps = 0, sizeRatio = 0.7f, audioBitrate = 320_000))
        val mediumConfig = loadQualityPresetConfig("preset_medium", QualityPresetConfig(resolutionShortSide = 1080, targetFps = 30, sizeRatio = 0.4f, audioBitrate = 192_000))
        val lowConfig = loadQualityPresetConfig("preset_low", QualityPresetConfig(resolutionShortSide = 720, targetFps = 30, sizeRatio = 0.2f, audioBitrate = 128_000))
        val sizePresetsList = loadTargetSizePresets()
        val defaultVideo = loadDefaultVideoConfig()
        val defaultAudio = loadDefaultAudioConfig()
        val filenameSegments = deserializeSegments(
            prefs.getString(PREF_FILENAME_SEGMENTS, DEFAULT_FILENAME_SEGMENTS) ?: DEFAULT_FILENAME_SEGMENTS
        )

        val savedVersionCode = prefs.getInt(PREF_SAVED_VERSION_CODE, -1)
        val showWhatsNew = if (savedVersionCode != -1) {
            CURRENT_VERSION_CODE > savedVersionCode
        } else {
            val isExistingUser = prefs.all.isNotEmpty()
            if (!isExistingUser) {
                prefs.edit().putInt(PREF_SAVED_VERSION_CODE, CURRENT_VERSION_CODE).apply()
            }
            isExistingUser
        }
        val backgroundCompressionEnabled = prefs.getBoolean("background_compression_enabled", false)
        val backgroundCompressionPrompted = prefs.getBoolean("background_compression_prompted", false)

        _uiState.update { it.copy(
            totalSavedBytes = saved, 
            showBitrate = showBitrate, 
            useMbps = useMbps,
            showStorageSaved = showStorageSaved,
            showTargetSizePreset = showTargetSizePreset,
            autoSaveToPhotos = autoSaveToPhotos,
            backgroundCompressionEnabled = backgroundCompressionEnabled,
            backgroundCompressionPrompted = backgroundCompressionPrompted,
            customOutputTreeUri = customOutputTreeUri,
            customOutputFolderName = customOutputFolderName,
            saveNextToOriginal = saveNextToOriginal,
            replaceOriginal = replaceOriginal,
            pendingReplaceRollback = pendingReplaceRollback,
            highPresetConfig = highConfig,
            mediumPresetConfig = mediumConfig,
            lowPresetConfig = lowConfig,
            targetSizePresets = sizePresetsList,
            defaultVideoConfig = defaultVideo,
            defaultAudioConfig = defaultAudio,
            filenameSegments = filenameSegments,
            showWhatsNewDialog = showWhatsNew
        ) }
        checkSupportedCodecs()
        checkSupportedAudioCodecs()
        clearCache()

        if (pendingReplaceRollback.isNotEmpty()) {
            // Filtering out entries whose backup file no longer exists (e.g. swept by
            // purgeStaleRollbackFiles) requires a disk stat per entry - deferred off the main
            // thread rather than done inline in loadReplaceRollback(), which init calls synchronously.
            viewModelScope.launch(Dispatchers.IO) {
                val stillValid = pendingReplaceRollback.filter { File(it.rollbackFilePath).exists() }
                if (stillValid.size != pendingReplaceRollback.size) {
                    updateReplaceRollback(stillValid)
                }
            }
        }
    }

    fun dismissWhatsNewDialog() {
        prefs.edit().putInt(PREF_SAVED_VERSION_CODE, CURRENT_VERSION_CODE).apply()
        _uiState.update { it.copy(showWhatsNewDialog = false) }
    }

    fun showWhatsNewDialog() {
        _uiState.update { it.copy(showWhatsNewDialog = true) }
    }
    
    internal fun checkSupportedCodecs() {
        val allCodecsEnabled = prefs.getBoolean("all_codecs_enabled", false)
        val supported = mutableListOf<String>()

        if (allCodecsEnabled) {
            supported.addAll(getDeviceEncoders())
        } else {
            supported.add(MimeTypes.VIDEO_H264)
            if (hasEncoder(MimeTypes.VIDEO_H265)) {
                supported.add(MimeTypes.VIDEO_H265)
            }
            if (hasEncoder(MimeTypes.VIDEO_AV1)) {
                supported.add(MimeTypes.VIDEO_AV1)
            }
        }
        
        _uiState.update { 
            var newCodec = it.videoCodec
            if (!supported.contains(newCodec)) {
                newCodec = when {
                    supported.contains(MimeTypes.VIDEO_H265) -> MimeTypes.VIDEO_H265
                    supported.contains(MimeTypes.VIDEO_H264) -> MimeTypes.VIDEO_H264
                    supported.isNotEmpty() -> supported.first()
                    else -> MimeTypes.VIDEO_H264
                }
            }
            it.copy(
                supportedCodecs = supported, 
                videoCodec = newCodec, 
                useH265 = newCodec == MimeTypes.VIDEO_H265,
                allCodecsEnabled = allCodecsEnabled,
                allCodecsUnlocked = true
            ) 
        }
    }

    internal fun checkSupportedAudioCodecs() {
        val supported = mutableListOf(MimeTypes.AUDIO_AAC)
        // Opus is usually software-only; include software encoders for audio.
        if (hasAnyAudioEncoder(MimeTypes.AUDIO_OPUS)) {
            supported.add(MimeTypes.AUDIO_OPUS)
        }

        _uiState.update {
            var newCodec = it.audioCodec
            if (!supported.contains(newCodec)) {
                newCodec = MimeTypes.AUDIO_AAC
            }
            var defaultConfig = it.defaultAudioConfig
            if (!supported.contains(defaultConfig.defaultAudioCodec)) {
                defaultConfig = defaultConfig.copy(defaultAudioCodec = MimeTypes.AUDIO_AAC)
            }
            it.copy(
                supportedAudioCodecs = supported,
                audioCodec = newCodec,
                defaultAudioConfig = defaultConfig
            )
        }
    }

    /** True if any encoder (hardware or software) supports the given audio MIME type. */
    internal fun hasAnyAudioEncoder(mimeType: String): Boolean {
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(mimeType, ignoreCase = true)) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    internal fun getDeviceEncoders(): List<String> {
        val codecs = mutableSetOf<String>()
        try {
            for (info in cachedCodecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.startsWith("video/", ignoreCase = true)) {
                        codecs.add(type.lowercase())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val preferred = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1)
        return codecs.toList().sortedWith { a, b ->
            val indexA = preferred.indexOf(a)
            val indexB = preferred.indexOf(b)
            when {
                indexA != -1 && indexB != -1 -> indexA.compareTo(indexB)
                indexA != -1 -> -1
                indexB != -1 -> 1
                else -> a.compareTo(b)
            }
        }
    }

    fun isSoftwareCodec(mimeType: String): Boolean {
        try {
            var hasHardware = false
            var hasSoftware = false
            for (info in cachedCodecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    val isSW = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isSoftwareOnly
                    } else {
                        val name = info.name.lowercase()
                        name.startsWith("c2.android") || name.startsWith("omx.google")
                    }
                    if (isSW) {
                        hasSoftware = true
                    } else {
                        hasHardware = true
                    }
                }
            }
            return hasSoftware && !hasHardware
        } catch (e: Exception) {
            return false
        }
    }

    fun enableAllCodecsFeature() {
        prefs.edit {
            putBoolean("all_codecs_enabled", true)
        }
        checkSupportedCodecs()
    }

    fun disableAllCodecsFeature() {
        prefs.edit {
            putBoolean("all_codecs_enabled", false)
        }
        checkSupportedCodecs()
    }

    internal fun hasEncoder(mimeType: String): Boolean {
        try {
            for (info in cachedCodecInfos) {
                if (!info.isEncoder) continue

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (info.isSoftwareOnly) {
                        continue
                    }
                } else {
                    val name = info.name.lowercase()
                    if (name.startsWith("c2.android")) {
                        continue
                    }
                }

                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    return true
                }
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private var compressionJob: Job? = null
    private var bgJob: Job? = null
    private var activeTransformer: Transformer? = null
    private var lastProbeUri: Uri? = null
    private var lastProbeResult: TrackProbe? = null

    private suspend fun probeTracksCached(context: Context, uri: Uri): TrackProbe =
        withContext(Dispatchers.IO) {
            val cached = lastProbeResult
            if (cached != null && lastProbeUri == uri) {
                cached
            } else {
                val fresh = probeTracks(context, uri)
                lastProbeUri = uri
                lastProbeResult = fresh
                fresh
            }
        }

    fun updateSelectedUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val queueItems = mutableListOf<compressedge.joshattic.us.model.QueueItem>()
            var firstItemSize = 0L
            var firstItemWidth = 0
            var firstItemHeight = 0
            var firstItemBitrate = 0
            var firstItemAudioBitrate = 0
            var firstItemFps = 30f
            var firstItemVideoMime: String? = null
            var firstItemDuration = 0L
            var firstItemName: String? = null

            for ((index, uri) in uris.withIndex()) {
                var size = 0L
                var width = 0
                var height = 0
                var bitrate = 0
                var audioBitrate = 0
                var fps = 30f
                var videoMime: String? = null
                var duration = 0L
                var originalName: String? = null

                try {
                    val probe = probeTracks(context, uri)
                    if (index == 0) {
                        lastProbeUri = uri
                        lastProbeResult = probe
                    }
                    audioBitrate = probe.audioBitrate
                    videoMime = probe.video?.mimeType
                    val videoInfo = probe.video

                    val cursor = context.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                        null,
                        null,
                        null
                    )
                    if (cursor != null && cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            originalName = cursor.getString(nameIndex)
                        }
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                        cursor.close()
                    }
                    if (size <= 0L) {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use {
                            size = it.statSize
                        }
                    }

                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)

                    width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

                    val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation == 90 || rotation == 270) {
                        val temp = width
                        width = height
                        height = temp
                    }

                    bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                    duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

                    val fpsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    fps = fpsStr?.toFloatOrNull() ?: 0f
                    if (fps <= 0f && videoInfo != null && videoInfo.frameRate > 0f) {
                        fps = videoInfo.frameRate
                    }
                    if (fps <= 0f) {
                        fps = 30f
                    }

                    retriever.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (index == 0) {
                    firstItemSize = size
                    firstItemWidth = width
                    firstItemHeight = height
                    firstItemBitrate = bitrate
                    firstItemAudioBitrate = audioBitrate
                    firstItemFps = fps
                    firstItemVideoMime = videoMime
                    firstItemDuration = duration
                    firstItemName = originalName
                }

                queueItems.add(
                    compressedge.joshattic.us.model.QueueItem(
                        uri = uri,
                        originalSize = size,
                        originalWidth = width,
                        originalHeight = height,
                        originalBitrate = bitrate,
                        originalAudioBitrate = audioBitrate,
                        originalFps = fps,
                        originalVideoMime = videoMime,
                        durationMs = duration,
                        originalName = originalName
                    )
                )
            }

            val current = _uiState.value
            val videoConfig = current.defaultVideoConfig
            val audioConfig = current.defaultAudioConfig

            val defaultTargetMb = if (firstItemSize > 0) (firstItemSize / (1024.0 * 1024.0) * videoConfig.defaultSizeRatio).toFloat().coerceAtLeast(0.1f) else 10f
            val defaultTargetPercentage = videoConfig.defaultSizeRatio * 100f

            val preferredCodec = if (current.supportedCodecs.contains(videoConfig.defaultVideoCodec)) videoConfig.defaultVideoCodec
                                 else if (current.supportedCodecs.contains(MimeTypes.VIDEO_H265)) MimeTypes.VIDEO_H265
                                 else MimeTypes.VIDEO_H264

            val preferredAudioCodec = if (current.supportedAudioCodecs.contains(audioConfig.defaultAudioCodec)) {
                audioConfig.defaultAudioCodec
            } else {
                MimeTypes.AUDIO_AAC
            }

            val targetHeight = if (videoConfig.defaultTargetResolutionHeight > 0) videoConfig.defaultTargetResolutionHeight else 0
            val targetFpsVal = if (videoConfig.defaultTargetFps > 0 && firstItemFps >= videoConfig.defaultTargetFps) videoConfig.defaultTargetFps else 0
            
            val isBatch = queueItems.size > 1

            _uiState.update { state ->
                state.copy(
                    isBatchMode = isBatch,
                    queue = queueItems,
                    globalTargetSizePercentage = defaultTargetPercentage,
                    selectedUri = queueItems.first().uri,
                    originalSize = firstItemSize,
                    originalWidth = firstItemWidth,
                    originalHeight = firstItemHeight,
                    originalBitrate = firstItemBitrate,
                    originalAudioBitrate = firstItemAudioBitrate,
                    originalFps = firstItemFps,
                    originalVideoMime = firstItemVideoMime,
                    durationMs = firstItemDuration,
                    originalName = firstItemName,
                    targetSizeMb = defaultTargetMb,
                    targetResolutionHeight = targetHeight,
                    targetFps = targetFpsVal,
                     videoCodec = preferredCodec,
                     useH265 = preferredCodec == MimeTypes.VIDEO_H265,
                     activePreset = QualityPreset.CUSTOM,
                     audioCodec = preferredAudioCodec,
                     audioBitrate = audioConfig.defaultAudioBitrate,
                     audioBitrateLocked = false,
                     targetResolutionLocked = false,
                     targetFpsLocked = false,
                     removeAudio = audioConfig.defaultRemoveAudio,
                    audioVolume = audioConfig.defaultAudioVolume,
                    isCompressing = false,
                    progress = 0f,
                    compressedUri = null,
                    compressedSize = 0L,
                    currentOutputSize = 0L,
                    error = null,
                    errorLog = null,
                    saveSuccess = false,
                    isSaving = false,
                    hasShared = false
                ).autoAdjust(defaultTargetMb)
            }
        }
    }
    
    fun updateQueueItem(uri: Uri, updater: (compressedge.joshattic.us.model.QueueItem) -> compressedge.joshattic.us.model.QueueItem) {
        _uiState.update { state ->
            val newQueue = state.queue.map { if (it.uri == uri) updater(it) else it }
            state.copy(queue = newQueue)
        }
    }

    fun removeQueueItem(uri: Uri) {
        _uiState.update { state ->
            val newQueue = state.queue.filter { it.uri != uri }
            if (newQueue.isEmpty()) {
                state.copy(selectedUri = null, isBatchMode = false, queue = emptyList())
            } else if (newQueue.size == 1) {
                val first = newQueue.first()
                state.copy(
                    isBatchMode = false, 
                    queue = newQueue,
                    selectedUri = first.uri,
                    originalSize = first.originalSize,
                    originalWidth = first.originalWidth,
                    originalHeight = first.originalHeight,
                    originalBitrate = first.originalBitrate,
                    originalAudioBitrate = first.originalAudioBitrate,
                    originalFps = first.originalFps,
                    originalVideoMime = first.originalVideoMime,
                    durationMs = first.durationMs,
                    originalName = first.originalName
                )
            } else {
                state.copy(queue = newQueue)
            }
        }
    }
    
    fun markAsShared() {
        _uiState.update { it.copy(hasShared = true) }
    }
    
    fun applyPreset(preset: QualityPreset) {
        if (preset == QualityPreset.CUSTOM) {
             _uiState.update { it.copy(activePreset = QualityPreset.CUSTOM) }
             return
        }
        
        val current = _uiState.value

        val config = when(preset) {
            QualityPreset.HIGH -> current.highPresetConfig
            QualityPreset.MEDIUM -> current.mediumPresetConfig
            QualityPreset.LOW -> current.lowPresetConfig
            else -> null
        }

        if (config != null) {
            val targetHeight = config.resolutionShortSide
            val targetFpsVal = if (config.targetFps > 0 && current.originalFps >= config.targetFps) config.targetFps else 0
            val targetMb = (current.originalSize / (1024.0 * 1024.0) * config.sizeRatio).toFloat().coerceAtLeast(0.1f)

            _uiState.update { 
                it.copy(
                    activePreset = preset,
                    targetResolutionHeight = targetHeight,
                    targetFps = targetFpsVal,
                    targetSizeMb = targetMb,
                    audioBitrate = config.audioBitrate,
                    audioBitrateLocked = true,
                    targetResolutionLocked = true,
                    targetFpsLocked = true,
                    removeAudio = false
                ).autoAdjust(targetMb, lockAudioBitrate = true, allowUpward = false)
            }
        }
    }

    fun updateHighPresetConfig(config: QualityPresetConfig) {
        _uiState.update { it.copy(highPresetConfig = config) }
        saveQualityPresetConfig("preset_high", config)
    }

    fun updateMediumPresetConfig(config: QualityPresetConfig) {
        _uiState.update { it.copy(mediumPresetConfig = config) }
        saveQualityPresetConfig("preset_medium", config)
    }

    fun updateLowPresetConfig(config: QualityPresetConfig) {
        _uiState.update { it.copy(lowPresetConfig = config) }
        saveQualityPresetConfig("preset_low", config)
    }

    fun resetQualityPresets() {
        val defaultConfigHigh = QualityPresetConfig(resolutionShortSide = 0, targetFps = 0, sizeRatio = 0.7f, audioBitrate = 320_000)
        val defaultConfigMedium = QualityPresetConfig(resolutionShortSide = 1080, targetFps = 30, sizeRatio = 0.4f, audioBitrate = 192_000)
        val defaultConfigLow = QualityPresetConfig(resolutionShortSide = 720, targetFps = 30, sizeRatio = 0.2f, audioBitrate = 128_000)
        _uiState.update { it.copy(
            highPresetConfig = defaultConfigHigh,
            mediumPresetConfig = defaultConfigMedium,
            lowPresetConfig = defaultConfigLow
        ) }
        prefs.edit().remove("preset_high").remove("preset_medium").remove("preset_low").apply()
    }

    fun addTargetSizePreset(label: String, sizeMb: Float) {
        val newPreset = compressedge.joshattic.us.model.TargetSizePreset(
            id = "custom_" + System.currentTimeMillis(),
            sizeMb = sizeMb,
            label = label,
            isCustom = true
        )
        val newList = (_uiState.value.targetSizePresets + newPreset).sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        saveTargetSizePresets(newList)
    }

    fun updateTargetSizePreset(id: String, label: String, sizeMb: Float) {
        val newList = _uiState.value.targetSizePresets.map { preset ->
            if (preset.id == id) preset.copy(label = label, sizeMb = sizeMb)
            else preset
        }.sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        saveTargetSizePresets(newList)
    }

    fun deleteTargetSizePreset(id: String) {
        val newList = _uiState.value.targetSizePresets.filterNot { it.id == id }.sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        saveTargetSizePresets(newList)
    }

    fun resetTargetSizePresets() {
        val newList = compressedge.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        prefs.edit().remove("target_size_presets").apply()
    }

    fun updateDefaultVideoConfig(config: DefaultVideoConfig) {
        _uiState.update { it.copy(defaultVideoConfig = config) }
        saveDefaultVideoConfig(config)
    }

    fun resetDefaultVideoConfig() {
        val defaultConfig = DefaultVideoConfig()
        _uiState.update { it.copy(defaultVideoConfig = defaultConfig) }
        prefs.edit().remove("default_video_config").apply()
    }

    fun updateDefaultAudioConfig(config: DefaultAudioConfig) {
        _uiState.update { it.copy(defaultAudioConfig = config) }
        saveDefaultAudioConfig(config)
    }

    fun resetDefaultAudioConfig() {
        val defaultConfig = DefaultAudioConfig()
        _uiState.update { it.copy(defaultAudioConfig = defaultConfig) }
        prefs.edit().remove("default_audio_config").apply()
    }

    internal fun normalizeSegments(segments: List<FilenameSegment>): List<FilenameSegment> {
        val result = mutableListOf<FilenameSegment>()
        var currentText = ""
        for (seg in segments) {
            when (seg) {
                is FilenameSegment.Text -> {
                    currentText += seg.value
                }
                is FilenameSegment.Token -> {
                    result.add(FilenameSegment.Text(currentText))
                    currentText = ""
                    result.add(seg)
                }
            }
        }
        result.add(FilenameSegment.Text(currentText))
        return result
    }

    fun updateFilenameSegments(segments: List<FilenameSegment>) {
        val normalized = normalizeSegments(segments)
        _uiState.update { it.copy(filenameSegments = normalized) }
        prefs.edit().putString(PREF_FILENAME_SEGMENTS, serializeSegments(normalized)).apply()
    }

    fun insertFilenameTokenAt(index: Int, tokenKey: String) {
        val current = _uiState.value.filenameSegments
        // Each token can only be added once
        if (current.any { it is FilenameSegment.Token && it.key == tokenKey }) return
        val newList = current.toMutableList()
        val clampedIndex = index.coerceIn(0, newList.size)
        newList.add(clampedIndex, FilenameSegment.Token(tokenKey))
        updateFilenameSegments(newList)
    }

    fun removeFilenameSegmentAt(index: Int) {
        val current = _uiState.value.filenameSegments.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            updateFilenameSegments(current)
        }
    }

    fun moveFilenameSegment(fromIndex: Int, toIndex: Int) {
        val current = _uiState.value.filenameSegments.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            updateFilenameSegments(current)
        }
    }

    fun resetFilenamePattern() {
        val defaultSegments = listOf(
            FilenameSegment.Token("original_name"),
            FilenameSegment.Token("compressed")
        )
        updateFilenameSegments(defaultSegments)
    }

    internal fun serializeSegments(segments: List<FilenameSegment>): String {
        return segments
            .filter { it !is FilenameSegment.Text || it.value.isNotEmpty() }
            .joinToString("|") { seg ->
                when (seg) {
                    is FilenameSegment.Text -> "text:${seg.value}"
                    is FilenameSegment.Token -> "token:${seg.key}"
                }
            }
    }

    internal fun deserializeSegments(raw: String): List<FilenameSegment> {
        if (raw.isBlank()) return normalizeSegments(emptyList())
        val parsed = raw.split("|").mapNotNull { part ->
            when {
                part.startsWith("text:") -> {
                    val value = part.removePrefix("text:")
                    if (value.isNotEmpty()) FilenameSegment.Text(value) else null
                }
                part.startsWith("token:") -> {
                    val key = part.removePrefix("token:")
                    if (key.isNotEmpty()) FilenameSegment.Token(key) else null
                }
                else -> null
            }
        }
        return normalizeSegments(parsed)
    }

    fun generatePreviewFileName(state: CompressorUiState): String {
        return compressedOutputFileName(state)
    }

    internal fun compressedOutputFileName(state: CompressorUiState): String {
        val dateStr by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
        val timeStr by lazy { SimpleDateFormat("HH-mm-ss", Locale.US).format(Date()) }
        val randomStr by lazy { String.format(Locale.US, "%06d", (0..999999).random()) }

        val resStr by lazy {
            val h = if (state.targetResolutionHeight > 0) state.targetResolutionHeight else state.originalHeight
            if (h > 0) "${h}p" else "1080p"
        }

        val fpsStr by lazy {
            val fps = if (state.targetFps > 0) state.targetFps else state.originalFps.toInt()
            if (fps > 0) "${fps}fps" else "30fps"
        }

        val videoBitrateStr by lazy {
            val calcBitrate = state.targetBitrate
            if (calcBitrate > 0) {
                if (state.useMbps) {
                    val mbps = calcBitrate / 1_000_000f
                    String.format(Locale.US, "Video_%.1fMbps", mbps)
                } else {
                    val kbps = calcBitrate / 1000
                    "Video_${kbps}kbps"
                }
            } else {
                "Video_5Mbps"
            }
        }

        val audioBitrateStr by lazy {
            if (state.removeAudio) {
                "NoAudio"
            } else {
                val kbps = if (state.audioBitrate > 0) state.audioBitrate / 1000 else 128
                "Audio_${kbps}kbps"
            }
        }

        val encodingStr by lazy {
            when (state.videoCodec) {
                MimeTypes.VIDEO_H265 -> "H265"
                MimeTypes.VIDEO_H264 -> "H264"
                MimeTypes.VIDEO_AV1 -> "AV1"
                MimeTypes.VIDEO_VP9 -> "VP9"
                else -> state.videoCodec.substringAfterLast("/").uppercase(Locale.US)
            }
        }

        val audioStatusStr by lazy {
            if (state.removeAudio) "NoAudio" else "WithAudio"
        }

        val presetStr by lazy {
            when (state.activePreset) {
                QualityPreset.HIGH -> "High"
                QualityPreset.MEDIUM -> "Medium"
                QualityPreset.LOW -> "Low"
                QualityPreset.CUSTOM -> "Custom"
            }
        }

        val originalNameStr by lazy {
            state.originalName?.substringBeforeLast(".")?.takeIf { it.isNotBlank() } ?: "Compressed"
        }

        val parts = mutableListOf<String>()
        for (segment in state.filenameSegments) {
            val evaluated = when (segment) {
                is FilenameSegment.Text -> segment.value.trim()
                is FilenameSegment.Token -> when (segment.key) {
                    "original_name" -> originalNameStr
                    "compressed" -> "Compressed"
                    "date" -> dateStr
                    "time" -> timeStr
                    "random" -> randomStr
                    "resolution" -> resStr
                    "framerate", "fps" -> fpsStr
                    "bitrate" -> videoBitrateStr
                    "audio_bitrate" -> audioBitrateStr
                    "encoding", "codec" -> encodingStr
                    "audio_status" -> audioStatusStr
                    "preset" -> presetStr
                    else -> segment.key
                }
            }
            if (evaluated.isNotBlank()) {
                parts.add(evaluated)
            }
        }

        val rawName = parts.joinToString("_")
        val sanitized = rawName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

        val finalStem = if (sanitized.isBlank()) "Compressed_${System.currentTimeMillis()}" else sanitized
        return "$finalStem.mp4"
    }

    private fun saveDefaultVideoConfig(config: DefaultVideoConfig) {
        val obj = JSONObject().apply {
            put("defaultVideoCodec", config.defaultVideoCodec)
            put("defaultTargetResolutionHeight", config.defaultTargetResolutionHeight)
            put("defaultTargetFps", config.defaultTargetFps)
            put("defaultSizeRatio", config.defaultSizeRatio.toDouble())
        }
        prefs.edit().putString("default_video_config", obj.toString()).apply()
    }

    private fun loadDefaultVideoConfig(): DefaultVideoConfig {
        val str = prefs.getString("default_video_config", null) ?: return DefaultVideoConfig()
        return try {
            val obj = JSONObject(str)
            DefaultVideoConfig(
                defaultVideoCodec = obj.optString("defaultVideoCodec", MimeTypes.VIDEO_H265),
                defaultTargetResolutionHeight = obj.optInt("defaultTargetResolutionHeight", 0),
                defaultTargetFps = obj.optInt("defaultTargetFps", 0),
                defaultSizeRatio = obj.optDouble("defaultSizeRatio", 0.7).toFloat()
            )
        } catch (e: Exception) {
            DefaultVideoConfig()
        }
    }

    private fun saveDefaultAudioConfig(config: DefaultAudioConfig) {
        val obj = JSONObject().apply {
            put("defaultAudioCodec", config.defaultAudioCodec)
            put("defaultAudioBitrate", config.defaultAudioBitrate)
            put("defaultRemoveAudio", config.defaultRemoveAudio)
            put("defaultAudioVolume", config.defaultAudioVolume.toDouble())
        }
        prefs.edit().putString("default_audio_config", obj.toString()).apply()
    }

    private fun loadDefaultAudioConfig(): DefaultAudioConfig {
        val str = prefs.getString("default_audio_config", null) ?: return DefaultAudioConfig()
        return try {
            val obj = JSONObject(str)
            DefaultAudioConfig(
                defaultAudioCodec = obj.optString("defaultAudioCodec", MimeTypes.AUDIO_AAC),
                defaultAudioBitrate = obj.optInt("defaultAudioBitrate", 128_000),
                defaultRemoveAudio = obj.optBoolean("defaultRemoveAudio", false),
                defaultAudioVolume = obj.optDouble("defaultAudioVolume", 1.0).toFloat()
            )
        } catch (e: Exception) {
            DefaultAudioConfig()
        }
    }

    private fun saveQualityPresetConfig(key: String, config: QualityPresetConfig) {
        val obj = JSONObject().apply {
            put("resolutionShortSide", config.resolutionShortSide)
            put("targetFps", config.targetFps)
            put("sizeRatio", config.sizeRatio.toDouble())
            put("audioBitrate", config.audioBitrate)
            if (config.label != null) put("label", config.label) else put("label", JSONObject.NULL)
        }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private fun loadQualityPresetConfig(key: String, default: QualityPresetConfig): QualityPresetConfig {
        val str = prefs.getString(key, null) ?: return default
        return try {
            val obj = JSONObject(str)
            QualityPresetConfig(
                resolutionShortSide = obj.getInt("resolutionShortSide"),
                targetFps = obj.getInt("targetFps"),
                sizeRatio = obj.getDouble("sizeRatio").toFloat(),
                audioBitrate = obj.getInt("audioBitrate"),
                label = when {
                    !obj.has("label") || obj.isNull("label") -> null
                    else -> obj.optString("label", "").takeIf { it.isNotBlank() }
                }
            )
        } catch (e: Exception) {
            try {
                val parts = str.split(",")
                QualityPresetConfig(
                    resolutionShortSide = parts[0].toInt(),
                    targetFps = parts[1].toInt(),
                    sizeRatio = parts[2].toFloat(),
                    audioBitrate = parts[3].toInt(),
                    label = null
                )
            } catch (ex: Exception) {
                default
            }
        }
    }

    // Set when saveCompressedOutput() is called while a previous save/replace (or an undo) is still
    // in flight - e.g. replace-original's backup+delete+insert I/O outlasting the next batch item's
    // compression. Rather than silently dropping that call (compressedItemsWithOriginals() folds in
    // every completed item, so a dropped call for the last item in a batch would never be retried by
    // a later call), the in-flight operation's own finally block re-invokes saveCompressedOutput once
    // it's done, picking up the now-current full item list.
    private var pendingSaveRetry = false

    private fun maybeRetrySave(context: Context) {
        if (pendingSaveRetry) {
            pendingSaveRetry = false
            saveCompressedOutput(context)
        }
    }

    /** Persists [CompressorUiState.pendingReplaceRollback] and updates state together, so the two never drift apart. */
    private fun updateReplaceRollback(newList: List<ReplaceRollbackEntry>) {
        _uiState.update { it.copy(pendingReplaceRollback = newList) }
        saveReplaceRollback(newList)
    }

    /**
     * Undo records are plain in-memory ViewModel state by default, but the backup files they point
     * to are written to durable internal storage and can outlive the process — persist the records
     * too, so a process death between a replace and pressing Undo doesn't strand a backed-up
     * original with no way to find it again (before the stale-file sweep eventually deletes it).
     */
    private fun saveReplaceRollback(list: List<ReplaceRollbackEntry>) {
        val array = JSONArray()
        for (entry in list) {
            val obj = JSONObject().apply {
                put("rollbackFilePath", entry.rollbackFilePath)
                put("insertedMediaUri", entry.insertedMediaUri)
                put("originalDisplayName", entry.originalDisplayName)
                put("originalRelativePath", entry.originalRelativePath)
            }
            array.put(obj)
        }
        prefs.edit { putString(PREF_REPLACE_ROLLBACK, array.toString()) }
    }

    private fun loadReplaceRollback(): List<ReplaceRollbackEntry> {
        val str = prefs.getString(PREF_REPLACE_ROLLBACK, null) ?: return emptyList()
        val array = try {
            JSONArray(str)
        } catch (e: Exception) {
            return emptyList()
        }
        val list = mutableListOf<ReplaceRollbackEntry>()
        for (i in 0 until array.length()) {
            // Parsed per-entry: a single malformed record (e.g. from an interrupted prior write)
            // must not discard the whole persisted list and strand otherwise-valid undo records.
            // Deliberately no File.exists() check here - this is called synchronously from init on
            // the main thread, and per-entry disk stats there would be blocking I/O. Stale entries
            // (backup file no longer present) are filtered out afterward, off the main thread - see
            // the call site in init.
            try {
                val obj = array.getJSONObject(i)
                list.add(
                    ReplaceRollbackEntry(
                        rollbackFilePath = obj.getString("rollbackFilePath"),
                        insertedMediaUri = obj.getString("insertedMediaUri"),
                        originalDisplayName = obj.getString("originalDisplayName"),
                        originalRelativePath = obj.getString("originalRelativePath")
                    )
                )
            } catch (e: Exception) {
                // Skip just this entry.
            }
        }
        return list
    }

    private fun saveTargetSizePresets(list: List<compressedge.joshattic.us.model.TargetSizePreset>) {
        val array = JSONArray()
        for (preset in list) {
            val obj = JSONObject().apply {
                put("id", preset.id)
                put("sizeMb", preset.sizeMb.toDouble())
                put("label", preset.label)
                put("isCustom", preset.isCustom)
            }
            array.put(obj)
        }
        prefs.edit().putString("target_size_presets", array.toString()).apply()
    }

    private fun loadTargetSizePresets(): List<compressedge.joshattic.us.model.TargetSizePreset> {
        val str = prefs.getString("target_size_presets", null) ?: return compressedge.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
        return try {
            val parsedList = if (str.startsWith("[")) {
                val array = JSONArray(str)
                val list = mutableListOf<compressedge.joshattic.us.model.TargetSizePreset>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        compressedge.joshattic.us.model.TargetSizePreset(
                            id = obj.getString("id"),
                            sizeMb = obj.getDouble("sizeMb").toFloat(),
                            label = obj.getString("label"),
                            isCustom = obj.optBoolean("isCustom", false)
                        )
                    )
                }
                list
            } else {
                // Legacy split parser fallback
                str.split(";\n", ";").mapNotNull { itemStr ->
                    val parts = itemStr.trim().split("|")
                    if (parts.size >= 4) {
                        compressedge.joshattic.us.model.TargetSizePreset(
                            id = parts[0],
                            sizeMb = parts[1].toFloat(),
                            label = parts[2],
                            isCustom = parts[3].trim().toBoolean()
                        )
                    } else null
                }
            }

            if (parsedList.isEmpty()) {
                compressedge.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
            } else {
                var needsSave = false
                val migrated = parsedList.map { preset ->
                    if (preset.id == "discord" && !preset.isCustom && preset.sizeMb == 10f) {
                        needsSave = true
                        preset.copy(sizeMb = 20f, label = "Discord")
                    } else {
                        preset
                    }
                }.toMutableList()

                if (migrated.none { it.id == "github" }) {
                    migrated.add(compressedge.joshattic.us.model.TargetSizePreset("github", 10f, "GitHub", isCustom = false))
                    needsSave = true
                }

                // Fix duplicate GitHub: discord was previously not renamable, so any
                // stored custom label for discord that equals "GitHub" is likely an
                // accidental rename that was previously invisible and now shows as duplicate.
                // Reset such cases to default.
                for (i in migrated.indices) {
                    val p = migrated[i]
                    if (p.id == "discord" && !p.isCustom) {
                        val labelLower = p.label.trim().lowercase()
                        if (labelLower == "github" || (labelLower.contains("github") && labelLower.contains("discord"))) {
                            migrated[i] = p.copy(label = "Discord")
                            needsSave = true
                        }
                    }
                }

                // Deduplicate by id (keep first occurrence)
                val seenIds = mutableSetOf<String>()
                val dedupedById = mutableListOf<compressedge.joshattic.us.model.TargetSizePreset>()
                for (preset in migrated) {
                    if (seenIds.add(preset.id)) {
                        dedupedById.add(preset)
                    } else {
                        needsSave = true
                    }
                }

                // Remove custom presets that duplicate a default preset's size+label
                // e.g., user previously added a custom 10MB "GitHub" before the default existed
                val defaultKeys = compressedge.joshattic.us.model.defaultTargetSizePresets
                    .associateBy { it.sizeMb to it.label.lowercase() }
                val nonCustomKeys = dedupedById.filter { !it.isCustom }
                    .associateBy { it.sizeMb to it.label.lowercase() }
                val finalList = mutableListOf<compressedge.joshattic.us.model.TargetSizePreset>()
                for (preset in dedupedById) {
                    if (preset.isCustom) {
                        val key = preset.sizeMb to preset.label.trim().lowercase()
                        if (key in nonCustomKeys || key in defaultKeys) {
                            needsSave = true
                            continue
                        }
                    }
                    finalList.add(preset)
                }

                val result = finalList.sortedBy { it.sizeMb }
                if (needsSave || result.size != migrated.size) {
                    saveTargetSizePresets(result)
                }
                result
            }
        } catch (e: Exception) {
            compressedge.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
        }
    }

    fun setTargetSizePreview(targetMb: Float) {
        _uiState.update { it.copy(targetSizeMb = targetMb) }
    }

    fun setGlobalTargetSizePercentage(percentage: Float) {
        _uiState.update { it.copy(globalTargetSizePercentage = percentage) }
    }

    fun setTargetSize(targetMb: Float) {
        _uiState.update { it.copy(targetSizeMb = targetMb, activePreset = QualityPreset.CUSTOM).autoAdjust(targetMb) }
    }

    fun setVideoCodec(codec: String) {
        _uiState.update { 
            val temp = it.copy(
                videoCodec = codec, 
                useH265 = codec == MimeTypes.VIDEO_H265, 
                activePreset = QualityPreset.CUSTOM
            )
            temp.autoAdjust(temp.targetSizeMb)
        }
    }

    fun setAudioCodec(codec: String) {
        _uiState.update {
            it.copy(
                audioCodec = codec,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun toggleShowBitrate() {
        _uiState.update { 
            val newValue = !it.showBitrate
            prefs.edit { putBoolean("show_bitrate", newValue) }
            it.copy(showBitrate = newValue)
        }
    }

    fun toggleBitrateUnit() {
        _uiState.update { 
            val newValue = !it.useMbps
            prefs.edit { putBoolean("use_mbps", newValue) }
            it.copy(useMbps = newValue)
        }
    }

    fun toggleShowStorageSaved() {
        _uiState.update { 
            val newValue = !it.showStorageSaved
            prefs.edit { putBoolean("show_storage_saved", newValue) }
            it.copy(showStorageSaved = newValue)
        }
    }

    fun toggleShowTargetSizePreset() {
        _uiState.update { 
            val newValue = !it.showTargetSizePreset
            prefs.edit { putBoolean("show_target_size_preset", newValue) }
            it.copy(showTargetSizePreset = newValue)
        }
    }

    fun toggleAutoSaveToPhotos() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        _uiState.update { 
            val newValue = !it.autoSaveToPhotos
            prefs.edit { putBoolean("auto_save_photos", newValue) }
            it.copy(autoSaveToPhotos = newValue)
        }
    }

    fun toggleBackgroundCompression() {
        _uiState.update {
            val newValue = !it.backgroundCompressionEnabled
            prefs.edit {
                putBoolean("background_compression_enabled", newValue)
                putBoolean("background_compression_prompted", true)
            }
            it.copy(
                backgroundCompressionEnabled = newValue,
                backgroundCompressionPrompted = true
            )
        }
    }

    fun setBackgroundCompressionEnabled(enabled: Boolean) {
        _uiState.update {
            prefs.edit {
                putBoolean("background_compression_enabled", enabled)
                putBoolean("background_compression_prompted", true)
            }
            it.copy(
                backgroundCompressionEnabled = enabled,
                backgroundCompressionPrompted = true
            )
        }
    }

    fun setCustomOutputFolder(context: Context, treeUri: Uri) {
        try {
            releasePersistedTreeUri(context, _uiState.value.customOutputTreeUri)
            context.contentResolver.takePersistableUriPermission(treeUri, PERSIST_URI_FLAGS)
            val folderName = resolveFolderDisplayName(context, treeUri)
            prefs.edit {
                putString(PREF_CUSTOM_OUTPUT_TREE_URI, treeUri.toString())
                putString(PREF_CUSTOM_OUTPUT_FOLDER_NAME, folderName)
            }
            _uiState.update {
                it.copy(
                    customOutputTreeUri = treeUri.toString(),
                    customOutputFolderName = folderName
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearCustomOutputFolder(context: Context) {
        releasePersistedTreeUri(context, _uiState.value.customOutputTreeUri)
        prefs.edit {
            remove(PREF_CUSTOM_OUTPUT_TREE_URI)
            remove(PREF_CUSTOM_OUTPUT_FOLDER_NAME)
        }
        _uiState.update {
            it.copy(customOutputTreeUri = null, customOutputFolderName = null)
        }
    }

    fun setSaveNextToOriginal(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_SAVE_NEXT_TO_ORIGINAL, enabled) }
        _uiState.update { it.copy(saveNextToOriginal = enabled) }
    }

    fun setReplaceOriginal(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_REPLACE_ORIGINAL, enabled) }
        _uiState.update { it.copy(replaceOriginal = enabled) }
    }

    private fun releasePersistedTreeUri(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            val uri = Uri.parse(uriString)
            val stillHeld = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
            if (stillHeld) {
                context.contentResolver.releasePersistableUriPermission(uri, PERSIST_URI_FLAGS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolveFolderDisplayName(context: Context, treeUri: Uri): String {
        DocumentFile.fromTreeUri(context, treeUri)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val path = docId.substringAfter(':', missingDelimiterValue = docId)
            path.substringAfterLast('/').ifBlank { path }.ifBlank {
                getApplication<Application>().getString(R.string.output_location_default)
            }
        } catch (_: Exception) {
            getApplication<Application>().getString(R.string.output_location_default)
        }
    }

    /**
     * Saves using the configured location: custom SAF folder, or default Photos gallery.
     * When [CompressorUiState.saveNextToOriginal] is enabled, each file is saved next to its
     * original source file when that's resolvable (i.e. the original lives in MediaStore, which
     * covers the common "compressed a video from the gallery" case); any file that can't be
     * placed next to its original falls back to the folder above.
     */
    fun saveCompressedOutput(context: Context) {
        val currentState = _uiState.value
        if (currentState.isSaving) {
            pendingSaveRetry = true
            return
        }
        val items = compressedItemsWithOriginals(currentState)
        if (items.isEmpty()) return

        if (currentState.replaceOriginal) {
            replaceOriginals(context, items)
            return
        }

        if (!currentState.saveNextToOriginal) {
            val treeUri = currentState.customOutputTreeUri
            if (!treeUri.isNullOrBlank()) {
                saveToCustomTree(context, Uri.parse(treeUri), items.map { it.first })
            } else {
                saveToGallery(context, items.map { it.first })
            }
            return
        }

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var savedNextToOriginal = 0
                val fallbackUris = mutableListOf<Uri>()
                var anyItemFound = false
                for ((uri, originalUri) in items) {
                    val file = uri.path?.let { File(it) }
                    if (file == null || !file.exists()) continue
                    anyItemFound = true

                    val relativePath = originalRelativePath(context, originalUri)
                    if (relativePath != null && insertVideoIntoMediaStore(context, file, file.name, relativePath) != null) {
                        savedNextToOriginal++
                    } else {
                        fallbackUris.add(uri)
                    }
                }

                // Every item's temp file was already gone (e.g. clearCache() raced this) - nothing
                // was saved anywhere, so this must not fall through to saveSuccess = true below.
                if (!anyItemFound) {
                    _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_file_lost)) }
                    return@launch
                }

                if (fallbackUris.isNotEmpty()) {
                    val treeUri = currentState.customOutputTreeUri
                    if (!treeUri.isNullOrBlank()) {
                        val treeSaved = saveUrisToCustomTreeBlocking(context, Uri.parse(treeUri), fallbackUris)
                        if (treeSaved == 0 && savedNextToOriginal == 0) {
                            // Error already set by saveUrisToCustomTreeBlocking - don't overwrite it below.
                            return@launch
                        }
                        addPartialSaveWarning(treeSaved, fallbackUris.size)
                    } else {
                        val fallbackSaved = saveUrisToGalleryBlocking(context, fallbackUris)
                        if (fallbackSaved == 0 && savedNextToOriginal == 0) {
                            _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_gallery_entry)) }
                            return@launch
                        }
                        addPartialSaveWarning(fallbackSaved, fallbackUris.size)
                    }
                }

                _uiState.update { it.copy(saveSuccess = true) }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message))
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
                maybeRetrySave(context)
            }
        }
    }

    private var pendingDeleteConsent: CompletableDeferred<Boolean>? = null

    /**
     * Called by the UI right after it launches [CompressorUiState.pendingDeleteRequest], before the
     * result comes back — clears the state field immediately so a recomposition or config change
     * while the system dialog is still open (the ViewModel survives; the Activity/Compose tree
     * doesn't) can't see a non-null pendingDeleteRequest and launch the same IntentSender a second
     * time. The actual wait for the user's answer happens via [pendingDeleteConsent], independent of
     * this state field.
     */
    fun consumePendingDeleteRequest() {
        _uiState.update { it.copy(pendingDeleteRequest = null) }
    }

    /** Called by the UI after getting the launched request's result back. */
    fun onDeleteRequestResult(granted: Boolean) {
        pendingDeleteConsent?.complete(granted)
        pendingDeleteConsent = null
    }

    /** Restores the most recent replace-original save: deletes the replacement and re-inserts the backed-up original. */
    fun undoReplaceOriginal(context: Context) {
        // Shares the isSaving flag with saveCompressedOutput()/replaceOriginals(): both read-modify-
        // write pendingReplaceRollback and touch the same backup files, so letting them overlap risks
        // one clobbering the other's just-written rollback state. If a save/replace is in flight,
        // decline for now rather than racing it - the Undo button stays visible so the user can retry.
        if (_uiState.value.isSaving) return
        val rollback = _uiState.value.pendingReplaceRollback
        if (rollback.isEmpty()) return
        updateReplaceRollback(emptyList())
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            // An entry whose restore fails stays in stillPending (re-surfacing the Undo option) rather
            // than being silently dropped — and, same as replaceOriginals, the backup file is only
            // deleted once the restore actually landed, so a failed restore never loses the last copy.
            val stillPending = mutableListOf<ReplaceRollbackEntry>()
            try {
                for (entry in rollback) {
                    val backupFile = File(entry.rollbackFilePath)
                    if (!backupFile.exists()) {
                        // No backup to restore from (e.g. swept by purgeStaleRollbackFiles) - keep the
                        // entry pending rather than deleting the replacement anyway, which would leave
                        // neither file with no way to recover.
                        stillPending.add(entry)
                        continue
                    }
                    try {
                        context.contentResolver.delete(Uri.parse(entry.insertedMediaUri), null, null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    val restored = try {
                        insertVideoIntoMediaStore(context, backupFile, entry.originalDisplayName, entry.originalRelativePath) != null
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                    if (restored) {
                        backupFile.delete()
                    } else {
                        stillPending.add(entry)
                    }
                }
                if (stillPending.isNotEmpty()) {
                    updateReplaceRollback(stillPending)
                    // Otherwise the button just reappears with isSaving flipping briefly and no
                    // explanation of why the restore didn't happen (missing backup or a failed insert).
                    _uiState.update {
                        it.copy(error = getApplication<Application>().getString(R.string.error_undo_failed))
                    }
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
                maybeRetrySave(context)
            }
        }
    }

    /**
     * Verifies each candidate (playback + duration sanity + the metadata check from
     * [CompressionExecutor.checkMetadataPreservation]), backs up whatever passes, deletes the
     * original (batched consent on API 30+, a single consent dialog on API 29, a plain attempt with
     * no dialog on API 26-28), then inserts the compressed file in its place. Anything that fails
     * verification, backup, or delete consent is never touched and falls back to the plain default
     * save location instead — this never risks losing the original.
     */
    private fun replaceOriginals(context: Context, items: List<Pair<Uri, Uri?>>) {
        data class Candidate(val compressedFile: File, val originalUri: Uri, val relativePath: String, val displayName: String)

        val previousRollback = _uiState.value.pendingReplaceRollback

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            // Declared outside the try block so `finally` can always persist whatever rollback
            // entries were recorded before any exception, even one from an unrelated later step
            // (e.g. writing fallback items) — a candidate whose original is already deleted and
            // replacement already inserted must never lose its undo record.
            val newRollback = mutableListOf<ReplaceRollbackEntry>()
            try {
                val candidates = mutableListOf<Candidate>()
                val fallbackUris = mutableListOf<Uri>()
                var anyItemFound = false

                for ((uri, originalUri) in items) {
                    val file = uri.path?.let { File(it) }
                    if (file == null || !file.exists()) continue
                    anyItemFound = true

                    val relativePath = originalUri?.let { originalRelativePath(context, it) }
                    val displayName = originalUri?.let { queryDisplayName(context, it) }
                    val verified = originalUri != null && relativePath != null && displayName != null &&
                        verifyReplaceCandidate(context, originalUri, file)

                    if (verified) {
                        candidates.add(Candidate(file, originalUri!!, relativePath!!, displayName!!))
                    } else {
                        fallbackUris.add(uri)
                    }
                }

                val rollbackDir = File(context.filesDir, "replace_rollback").apply { mkdirs() }
                purgeStaleRollbackFiles(rollbackDir)

                val backedUp = mutableListOf<Pair<Candidate, File>>()
                for (candidate in candidates) {
                    val backupFile = File(rollbackDir, "${System.currentTimeMillis()}_${candidate.displayName}")
                    val ok = try {
                        context.contentResolver.openInputStream(candidate.originalUri)?.use { input ->
                            backupFile.outputStream().use { out -> input.copyTo(out, COPY_BUFFER_BYTES) }
                        }
                        backupFile.exists() && backupFile.length() > 0
                    } catch (e: Exception) {
                        false
                    }
                    if (ok) {
                        backedUp.add(candidate to backupFile)
                    } else {
                        backupFile.delete()
                        fallbackUris.add(Uri.fromFile(candidate.compressedFile))
                    }
                }

                if (backedUp.isNotEmpty()) {
                    val deletedOk: Map<Candidate, Boolean> = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                            val ok = deleteOriginalsBatch(context, backedUp.map { it.first.originalUri })
                            backedUp.associate { it.first to ok }
                        }
                        backedUp.size == 1 -> {
                            backedUp.associate { it.first to deleteOriginalWithConsent(context, it.first.originalUri) }
                        }
                        else -> {
                            // No clean multi-item consent flow below API 30: attempt a plain delete
                            // per item with no dialog, rather than chaining several consent prompts.
                            backedUp.associate { (candidate, _) ->
                                candidate to try {
                                    context.contentResolver.delete(candidate.originalUri, null, null)
                                    !uriStillExists(context, candidate.originalUri)
                                } catch (e: Exception) {
                                    false
                                }
                            }
                        }
                    }

                    for ((candidate, backupFile) in backedUp) {
                        if (deletedOk[candidate] != true) {
                            backupFile.delete()
                            fallbackUris.add(Uri.fromFile(candidate.compressedFile))
                            continue
                        }
                        // Isolated per candidate: an unexpected exception here (insertVideoIntoMediaStore's
                        // own contentResolver.insert() call isn't itself try/caught) must not abort the
                        // whole batch and lose already-recorded rollback entries for prior candidates —
                        // the original for THIS candidate is already deleted, so restore-from-backup is
                        // the same safe fallback as an ordinary insert failure.
                        try {
                            val insertedUri = insertVideoIntoMediaStore(context, candidate.compressedFile, candidate.displayName, candidate.relativePath)
                            if (insertedUri != null) {
                                newRollback.add(
                                    ReplaceRollbackEntry(
                                        rollbackFilePath = backupFile.absolutePath,
                                        insertedMediaUri = insertedUri.toString(),
                                        originalDisplayName = candidate.displayName,
                                        originalRelativePath = candidate.relativePath
                                    )
                                )
                            } else {
                                // Delete succeeded but insert failed (e.g. storage full): restore the
                                // original immediately from backup rather than leaving neither file.
                                // Only delete the backup once the restore actually landed — if storage
                                // is so full that even the small restore write fails too, keeping the
                                // backup on disk (until the next stale-file sweep) beats guaranteed
                                // permanent loss of the only remaining copy.
                                if (insertVideoIntoMediaStore(context, backupFile, candidate.displayName, candidate.relativePath) != null) {
                                    backupFile.delete()
                                }
                                fallbackUris.add(Uri.fromFile(candidate.compressedFile))
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            val restored = try {
                                insertVideoIntoMediaStore(context, backupFile, candidate.displayName, candidate.relativePath) != null
                            } catch (restoreError: Exception) {
                                restoreError.printStackTrace()
                                false
                            }
                            if (restored) {
                                backupFile.delete()
                            }
                            fallbackUris.add(Uri.fromFile(candidate.compressedFile))
                        }
                    }
                }

                // Nothing to save at all (every item's temp file was already gone) - don't fall
                // through to saveSuccess = true below.
                if (!anyItemFound) {
                    _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_file_lost)) }
                    return@launch
                }

                if (fallbackUris.isNotEmpty()) {
                    val currentState = _uiState.value
                    val treeUri = currentState.customOutputTreeUri
                    val fallbackSaved = if (!treeUri.isNullOrBlank()) {
                        // Error already set by saveUrisToCustomTreeBlocking on failure.
                        saveUrisToCustomTreeBlocking(context, Uri.parse(treeUri), fallbackUris)
                    } else {
                        saveUrisToGalleryBlocking(context, fallbackUris)
                    }
                    if (fallbackSaved == 0 && newRollback.isEmpty()) {
                        if (treeUri.isNullOrBlank()) {
                            _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_gallery_entry)) }
                        }
                        return@launch
                    }
                    addPartialSaveWarning(fallbackSaved, fallbackUris.size)
                }

                _uiState.update { it.copy(saveSuccess = true) }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message))
                }
            } finally {
                // Only supersede the previous rollback once this run actually produced a new one -
                // e.g. every candidate failed verification and fell back to a plain save, leaving
                // newRollback empty. The previous run's still-valid Undo (backup + record) must stay
                // intact in that case rather than being wiped out for a run that replaced nothing.
                // Always persisted when non-empty, even if the try block above threw partway through:
                // a candidate whose original was already deleted and replacement already inserted
                // before the exception must keep its undo record.
                if (newRollback.isNotEmpty()) {
                    previousRollback.forEach { File(it.rollbackFilePath).delete() }
                    updateReplaceRollback(newRollback)
                }
                _uiState.update { it.copy(isSaving = false) }
                maybeRetrySave(context)
            }
        }
    }

    /** Playback + duration sanity check, plus the metadata-preservation check from [CompressionExecutor]. */
    private fun verifyReplaceCandidate(context: Context, originalUri: Uri, compressedFile: File): Boolean {
        return try {
            val extractor = MediaExtractor()
            var trackCount = 0
            var durationUs = 0L
            try {
                extractor.setDataSource(compressedFile.absolutePath)
                trackCount = extractor.trackCount
                for (i in 0 until trackCount) {
                    val format = extractor.getTrackFormat(i)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                    }
                }
            } finally {
                extractor.release()
            }
            if (trackCount == 0) return false

            val originalDurationMs = queryOriginalDurationMs(context, originalUri)
            if (originalDurationMs > 0 && durationUs > 0) {
                val compressedDurationMs = durationUs / 1000
                val diff = kotlin.math.abs(compressedDurationMs - originalDurationMs)
                val tolerance = maxOf(1000L, originalDurationMs / 20)
                if (diff > tolerance) return false
            }

            val metadataResult = CompressionExecutor.checkMetadataPreservation(context, originalUri, compressedFile.absolutePath)
            if (metadataResult.timestampPreserved == false || metadataResult.locationPreserved == false) return false

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun queryOriginalDurationMs(context: Context, uri: Uri): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun uriStillExists(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun purgeStaleRollbackFiles(dir: File) {
        try {
            val cutoff = System.currentTimeMillis() - REPLACE_ROLLBACK_MAX_AGE_MS
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // A file just purged above may be the backup behind the *currently pending* undo entry
        // (e.g. it's older than 3 days but the user never tapped Undo) - prune it from state too,
        // not just from disk. Otherwise the Undo button keeps showing for an entry that can never
        // succeed, since nothing else evicts pendingReplaceRollback between app launches.
        val current = _uiState.value.pendingReplaceRollback
        if (current.isNotEmpty()) {
            val stillValid = current.filter { File(it.rollbackFilePath).exists() }
            if (stillValid.size != current.size) {
                updateReplaceRollback(stillValid)
            }
        }
    }

    /** API 30+: one system dialog covering every uri at once. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun deleteOriginalsBatch(context: Context, uris: List<Uri>): Boolean {
        return try {
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
            awaitDeleteConsent(pendingIntent.intentSender)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Exactly API 29: a direct delete throws [android.app.RecoverableSecurityException] with a one-time consent intent. */
    private suspend fun deleteOriginalWithConsent(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null)
            !uriStillExists(context, uri)
        } catch (e: SecurityException) {
            val recoverable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                e as? android.app.RecoverableSecurityException
            } else {
                null
            }
            if (recoverable != null) {
                val granted = awaitDeleteConsent(recoverable.userAction.actionIntent.intentSender)
                granted && !uriStillExists(context, uri)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun awaitDeleteConsent(intentSender: android.content.IntentSender): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingDeleteConsent = deferred
        _uiState.update { it.copy(pendingDeleteRequest = intentSender) }
        return deferred.await()
    }

    /** Pairs each pending compressed output with the source uri it was produced from, when known. */
    private fun compressedItemsWithOriginals(state: CompressorUiState): List<Pair<Uri, Uri?>> {
        val targetUris = if (state.compressedUris.isNotEmpty()) state.compressedUris else listOfNotNull(state.compressedUri)
        return targetUris.mapIndexed { index, uri -> uri to state.compressedOriginalUris.getOrNull(index) }
    }

    /** The original file's MediaStore folder (e.g. "DCIM/Camera"), if the original is a MediaStore item we can read metadata for. */
    private fun originalRelativePath(context: Context, originalUri: Uri?): String? {
        if (originalUri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            context.contentResolver.query(
                originalUri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the inserted item's uri on success, or null on failure (nothing left behind either way). */
    private fun insertVideoIntoMediaStore(context: Context, file: File, displayName: String, relativePath: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = context.contentResolver.insert(collection, values) ?: return null
        return try {
            val copied = context.contentResolver.openOutputStream(itemUri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, COPY_BUFFER_BYTES) }
                true
            } ?: false

            if (!copied) {
                context.contentResolver.delete(itemUri, null, null)
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
            }
            itemUri
        } catch (e: Exception) {
            e.printStackTrace()
            context.contentResolver.delete(itemUri, null, null)
            null
        }
    }

    /** Blocking (must run on an IO dispatcher) helper shared by [saveToGallery] and the next-to-original fallback path. */
    private fun saveUrisToGalleryBlocking(context: Context, uris: List<Uri>): Int {
        var savedCount = 0
        for (uri in uris) {
            val file = uri.path?.let { File(it) } ?: continue
            if (!file.exists()) continue
            if (insertVideoIntoMediaStore(context, file, file.name, Environment.DIRECTORY_MOVIES + "/Compressor Edge") != null) {
                savedCount++
            }
        }
        return savedCount
    }

    /**
     * Blocking (must run on an IO dispatcher) helper shared by [saveToCustomTree] and the
     * next-to-original/replace-original fallback paths. Returns how many files were actually
     * written, not just whether the tree itself was writable - a target folder that's writable but
     * where an individual file write fails (name collision the provider won't resolve, per-file
     * quota, a mid-copy exception) must not be silently counted as saved. Callers compare the count
     * against `uris.size` to detect a partial failure - see [addPartialSaveWarning].
     */
    private fun saveUrisToCustomTreeBlocking(context: Context, treeUri: Uri, uris: List<Uri>): Int {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree == null || !tree.canWrite()) {
            _uiState.update {
                it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, "Folder not writable"))
            }
            return 0
        }

        var savedCount = 0
        for (uri in uris) {
            val file = uri.path?.let { File(it) } ?: continue
            if (!file.exists()) continue

            val targetName = file.name
            tree.findFile(targetName)?.takeIf { it.isFile }?.delete()
            val target = tree.createFile("video/mp4", targetName) ?: continue

            val wrote = try {
                context.contentResolver.openOutputStream(target.uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out, COPY_BUFFER_BYTES) }
                } != null
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            if (wrote) savedCount++
        }
        return savedCount
    }

    /**
     * Appends a dedup'd warning when fewer than [totalCount] files were actually saved - including
     * savedCount == 0 (the fallback sub-batch failed completely while an earlier part of the same
     * save succeeded elsewhere, e.g. via the primary next-to-original/replace path): that case must
     * still warn, since callers only treat savedCount == 0 as fatal when nothing else succeeded
     * either, and otherwise silently fall through to saveSuccess = true.
     */
    private fun addPartialSaveWarning(savedCount: Int, totalCount: Int) {
        if (savedCount >= totalCount) return
        val msg = getApplication<Application>().getString(
            R.string.warning_partial_save_failure, totalCount - savedCount, totalCount
        )
        _uiState.update { state ->
            if (state.warnings.contains(msg)) state else state.copy(warnings = (state.warnings + msg).distinct())
        }
    }

    fun toggleRemoveAudio() {
        _uiState.update { 
            val temp = it.copy(removeAudio = !it.removeAudio, activePreset = QualityPreset.CUSTOM)
            if (temp.removeAudio) {
                 temp
            } else {
                 temp.autoAdjust(temp.targetSizeMb)    
            }
        }
    }

    fun setAudioBitrate(bitrate: Int) {
        _uiState.update {
            val temp = it.copy(
                audioBitrate = bitrate,
                audioBitrateLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
            temp.autoAdjust(temp.targetSizeMb, lockAudioBitrate = true)
        }
    }

    fun setAudioVolume(volume: Float) {
        _uiState.update { it.copy(audioVolume = volume, activePreset = QualityPreset.CUSTOM) }
    }

    fun setResolution(height: Int) {
        _uiState.update {
            it.copy(
                targetResolutionHeight = height,
                targetResolutionLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun setFps(fps: Int) {
        _uiState.update {
            it.copy(targetFps = fps, targetFpsLocked = true, activePreset = QualityPreset.CUSTOM)
        }
    }

    fun acceptSuggestedResolution() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                targetResolutionHeight = suggestion.targetResolutionHeight,
                targetResolutionLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun acceptSuggestedFps() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                targetFps = suggestion.targetFps,
                targetFpsLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun acceptSuggestedAudioBitrate() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                audioBitrate = suggestion.audioBitrate,
                audioBitrateLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun acceptAllSuggestions() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                audioBitrate = suggestion.audioBitrate,
                targetResolutionHeight = suggestion.targetResolutionHeight,
                targetFps = suggestion.targetFps,
                audioBitrateLocked = true,
                targetResolutionLocked = true,
                targetFpsLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }
    
    fun cancelCompression() {
        activeTransformer?.cancel()
        activeTransformer = null
        compressionJob?.cancel()
        compressionJob = null
        bgJob?.cancel()
        bgJob = null
        if (_uiState.value.isBackgroundCompression) {
            BackgroundCompressionService.stop(getApplication())
        }
        BackgroundCompressionManager.reset()
        _uiState.update { it.copy(isCompressing = false, isBackgroundCompression = false, progress = 0f) }
    }
    
    private fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val outputDir = File(context.cacheDir, "compressed_videos")
                if (outputDir.exists()) {
                    outputDir.listFiles()?.forEach {
                        try { it.delete() } catch(e: Exception) {}
                    }
                }
            } catch(e: Exception) {
                 e.printStackTrace()
            }
        }
    }

    fun reset() {
        // A save/replace coroutine launched on viewModelScope (saveCompressedOutput/replaceOriginals/
        // undoReplaceOriginal/saveToUri/saveToCustomTree/saveToGallery) does blocking file I/O with no
        // suspension points, so cancelling it wouldn't actually stop it before it finishes and writes
        // its result (saveSuccess/error/pendingReplaceRollback) back to _uiState - which would land on
        // whatever fresh session reset() below just started, corrupting it (e.g. marking an unrelated
        // new compression as already "Saved"). Declining to reset while isSaving is true, matching the
        // same guard saveCompressedOutput/undoReplaceOriginal already use, avoids the race entirely.
        if (_uiState.value.isSaving) return
        val current = _uiState.value
        val savedBytes = current.totalSavedBytes
        val supportedCodecs = current.supportedCodecs
        val supportedAudioCodecs = current.supportedAudioCodecs
        val showBitrate = current.showBitrate
        val useMbps = current.useMbps
        
        bgJob?.cancel()
        bgJob = null
        activeTransformer?.cancel()
        activeTransformer = null
        compressionJob?.cancel()
        compressionJob = null
        if (current.isBackgroundCompression) {
            BackgroundCompressionService.stop(getApplication())
        }
        BackgroundCompressionManager.reset()
        clearCache()

        val defaultCodec = if (supportedCodecs.contains(MimeTypes.VIDEO_H265)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val useH265 = defaultCodec == MimeTypes.VIDEO_H265
        val defaultAudioCodec = if (supportedAudioCodecs.contains(current.defaultAudioConfig.defaultAudioCodec)) {
            current.defaultAudioConfig.defaultAudioCodec
        } else {
            MimeTypes.AUDIO_AAC
        }
        
        _uiState.update {
            CompressorUiState(
                totalSavedBytes = savedBytes,
                supportedCodecs = supportedCodecs,
                supportedAudioCodecs = supportedAudioCodecs,
                showBitrate = showBitrate,
                useMbps = useMbps,
                showStorageSaved = current.showStorageSaved,
                showTargetSizePreset = current.showTargetSizePreset,
                autoSaveToPhotos = current.autoSaveToPhotos,
                backgroundCompressionEnabled = current.backgroundCompressionEnabled,
                backgroundCompressionPrompted = current.backgroundCompressionPrompted,
                customOutputTreeUri = current.customOutputTreeUri,
                customOutputFolderName = current.customOutputFolderName,
                saveNextToOriginal = current.saveNextToOriginal,
                replaceOriginal = current.replaceOriginal,
                pendingReplaceRollback = current.pendingReplaceRollback,
                allCodecsEnabled = current.allCodecsEnabled,
                allCodecsUnlocked = current.allCodecsUnlocked,
                highPresetConfig = current.highPresetConfig,
                mediumPresetConfig = current.mediumPresetConfig,
                lowPresetConfig = current.lowPresetConfig,
                targetSizePresets = current.targetSizePresets,
                defaultVideoConfig = current.defaultVideoConfig,
                defaultAudioConfig = current.defaultAudioConfig,
                videoCodec = defaultCodec,
                useH265 = useH265,
                audioCodec = defaultAudioCodec
            )
        }
    }

    fun startCompression(context: Context) = viewModelScope.launch(Dispatchers.Main) {
        bgJob?.cancel()
        bgJob = null
        BackgroundCompressionManager.reset()

        val currentState = _uiState.value
        val itemsToProcess = if (currentState.isBatchMode) currentState.queue else {
            currentState.selectedUri?.let { listOf(currentState.queue.firstOrNull { q -> q.uri == it } ?: compressedge.joshattic.us.model.QueueItem(
                uri = it,
                originalSize = currentState.originalSize,
                originalWidth = currentState.originalWidth,
                originalHeight = currentState.originalHeight,
                originalBitrate = currentState.originalBitrate,
                originalAudioBitrate = currentState.originalAudioBitrate,
                originalFps = currentState.originalFps,
                originalVideoMime = currentState.originalVideoMime,
                durationMs = currentState.durationMs,
                originalName = currentState.originalName
            )) } ?: emptyList()
        }

        if (itemsToProcess.isEmpty()) return@launch
        
        val warningsAcc = mutableListOf<String>()

        _uiState.update {
            it.copy(
                isCompressing = true,
                isBackgroundCompression = false,
                progress = 0f,
                currentOutputSize = 0L,
                error = null,
                errorLog = null,
                compressedUri = null,
                saveSuccess = false,
                isSaving = false,
                warnings = emptyList()
            )
        }

        var totalSavedThisSession = 0L
        var anyErrors = false
        var lastUri: Uri? = null
        val completedUris = mutableListOf<Uri>()

        val outputDir = File(context.cacheDir, "compressed_videos")
        outputDir.mkdirs()

        compressionJob = viewModelScope.launch(Dispatchers.Main) {
            for ((index, item) in itemsToProcess.withIndex()) {
                if (!isActive) break

                _uiState.update {
                    it.copy(
                        currentlyCompressingIndex = index,
                        currentlyCompressingUri = item.uri
                    )
                }

                val itemRequestedShortSide = item.targetResolutionHeightOverride ?: currentState.targetResolutionHeight
                val itemRequestedFps = item.targetFpsOverride ?: currentState.targetFps

                val plan = withContext(Dispatchers.IO) {
                    val probe = probeTracksCached(context, item.uri)
                    buildCompressionPlan(probe, currentState, itemRequestedShortSide, itemRequestedFps)
                }
                if (plan.blockingError != null) {
                    _uiState.update { it.copy(error = plan.blockingError, errorLog = null, isCompressing = false) }
                    anyErrors = true
                    break
                }

                val (itemSourceTimestamp, itemSourceLocation) = withContext(Dispatchers.IO) {
                    CompressionExecutor.readSourceMp4Metadata(context, item.uri)
                }

                warningsAcc.addAll(plan.warnings)
                _uiState.update { it.copy(warnings = warningsAcc.distinct()) }

                val baseName = item.originalName?.substringBeforeLast(".") ?: "Compressed_${System.currentTimeMillis()}_$index"
                var outputFile = File(outputDir, "${baseName}_Compressed.mp4")
                var counter = 1
                while (outputFile.exists()) {
                    outputFile = File(outputDir, "${baseName}_Compressed ($counter).mp4")
                    counter++
                }
                val outputPath = outputFile.absolutePath

                val itemAudioBitrate = item.audioBitrateOverride ?: currentState.audioBitrate
                val audioBitrateToUse = if (itemAudioBitrate == 0) {
                    if (item.originalAudioBitrate > 0) item.originalAudioBitrate else 128_000
                } else {
                    itemAudioBitrate
                }
                
                val itemVideoMimeType = plan.outputVideoMimeType
                val itemOutputHeight = plan.outputHeight
                val itemOutputFps = plan.outputFps
                
                // Recompute targetBitrate
                val seconds = item.durationMs / 1000.0
                val targetSizePct = item.targetSizePercentageOverride ?: currentState.globalTargetSizePercentage
                val itemOriginalMb = item.originalSize / (1024.0 * 1024.0)
                val targetMbForCalculation = if (currentState.isBatchMode) {
                    (itemOriginalMb * (targetSizePct / 100.0)).coerceAtLeast(0.1)
                } else {
                    currentState.targetSizeMb.toDouble()
                }

                var itemTargetBitrate = currentState.targetBitrate
                if (currentState.isBatchMode) {
                    val targetBits = targetMbForCalculation * 8 * 1024 * 1024
                    val removeAudioItem = item.removeAudioOverride ?: currentState.removeAudio
                    val audioBits = if (removeAudioItem) 0.0 else {
                        val rate = if (itemAudioBitrate == 0) 256_000.0 else itemAudioBitrate.toDouble()
                        rate * seconds
                    }
                    val overheadBits = (targetBits * 0.02) + (50 * 1024 * 8)
                    var availableVideoBits = targetBits - audioBits - overheadBits
                    availableVideoBits = availableVideoBits.coerceAtLeast(targetBits * 0.1)
                    val calculated = if (seconds > 0) (availableVideoBits / seconds).toLong() else 2_000_000L
                    
                    // Min bitrate
                    val origShort = if (item.originalWidth > 0 && item.originalHeight > 0) minOf(item.originalWidth, item.originalHeight) else item.originalHeight
                    val h = if (itemRequestedShortSide > 0 && origShort > 0) minOf(itemRequestedShortSide, origShort) else origShort
                    var base = when {
                        h >= 2160 -> 4_000_000L
                        h >= 1440 -> 2_500_000L
                        h >= 1080 -> 1_500_000L
                        h >= 720 -> 1_000_000L
                        h >= 480 -> 500_000L
                        h >= 360 -> 350_000L
                        else -> 200_000L
                    }
                    if (itemVideoMimeType == MimeTypes.VIDEO_H265) {
                        base = (base * 0.7).toLong()
                    } else if (itemVideoMimeType == MimeTypes.VIDEO_AV1) {
                        base = (base * 0.6).toLong()
                    }
                    val fpsVal = if (itemOutputFps > 0) itemOutputFps.toFloat() else item.originalFps
                    val multiplier = if (fpsVal > 45) 1.5f else 1.0f
                    val minBitrate = (base * multiplier).toLong()
                    
                    val original = if (item.originalBitrate > 0) item.originalBitrate.toLong() else Long.MAX_VALUE
                    itemTargetBitrate = calculated.coerceAtLeast(minBitrate).coerceAtMost(original).toInt()
                }

                val params = CompressionExecutor.Params(
                    inputUri = item.uri,
                    outputPath = outputPath,
                    videoMimeType = itemVideoMimeType,
                    outputHeight = itemOutputHeight,
                    outputFps = itemOutputFps,
                    originalWidth = item.originalWidth,
                    originalHeight = item.originalHeight,
                    originalFps = item.originalFps,
                    targetBitrate = itemTargetBitrate,
                    audioBitrate = audioBitrateToUse,
                    audioCodec = currentState.audioCodec,
                    removeAudio = item.removeAudioOverride ?: currentState.removeAudio,
                    audioVolume = item.audioVolumeOverride ?: currentState.audioVolume,
                    onHdrToneMap = {
                        val warningMsg = getApplication<android.app.Application>().getString(R.string.warning_hdr_tone_mapped)
                        if (!warningsAcc.contains(warningMsg)) {
                            warningsAcc.add(warningMsg)
                            _uiState.update { it.copy(warnings = warningsAcc.toList()) }
                        }
                    },
                    sourceTimestamp = itemSourceTimestamp,
                    sourceLocation = itemSourceLocation
                )

                try {
                    val finalSize = CompressionExecutor.executeSuspend(context, params) { holder, _ ->
                        val overallProgress = (index.toFloat() + (holder.progress / 100f)) / itemsToProcess.size.toFloat()
                        val currentSize = if (outputFile.exists()) outputFile.length() else 0L
                        _uiState.update { it.copy(progress = overallProgress, currentOutputSize = currentSize) }
                    }

                    val savedBytes = item.originalSize - finalSize
                    if (savedBytes > 0) totalSavedThisSession += savedBytes
                    val outputUri = Uri.fromFile(outputFile)
                    lastUri = outputUri
                    completedUris.add(outputUri)

                    if (itemSourceTimestamp != null || itemSourceLocation != null) {
                        val metadataResult = withContext(Dispatchers.IO) {
                            CompressionExecutor.checkMetadataPreservation(itemSourceTimestamp, itemSourceLocation, outputFile.absolutePath)
                        }
                        addMetadataPreservationWarnings(warningsAcc, metadataResult)
                    }
                } catch (e: Exception) {
                    if (e is androidx.media3.transformer.ExportException) {
                        val errorMsg = CompressionExecutor.errorMessage(getApplication<android.app.Application>(), e)
                        _uiState.update {
                            it.copy(
                                isCompressing = false,
                                error = errorMsg,
                                errorLog = e.stackTraceToString()
                            )
                        }
                    } else if (e !is kotlinx.coroutines.CancellationException) {
                        _uiState.update {
                            it.copy(
                                isCompressing = false,
                                error = e.localizedMessage ?: "Unknown Error",
                                errorLog = e.stackTraceToString()
                            )
                        }
                    }
                    anyErrors = true
                    break
                }
            }
            
            if (!anyErrors && isActive) {
                var newTotal = _uiState.value.totalSavedBytes
                if (totalSavedThisSession > 0) {
                    newTotal += totalSavedThisSession
                    prefs.edit { putLong("total_saved_bytes", newTotal) }
                }
                val totalOutputSize = completedUris.sumOf { if (File(it.path!!).exists()) File(it.path!!).length() else 0L }
                _uiState.update {
                    it.copy(
                        isCompressing = false,
                        progress = 1f,
                        compressedUri = lastUri,
                        compressedUris = completedUris,
                        compressedOriginalUris = itemsToProcess.take(completedUris.size).map { item -> item.uri },
                        compressedSize = totalOutputSize,
                        totalSavedBytes = newTotal
                    )
                }
                // Fires only after compressedUri/compressedUris/compressedOriginalUris above are set -
                // saveCompressedOutput() reads that state to know what to save, so calling it any
                // earlier (e.g. per-item, mid-loop) would act on the previous run's stale/empty state
                // instead of what this run just produced. Matches startBackgroundCompression's
                // collector, which updates the same fields before its own auto-save call.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && _uiState.value.autoSaveToPhotos) {
                    saveCompressedOutput(getApplication())
                }
            }
        }
    }

    fun startBackgroundCompression(context: Context) = viewModelScope.launch(Dispatchers.Main) {
        compressionJob?.cancel()
        compressionJob = null
        activeTransformer?.cancel()
        activeTransformer = null
        BackgroundCompressionManager.reset()

        val currentState = _uiState.value
        val itemsToProcess = if (currentState.isBatchMode) currentState.queue else {
            currentState.selectedUri?.let { listOf(currentState.queue.firstOrNull { q -> q.uri == it } ?: compressedge.joshattic.us.model.QueueItem(
                uri = it,
                originalSize = currentState.originalSize,
                originalWidth = currentState.originalWidth,
                originalHeight = currentState.originalHeight,
                originalBitrate = currentState.originalBitrate,
                originalAudioBitrate = currentState.originalAudioBitrate,
                originalFps = currentState.originalFps,
                originalVideoMime = currentState.originalVideoMime,
                durationMs = currentState.durationMs,
                originalName = currentState.originalName
            )) } ?: emptyList()
        }

        if (itemsToProcess.isEmpty()) return@launch

        val warningsAcc = mutableListOf<String>()
        val outputDir = File(context.cacheDir, "compressed_videos")
        outputDir.mkdirs()

        val batchParams = mutableListOf<CompressionExecutor.Params>()
        var totalOriginalSize = 0L

        for ((index, item) in itemsToProcess.withIndex()) {
            val itemRequestedShortSide = item.targetResolutionHeightOverride ?: currentState.targetResolutionHeight
            val itemRequestedFps = item.targetFpsOverride ?: currentState.targetFps

            val plan = withContext(Dispatchers.IO) {
                val probe = probeTracksCached(context, item.uri)
                buildCompressionPlan(probe, currentState, itemRequestedShortSide, itemRequestedFps)
            }
            if (plan.blockingError != null) {
                _uiState.update { it.copy(error = plan.blockingError, errorLog = null, isCompressing = false) }
                return@launch
            }

            val (itemSourceTimestamp, itemSourceLocation) = withContext(Dispatchers.IO) {
                CompressionExecutor.readSourceMp4Metadata(context, item.uri)
            }

            warningsAcc.addAll(plan.warnings)

            val baseName = item.originalName?.substringBeforeLast(".") ?: "Compressed_${System.currentTimeMillis()}_$index"
            var outputFile = File(outputDir, "${baseName}_Compressed.mp4")
            var counter = 1
            while (outputFile.exists()) {
                outputFile = File(outputDir, "${baseName}_Compressed ($counter).mp4")
                counter++
            }
            val outputPath = outputFile.absolutePath

            val itemAudioBitrate = item.audioBitrateOverride ?: currentState.audioBitrate
            val audioBitrateToUse = if (itemAudioBitrate == 0) {
                if (item.originalAudioBitrate > 0) item.originalAudioBitrate else 128_000
            } else {
                itemAudioBitrate
            }

            val itemVideoMimeType = plan.outputVideoMimeType
            val itemOutputHeight = plan.outputHeight
            val itemOutputFps = plan.outputFps

            val seconds = item.durationMs / 1000.0
            val targetSizePct = item.targetSizePercentageOverride ?: currentState.globalTargetSizePercentage
            val itemOriginalMb = item.originalSize / (1024.0 * 1024.0)
            val targetMbForCalculation = if (currentState.isBatchMode) {
                (itemOriginalMb * (targetSizePct / 100.0)).coerceAtLeast(0.1)
            } else {
                currentState.targetSizeMb.toDouble()
            }

            var itemTargetBitrate = currentState.targetBitrate
            if (currentState.isBatchMode) {
                val targetBits = targetMbForCalculation * 8 * 1024 * 1024
                val removeAudioItem = item.removeAudioOverride ?: currentState.removeAudio
                val audioBits = if (removeAudioItem) 0.0 else {
                    val rate = if (itemAudioBitrate == 0) 256_000.0 else itemAudioBitrate.toDouble()
                    rate * seconds
                }
                val overheadBits = (targetBits * 0.02) + (50 * 1024 * 8)
                var availableVideoBits = targetBits - audioBits - overheadBits
                availableVideoBits = availableVideoBits.coerceAtLeast(targetBits * 0.1)
                val calculated = if (seconds > 0) (availableVideoBits / seconds).toLong() else 2_000_000L

                val origShort = if (item.originalWidth > 0 && item.originalHeight > 0) minOf(item.originalWidth, item.originalHeight) else item.originalHeight
                val h = if (itemRequestedShortSide > 0 && origShort > 0) minOf(itemRequestedShortSide, origShort) else origShort
                var base = when {
                    h >= 2160 -> 4_000_000L
                    h >= 1440 -> 2_500_000L
                    h >= 1080 -> 1_500_000L
                    h >= 720 -> 1_000_000L
                    h >= 480 -> 500_000L
                    h >= 360 -> 350_000L
                    else -> 200_000L
                }
                if (itemVideoMimeType == MimeTypes.VIDEO_H265) {
                    base = (base * 0.7).toLong()
                } else if (itemVideoMimeType == MimeTypes.VIDEO_AV1) {
                    base = (base * 0.6).toLong()
                }
                val fpsVal = if (itemOutputFps > 0) itemOutputFps.toFloat() else item.originalFps
                val multiplier = if (fpsVal > 45) 1.5f else 1.0f
                val minBitrate = (base * multiplier).toLong()

                val original = if (item.originalBitrate > 0) item.originalBitrate.toLong() else Long.MAX_VALUE
                itemTargetBitrate = calculated.coerceAtLeast(minBitrate).coerceAtMost(original).toInt()
            }

            totalOriginalSize += item.originalSize

            batchParams.add(
                CompressionExecutor.Params(
                    inputUri = item.uri,
                    outputPath = outputPath,
                    videoMimeType = itemVideoMimeType,
                    outputHeight = itemOutputHeight,
                    outputFps = itemOutputFps,
                    originalWidth = item.originalWidth,
                    originalHeight = item.originalHeight,
                    originalFps = item.originalFps,
                    targetBitrate = itemTargetBitrate,
                    audioBitrate = audioBitrateToUse,
                    audioCodec = currentState.audioCodec,
                    removeAudio = item.removeAudioOverride ?: currentState.removeAudio,
                    audioVolume = item.audioVolumeOverride ?: currentState.audioVolume,
                    onHdrToneMap = {
                        val warningMsg = getApplication<android.app.Application>().getString(R.string.warning_hdr_tone_mapped)
                        BackgroundCompressionManager.setHdrWarning(warningMsg)
                    },
                    sourceTimestamp = itemSourceTimestamp,
                    sourceLocation = itemSourceLocation
                )
            )
        }

        BackgroundCompressionManager.pendingBatch = batchParams

        _uiState.update {
            it.copy(
                isCompressing = true,
                isBackgroundCompression = true,
                progress = 0f,
                currentOutputSize = 0L,
                error = null,
                errorLog = null,
                compressedUri = null,
                saveSuccess = false,
                isSaving = false,
                warnings = warningsAcc.distinct()
            )
        }

        val intent = Intent(context, BackgroundCompressionService::class.java).apply {
            action = BackgroundCompressionService.ACTION_START_BATCH
            putExtra(BackgroundCompressionService.EXTRA_ORIGINAL_SIZE, totalOriginalSize)
        }

        BackgroundCompressionManager.setRunning(totalOriginalSize)
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            e.printStackTrace()
            BackgroundCompressionManager.reset()
            startCompression(context)
            return@launch
        }

        bgJob = viewModelScope.launch {
            BackgroundCompressionManager.state.collect { bg ->
                when {
                    bg.completed && bg.compressedUri != null -> {
                        val savedBytes = currentState.totalOriginalSizeBatch - bg.compressedSize
                        var newTotal = _uiState.value.totalSavedBytes
                        if (savedBytes > 0) {
                            newTotal += savedBytes
                            prefs.edit { putLong("total_saved_bytes", newTotal) }
                        }
                        _uiState.update {
                            it.copy(
                                isCompressing = false,
                                isBackgroundCompression = false,
                                progress = 1f,
                                compressedUri = bg.compressedUri,
                                compressedUris = bg.compressedUris,
                                compressedOriginalUris = itemsToProcess.take(bg.compressedUris.size).map { item -> item.uri },
                                compressedSize = bg.compressedSize,
                                totalSavedBytes = newTotal,
                                // Merged with the plan warnings already set when the batch started
                                // (it.warnings), not replaced - this collector only ever adds the
                                // hdr/metadata warnings discovered during/after compression.
                                warnings = (it.warnings + listOfNotNull(bg.hdrWarning) + bg.metadataWarnings).distinct()
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && _uiState.value.autoSaveToPhotos) {
                            saveCompressedOutput(getApplication())
                        }
                    }
                    bg.completed && bg.error != null -> {
                        _uiState.update {
                            it.copy(
                                isCompressing = false,
                                isBackgroundCompression = false,
                                error = bg.error,
                                errorLog = bg.errorLog
                            )
                        }
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                isCompressing = bg.isRunning,
                                isBackgroundCompression = bg.isRunning,
                                progress = bg.progress,
                                currentOutputSize = bg.outputSize,
                                currentlyCompressingUri = bg.currentlyCompressingUri,
                                currentlyCompressingIndex = bg.currentlyCompressingIndex
                            )
                        }
                    }
                }
            }
        }
    }

    /** Appends a warning for each source metadata field that couldn't be carried into the output. */
    private fun addMetadataPreservationWarnings(
        warningsAcc: MutableList<String>,
        result: CompressionExecutor.MetadataPreservationResult
    ) {
        val app = getApplication<Application>()
        var changed = false
        if (result.timestampPreserved == false) {
            val msg = app.getString(R.string.warning_metadata_date_not_preserved)
            if (!warningsAcc.contains(msg)) {
                warningsAcc.add(msg)
                changed = true
            }
        }
        if (result.locationPreserved == false) {
            val msg = app.getString(R.string.warning_metadata_location_not_preserved)
            if (!warningsAcc.contains(msg)) {
                warningsAcc.add(msg)
                changed = true
            }
        }
        if (changed) {
            _uiState.update { it.copy(warnings = warningsAcc.toList()) }
        }
    }

    private fun probeTracks(context: Context, uri: Uri): TrackProbe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var video: VideoTrackInfo? = null
            var audioMime: String? = null
            var audioBitrate = 0
            var aacProfile = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    video == null && mime.startsWith("video/") -> {
                        val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                        val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                        var frameRate = 0f
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            try {
                                frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                            } catch (e: Exception) {
                                try {
                                    frameRate = format.getFloat(MediaFormat.KEY_FRAME_RATE)
                                } catch (ignored: Exception) {}
                            }
                        }
                        video = VideoTrackInfo(mime, width, height, frameRate)
                    }
                    audioMime == null && mime.startsWith("audio/") -> {
                        audioMime = mime
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            audioBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                        }
                        if (format.containsKey("aac-profile")) {
                            aacProfile = format.getInteger("aac-profile")
                        }
                    }
                }
                if (video != null && audioMime != null) break
            }
            return TrackProbe(video, audioMime, audioBitrate, aacProfile)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }
        return TrackProbe(null, null, 0, -1)
    }

    private fun resolveOutputDimensions(origWidth: Int, origHeight: Int, targetShortSide: Int): Pair<Int, Int> {
        if (origWidth <= 0 || origHeight <= 0 || targetShortSide <= 0) {
            var w = origWidth
            var h = origHeight
            if (w % 2 != 0 && w > 0) w -= 1
            if (h % 2 != 0 && h > 0) h -= 1
            return Pair(w, h)
        }
        val isPortrait = origHeight > origWidth
        val origShort = minOf(origWidth, origHeight)
        if (targetShortSide >= origShort) {
            var w = origWidth
            var h = origHeight
            if (w % 2 != 0 && w > 0) w -= 1
            if (h % 2 != 0 && h > 0) h -= 1
            return Pair(w, h)
        }
        
        return if (isPortrait) {
            val targetW = targetShortSide
            var targetH = (targetW.toLong() * origHeight / origWidth).toInt()
            if (targetH % 2 != 0) targetH -= 1
            var finalW = targetW
            if (finalW % 2 != 0) finalW -= 1
            Pair(finalW, targetH)
        } else {
            val targetH = targetShortSide
            var targetW = (targetH.toLong() * origWidth / origHeight).toInt()
            if (targetW % 2 != 0) targetW -= 1
            var finalH = targetH
            if (finalH % 2 != 0) finalH -= 1
            Pair(targetW, finalH)
        }
    }

    private fun buildCompressionPlan(
        probe: TrackProbe,
        state: CompressorUiState,
        requestedShortSide: Int = state.targetResolutionHeight,
        requestedFps: Int = state.targetFps
    ): CompressionPlan {
        var outputMime = state.videoCodec
        var outputFps = requestedFps
        val warnings = mutableListOf<String>()

        val sourceInfo = probe.video
        val sourceMime = sourceInfo?.mimeType ?: state.originalVideoMime
        val sourceWidth = sourceInfo?.width ?: state.originalWidth
        val sourceHeight = sourceInfo?.height ?: state.originalHeight
        val sourceFps = if ((sourceInfo?.frameRate ?: 0f) > 0f) sourceInfo!!.frameRate else state.originalFps

        if (!sourceMime.isNullOrBlank() && sourceWidth > 0 && sourceHeight > 0) {
            val decoderSupported = isCodecConfigurationSupported(
                mimeType = sourceMime,
                width = sourceWidth,
                height = sourceHeight,
                fps = sourceFps,
                encoder = false
            )
            if (!decoderSupported) {
                val (_, initialHeight) = resolveOutputDimensions(sourceWidth, sourceHeight, requestedShortSide)
                return CompressionPlan(
                    outputVideoMimeType = outputMime,
                    outputHeight = initialHeight,
                    outputFps = outputFps,
                    warnings = warnings,
                    blockingError = getApplication<Application>().getString(
                        R.string.error_decoder_config_unsupported_details,
                        sourceWidth,
                        sourceHeight,
                        sourceFps,
                        sourceMime.substringAfter("/")
                    )
                )
            }
        }

        val attemptedConfigs = mutableListOf<Triple<String, Int, Int>>()
        fun isCurrentOutputSupported(mime: String, outWidth: Int, outHeight: Int, fps: Int): Boolean {
            val safeWidth = (if (outWidth > 0) outWidth else sourceWidth).coerceAtLeast(2)
            val safeHeight = (if (outHeight > 0) outHeight else sourceHeight).coerceAtLeast(2)
            val safeFps = if (fps > 0) fps else sourceFps.toInt()
            var adjWidth = safeWidth
            var adjHeight = safeHeight
            if (adjWidth % 2 != 0) adjWidth -= 1
            if (adjHeight % 2 != 0) adjHeight -= 1
            attemptedConfigs.add(Triple(mime, minOf(adjWidth, adjHeight), safeFps))
            return isCodecConfigurationSupported(
                mimeType = mime,
                width = adjWidth,
                height = adjHeight,
                fps = safeFps.toFloat(),
                encoder = true
            )
        }

        var (currentOutputWidth, currentOutputHeight) = resolveOutputDimensions(sourceWidth, sourceHeight, requestedShortSide)

        if (!isCurrentOutputSupported(outputMime, currentOutputWidth, currentOutputHeight, outputFps)) {
            if (outputMime != MimeTypes.VIDEO_H264 && isCurrentOutputSupported(MimeTypes.VIDEO_H264, currentOutputWidth, currentOutputHeight, outputFps)) {
                outputMime = MimeTypes.VIDEO_H264
                warnings.add(getApplication<Application>().getString(R.string.warning_codec_fallback_h264))
            } else {
                val origShort = if (sourceWidth > 0 && sourceHeight > 0) minOf(sourceWidth, sourceHeight) else sourceHeight
                val fallbackShortSides = listOf(1080, 720, 540, 480, 360, 240)
                    .filter { it in 2..origShort }
                    .ifEmpty { listOf(origShort.coerceAtLeast(2)) }
                val fallbackFps = listOf(30, 24)
                var supported = false

                for (shortCandidate in fallbackShortSides) {
                    val (candWidth, candHeight) = resolveOutputDimensions(sourceWidth, sourceHeight, shortCandidate)
                    for (fpsCandidate in fallbackFps) {
                        if (isCurrentOutputSupported(MimeTypes.VIDEO_H264, candWidth, candHeight, fpsCandidate)) {
                            outputMime = MimeTypes.VIDEO_H264
                            currentOutputWidth = candWidth
                            currentOutputHeight = candHeight
                            outputFps = fpsCandidate
                            warnings.add(
                                getApplication<Application>().getString(
                                    R.string.warning_quality_fallback,
                                    minOf(candWidth, candHeight),
                                    outputFps
                                )
                            )
                            supported = true
                            break
                        }
                    }
                    if (supported) break
                }

                if (!supported) {
                    val attempted = attemptedConfigs
                        .joinToString(separator = ", ") { "${it.first.substringAfter("/")} ${it.second}p@${it.third}fps" }
                    return CompressionPlan(
                        outputVideoMimeType = outputMime,
                        outputHeight = currentOutputHeight,
                        outputFps = outputFps,
                        warnings = warnings,
                        blockingError = getApplication<Application>().getString(
                            R.string.error_encoder_config_unsupported_details,
                            attempted
                        )
                    )
                }
            }
        }

        return CompressionPlan(
            outputVideoMimeType = outputMime,
            outputHeight = currentOutputHeight,
            outputFps = outputFps,
            warnings = warnings,
            blockingError = null
        )
    }

    private fun isCodecConfigurationSupported(
        mimeType: String,
        width: Int,
        height: Int,
        fps: Float,
        encoder: Boolean
    ): Boolean {
        return try {
            val safeFps = kotlin.math.ceil(if (fps > 0f) fps.toDouble() else 30.0)
            cachedCodecInfos
                .asSequence()
                .filter { it.isEncoder == encoder }
                .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
                .any { info ->
                    try {
                        val capabilities = info.getCapabilitiesForType(mimeType)
                        val videoCaps = capabilities.videoCapabilities ?: return@any false
                        videoCaps.areSizeAndRateSupported(width, height, safeFps) ||
                            videoCaps.areSizeAndRateSupported(height, width, safeFps)
                    } catch (_: Exception) {
                        false
                    }
                }
        } catch (_: Exception) {
            false
        }
    }

    fun saveToUri(context: Context, targetUri: Uri) {
        val currentState = _uiState.value
        if (currentState.isSaving) return
        val compressedUri = currentState.compressedUri ?: return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(compressedUri.path!!)
                if (!file.exists()) {
                    _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_file_lost)) }
                    return@launch
                }
                
                context.contentResolver.openOutputStream(targetUri)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out, COPY_BUFFER_BYTES)
                    }
                }
                 _uiState.update { it.copy(saveSuccess = true) }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
                maybeRetrySave(context)
            }
        }
    }

    private fun saveToCustomTree(context: Context, treeUri: Uri, targetUris: List<Uri>) {
        if (targetUris.isEmpty()) return
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val savedCount = saveUrisToCustomTreeBlocking(context, treeUri, targetUris)
                if (savedCount > 0) {
                    addPartialSaveWarning(savedCount, targetUris.size)
                    _uiState.update { it.copy(saveSuccess = true) }
                } else if (_uiState.value.error == null) {
                    // saveUrisToCustomTreeBlocking already sets a specific error for "folder not
                    // writable"; this covers the other savedCount == 0 case - every individual file
                    // write failed even though the tree itself was writable - which otherwise left
                    // isSaving/saveSuccess/error all falling back to their defaults with no feedback.
                    _uiState.update {
                        it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, "No files could be written"))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message))
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
                maybeRetrySave(context)
            }
        }
    }

    fun saveToGallery(context: Context, targetUris: List<Uri>? = null) {
        val currentState = _uiState.value
        if (currentState.isSaving) return
        val uris = targetUris ?: compressedItemsWithOriginals(currentState).map { it.first }
        if (uris.isEmpty()) return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val savedCount = saveUrisToGalleryBlocking(context, uris)
                if (savedCount > 0) {
                    addPartialSaveWarning(savedCount, uris.size)
                    _uiState.update { it.copy(saveSuccess = true) }
                } else {
                    _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_gallery_entry)) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
                maybeRetrySave(context)
            }
        }
    }
}
