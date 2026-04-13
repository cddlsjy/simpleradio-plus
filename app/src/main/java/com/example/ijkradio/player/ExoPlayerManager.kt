package com.example.ijkradio.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asFlow
import com.example.ijkradio.data.Station
import com.example.ijkradio.ui.PlaybackState
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.charset.Charset

/**
 * ExoPlayer 播放器管理器单例类
 * 负责管理 ExoPlayer 的生命周期和播放控制
 * 支持 ICY 元数据编码格式（UTF-8 / GBK / AUTO）
 */
class ExoPlayerManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ExoPlayerManager"

        // 编码类型常量
        const val ENCODING_UTF8 = "UTF-8"
        const val ENCODING_GBK = "GBK"
        const val ENCODING_AUTO = "AUTO"

        // SharedPreferences key for global encoding
        private const val KEY_GLOBAL_ENCODING = "global_encoding"
        private const val PREFS_NAME = "ijk_radio_prefs"

        @Volatile
        private var instance: ExoPlayerManager? = null

        fun getInstance(context: Context): ExoPlayerManager {
            return instance ?: synchronized(this) {
                instance ?: ExoPlayerManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // 播放器实例
    private var exoPlayer: ExoPlayer? = null

    // 当前播放的电台
    private var currentStation: Station? = null

    // 全局编码设置
    private var globalEncoding: String = ENCODING_UTF8

    // SharedPreferences for persisting settings
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 播放状态
    private val _playbackState = MutableLiveData<PlaybackState>(PlaybackState.Stopped)
    val playbackState: Flow<PlaybackState> = _playbackState.asFlow()

    // 元数据流（歌曲信息）
    private val _metadataFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val metadataFlow: SharedFlow<String> = _metadataFlow.asSharedFlow()

    // 音量 (0.0 - 1.0)
    private var currentVolume = 1.0f

    // 硬解码开关
    private var hardwareDecodeEnabled = true

    init {
        // 从 SharedPreferences 读取保存的编码设置
        globalEncoding = prefs.getString(KEY_GLOBAL_ENCODING, ENCODING_UTF8) ?: ENCODING_UTF8
        Log.d(TAG, "Global encoding loaded: $globalEncoding")
    }

    /**
     * 初始化播放器
     */
    fun initialize() {
        try {
            exoPlayer = ExoPlayer.Builder(context)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .build()

            exoPlayer?.addListener(ExoPlayerListener())
            Log.d(TAG, "ExoPlayer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ExoPlayer", e)
            _playbackState.postValue(PlaybackState.Error("播放器初始化失败: ${e.message}"))
        }
    }

    /**
     * 播放电台
     */
    fun playStation(station: Station) {
        Log.d(TAG, "Playing station: ${station.name}, URL: ${station.url}, Global Encoding: $globalEncoding")
        currentStation = station
        _playbackState.postValue(PlaybackState.Buffering)

        try {
            exoPlayer?.let { player ->
                val dataSourceFactory = DefaultDataSourceFactory(
                    context,
                    Util.getUserAgent(context, "IjkRadioPlayer")
                )

                val uri = Uri.parse(station.url)
                val mediaItemBuilder = MediaItem.Builder().setUri(uri)

                // 仅在 API 21+ 设置追帧速度（SDK 19 跳过）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mediaItemBuilder.setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setMaxPlaybackSpeed(1.02f)
                            .build()
                    )
                }
                val mediaItem = mediaItemBuilder.build()

                val mediaSource = when {
                    uri.lastPathSegment?.endsWith(".m3u8", ignoreCase = true) == true ||
                    Util.inferContentType(uri) == C.TYPE_HLS -> {
                        // SDK 19 兼容：不使用 setAllowChunklessPreparation
                        HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(mediaItem)
                    }
                    else -> {
                        ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(mediaItem)
                    }
                }

                player.setMediaSource(mediaSource)
                player.prepare()
                player.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing station", e)
            _playbackState.postValue(PlaybackState.Error("播放失败: ${e.message}"))
        }
    }

    /**
     * 暂停播放
     */
    fun pause() {
        try {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    _playbackState.postValue(PlaybackState.Paused)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing playback", e)
        }
    }

    /**
     * 恢复播放
     */
    fun resume() {
        try {
            exoPlayer?.let { player ->
                player.play()
                currentStation?.let { station ->
                    _playbackState.postValue(PlaybackState.Playing(station.name))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming playback", e)
        }
    }

    /**
     * 停止播放
     */
    fun stop() {
        try {
            exoPlayer?.stop()
            _playbackState.postValue(PlaybackState.Stopped)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        }
    }

    /**
     * 设置音量
     */
    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        try {
            exoPlayer?.volume = currentVolume
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
        }
    }

    /**
     * 设置硬解码
     */
    fun setHardwareDecode(useHardware: Boolean) {
        hardwareDecodeEnabled = useHardware
        // ExoPlayer 会自动处理解码方式，这里仅保存状态
    }

    /**
     * 获取当前全局编码设置
     */
    fun getGlobalEncoding(): String = globalEncoding

    /**
     * 设置全局编码并保存到 SharedPreferences
     */
    fun setGlobalEncoding(encoding: String) {
        globalEncoding = encoding
        prefs.edit().putString(KEY_GLOBAL_ENCODING, encoding).apply()
        Log.d(TAG, "Global encoding set to: $encoding")
    }

    /**
     * 获取当前播放的电台
     */
    fun getCurrentStation(): Station? = currentStation

    /**
     * 检查是否正在播放
     */
    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying == true
    }

    /**
     * 释放播放器资源
     */
    fun release() {
        try {
            exoPlayer?.release()
            exoPlayer = null
            currentStation = null
            _playbackState.postValue(PlaybackState.Stopped)
            Log.d(TAG, "ExoPlayer released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ExoPlayer", e)
        }
    }

    // ==================== 乱码修复函数 ====================

    /**
     * 修复 ICY 元数据中的中文乱码。
     * 使用全局编码设置进行解码：
     * - UTF-8: 直接使用UTF-8解码
     * - GBK: 将ISO-8859-1错误读取的字节按GBK解码
     * - AUTO: 尝试UTF-8，如果失败则尝试GBK
     */
    private fun fixMetadataEncoding(badString: String): String {
        if (badString.isBlank()) return badString

        val bytes = badString.toByteArray(Charsets.ISO_8859_1)

        return when (globalEncoding) {
            ENCODING_UTF8 -> {
                try {
                    String(bytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    badString
                }
            }
            ENCODING_GBK -> {
                try {
                    String(bytes, Charset.forName("GBK"))
                } catch (e: Exception) {
                    badString
                }
            }
            ENCODING_AUTO -> {
                // 自动检测：先尝试UTF-8，如果包含乱码字符则回退GBK
                try {
                    val utf8 = String(bytes, Charsets.UTF_8)
                    if (!utf8.contains('\uFFFD')) utf8 else String(bytes, Charset.forName("GBK"))
                } catch (e: Exception) {
                    badString
                }
            }
            else -> {
                // 默认使用UTF-8
                try {
                    String(bytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    badString
                }
            }
        }
    }

    // ==================== 内部监听器实现 ====================

    private inner class ExoPlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    Log.d(TAG, "Buffering")
                    _playbackState.postValue(PlaybackState.Buffering)
                }
                Player.STATE_READY -> {
                    Log.d(TAG, "Ready")
                    currentStation?.let {
                        _playbackState.postValue(PlaybackState.Playing(it.name))
                    }
                }
                Player.STATE_ENDED -> {
                    Log.d(TAG, "Ended")
                    _playbackState.postValue(PlaybackState.Stopped)
                }
                Player.STATE_IDLE -> {
                    Log.d(TAG, "Idle")
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}")
            _playbackState.postValue(PlaybackState.Error(error.message ?: "未知错误"))
        }

        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata[i]
                if (entry is IcyInfo) {
                    val rawTitle = entry.title ?: ""
                    val fixed = fixMetadataEncoding(rawTitle)
                    if (fixed.isNotBlank()) {
                        Log.d(TAG, "Metadata: $fixed (encoding: $globalEncoding)")
                        _metadataFlow.tryEmit(fixed)
                    }
                }
            }
        }
    }
}
