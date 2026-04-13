package com.example.ijkradio.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 电台存储管理器
 * 使用 SharedPreferences 存储电台列表和播放状态
 * 首次运行时从 assets/stations.json 加载默认电台
 */
class StationStorage(private val context: Context) {

    companion object {
        private const val TAG = "StationStorage"
        private const val PREFS_NAME = "ijk_radio_prefs"
        private const val KEY_STATIONS = "stations"
        private const val KEY_LAST_PLAYED_ID = "last_played_id"
        private const val KEY_LAST_VOLUME = "last_volume"
        private const val KEY_LAST_POSITION = "last_position"
        private const val KEY_USE_HARDWARE_DECODE = "use_hardware_decode"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * 保存电台列表（包含编码设置）
     */
    fun saveStations(stations: List<Station>) {
        val json = gson.toJson(stations)
        prefs.edit().putString(KEY_STATIONS, json).apply()
        Log.d(TAG, "Saved ${stations.size} stations")
    }

    /**
     * 获取电台列表
     */
    fun getStations(): List<Station> {
        val json = prefs.getString(KEY_STATIONS, null)
        val stations = if (json != null) {
            try {
                val type = object : TypeToken<List<Station>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse stations from SharedPreferences", e)
                emptyList()
            }
        } else {
            // 首次运行：从 assets/stations.json 加载默认电台
            val defaultStations = loadDefaultStationsFromAssets()
            if (defaultStations.isNotEmpty()) {
                saveStations(defaultStations)
                Log.d(TAG, "Loaded ${defaultStations.size} stations from assets/stations.json")
            }
            defaultStations
        }
        return stations
    }

    /**
     * 从 assets/stations.json 加载默认电台列表
     * 为没有有效 ID 的电台生成基于 URL 的稳定 ID
     */
    private fun loadDefaultStationsFromAssets(): List<Station> {
        return try {
            val jsonString = context.assets.open("stations.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<Station>>() {}.type
            val stations: List<Station> = gson.fromJson(jsonString, type)

            // 为没有有效 ID 的电台生成稳定的 ID
            stations.map { station ->
                if (station.id.isEmpty() || station.id.length != 36) {
                    // 根据 URL 生成稳定的 ID (使用 UUID.nameUUIDFromBytes)
                    val stableId = java.util.UUID.nameUUIDFromBytes(station.url.toByteArray()).toString()
                    Log.d(TAG, "Generated stable ID for ${station.name}: $stableId")
                    station.copy(id = stableId)
                } else {
                    station
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load stations from assets/stations.json", e)
            emptyList()
        }
    }

    /**
     * 添加电台
     */
    fun addStation(station: Station) {
        val stations = getStations().toMutableList()
        // 检查是否已存在同名电台
        if (stations.none { it.name == station.name && it.url == station.url }) {
            stations.add(station)
            saveStations(stations)
        }
    }

    /**
     * 删除电台
     */
    fun removeStation(station: Station) {
        val stations = getStations().toMutableList()
        val iterator = stations.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().id == station.id) {
                iterator.remove()
                break
            }
        }
        saveStations(stations)
    }

    /**
     * 删除电台 by ID
     */
    fun removeStationById(stationId: String) {
        val stations = getStations().toMutableList()
        val iterator = stations.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().id == stationId) {
                iterator.remove()
                break
            }
        }
        saveStations(stations)
    }

    /**
     * 更新电台（包含编码设置）
     */
    fun updateStation(station: Station) {
        val stations = getStations().toMutableList()
        val index = stations.indexOfFirst { it.id == station.id }
        if (index != -1) {
            stations[index] = station
            saveStations(stations)
        }
    }

    /**
     * 保存上次播放的电台ID
     */
    fun saveLastPlayed(station: Station) {
        prefs.edit()
            .putString(KEY_LAST_PLAYED_ID, station.id)
            .apply()
    }

    /**
     * 获取上次播放的电台ID
     */
    fun getLastPlayedId(): String? {
        return prefs.getString(KEY_LAST_PLAYED_ID, null)
    }

    /**
     * 获取上次播放的电台
     */
    fun getLastPlayed(): Station? {
        val lastId = getLastPlayedId() ?: return null
        return getStations().find { it.id == lastId }
    }

    /**
     * 保存音量
     */
    fun saveVolume(volume: Float) {
        prefs.edit().putFloat(KEY_LAST_VOLUME, volume).apply()
    }

    /**
     * 获取音量
     */
    fun getVolume(): Float {
        return prefs.getFloat(KEY_LAST_VOLUME, 1.0f)
    }

    /**
     * 保存播放位置（毫秒）
     */
    fun savePosition(position: Long) {
        prefs.edit().putLong(KEY_LAST_POSITION, position).apply()
    }

    /**
     * 获取播放位置（毫秒）
     */
    fun getPosition(): Long {
        return prefs.getLong(KEY_LAST_POSITION, 0L)
    }

    /**
     * 清空所有数据
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * 重置为默认电台（从 assets/stations.json 重新加载）
     */
    fun resetToDefault() {
        val defaultStations = loadDefaultStationsFromAssets()
        saveStations(defaultStations)
        Log.d(TAG, "Reset to default stations: ${defaultStations.size} stations")
    }

    /**
     * 检查是否为首次运行
     */
    fun isFirstRun(): Boolean {
        return !prefs.contains(KEY_STATIONS)
    }

    /**
     * 标记首次运行已完成
     */
    fun markFirstRunComplete() {
        // 首次运行后会保存电台数据，所以只需检查KEY_STATIONS即可
    }

    /**
     * 保存硬解码设置
     */
    fun saveUseHardwareDecode(useHardware: Boolean) {
        prefs.edit().putBoolean(KEY_USE_HARDWARE_DECODE, useHardware).apply()
    }

    /**
     * 获取硬解码设置（默认 true）
     */
    fun getUseHardwareDecode(): Boolean {
        return prefs.getBoolean(KEY_USE_HARDWARE_DECODE, true)
    }
}
