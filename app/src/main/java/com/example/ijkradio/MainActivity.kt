package com.example.ijkradio

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.ItemTouchHelper
import com.example.ijkradio.data.Station
import com.example.ijkradio.data.StationStorage
import com.example.ijkradio.player.ExoPlayerManager
import com.example.ijkradio.ui.PlaybackState
import com.example.ijkradio.ui.StationAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider

/**
 * 主界面Activity
 * 管理电台列表、播放器控制和UI交互
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // UI组件
    private lateinit var recyclerView: RecyclerView
    private lateinit var playPauseButton: FloatingActionButton
    private lateinit var settingsButton: ImageButton
    private lateinit var statusTextView: TextView
    private lateinit var songTitleTextView: TextView
    private lateinit var emptyView: TextView
    private lateinit var volumeSlider: Slider
    private lateinit var volumeIcon: ImageView

    // 适配器
    private lateinit var stationAdapter: StationAdapter

    // 数据和播放器
    private lateinit var stationStorage: StationStorage
    private lateinit var playerManager: ExoPlayerManager

    // 电台列表
    private var stations: MutableList<Station> = mutableListOf()
    private var selectedStation: Station? = null
    private var autoPlayEnabled = true
    private var autoPlayLastStationEnabled = true

    // 全局编码选项
    private val encodingOptions = arrayOf("UTF-8", "GBK", "AUTO")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "onCreate: Initializing activity")

        initStorage()
        initViews()
        initPlayer()
        initRecyclerView()
        setupListeners()
        loadStations()
        restoreLastPlayed()
    }

    /**
     * 初始化UI组件
     */
    private fun initViews() {
        recyclerView = findViewById(R.id.stations_recycler_view)
        playPauseButton = findViewById(R.id.play_pause_button)
        settingsButton = findViewById(R.id.settings_button)
        statusTextView = findViewById(R.id.status_text_view)
        songTitleTextView = findViewById(R.id.song_title_text_view)
        emptyView = findViewById(R.id.empty_view)
    }

    /**
     * 初始化存储
     */
    private fun initStorage() {
        stationStorage = StationStorage(this)
    }

    /**
     * 初始化播放器（仅 ExoPlayer）
     */
    private fun initPlayer() {
        playerManager = ExoPlayerManager.getInstance(this)
        playerManager.initialize()
        playerManager.setVolume(stationStorage.getVolume())
        playerManager.setHardwareDecode(stationStorage.getUseHardwareDecode())
    }

    /**
     * 初始化RecyclerView
     */
    private fun initRecyclerView() {
        stationAdapter = StationAdapter(
            onStationClick = { station -> onStationClicked(station) },
            onDeleteClick = { station -> showDeleteDialog(station) }
        )

        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = stationAdapter
            setHasFixedSize(true)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val station = stationAdapter.getItemAt(position)
                if (station != null) {
                    showDeleteDialog(station)
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    /**
     * 设置监听器
     */
    private fun setupListeners() {
        playPauseButton.setOnClickListener {
            togglePlayPause()
        }

        findViewById<ImageButton>(R.id.add_station_button).setOnClickListener {
            showAddStationDialog()
        }

        settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        lifecycleScope.launch {
            playerManager.playbackState.collect { state ->
                updatePlaybackUI(state)
            }
        }

        lifecycleScope.launch {
            playerManager.metadataFlow.collect { metadata ->
                songTitleTextView.text = metadata
                songTitleTextView.visibility = if (metadata.isNotBlank()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * 加载电台列表
     */
    private fun loadStations() {
        stations = stationStorage.getStations().toMutableList()
        stationAdapter.submitList(stations.toList())
        updateEmptyView()
    }

    /**
     * 恢复上次播放
     */
    private fun restoreLastPlayed() {
        val lastPlayed = stationStorage.getLastPlayed()
        if (lastPlayed != null) {
            selectedStation = lastPlayed
            stationAdapter.setSelectedStation(lastPlayed)
            if (autoPlayLastStationEnabled) {
                playerManager.playStation(lastPlayed)
            }
        }
    }

    /**
     * 电台点击事件
     */
    private fun onStationClicked(station: Station) {
        Log.d(TAG, "Station clicked: ${station.name}")
        selectedStation = station
        stationAdapter.setSelectedStation(station)
        stationStorage.saveLastPlayed(station)

        val currentStation = playerManager.getCurrentStation()
        if (currentStation?.id != station.id) {
            playerManager.playStation(station)
        }
    }

    /**
     * 切换播放/暂停
     */
    private fun togglePlayPause() {
        val currentStation = selectedStation ?: return

        when {
            playerManager.isPlaying() -> {
                playerManager.pause()
            }
            playerManager.getCurrentStation()?.id == currentStation.id -> {
                playerManager.resume()
            }
            else -> {
                playerManager.playStation(currentStation)
            }
        }
    }

    /**
     * 更新播放UI
     */
    private fun updatePlaybackUI(state: PlaybackState) {
        when (state) {
            is PlaybackState.Stopped -> {
                statusTextView.text = getString(R.string.status_stopped)
                playPauseButton.setImageResource(R.drawable.ic_play)
                stationAdapter.setPlayingStation(null)
                songTitleTextView.text = ""
                songTitleTextView.visibility = View.GONE
            }
            is PlaybackState.Buffering -> {
                statusTextView.text = getString(R.string.status_buffering)
                playPauseButton.setImageResource(R.drawable.ic_pause)
            }
            is PlaybackState.Playing -> {
                statusTextView.text = state.stationName
                playPauseButton.setImageResource(R.drawable.ic_pause)
                val station = stations.find { it.name == state.stationName }
                stationAdapter.setPlayingStation(station)
            }
            is PlaybackState.Paused -> {
                statusTextView.text = getString(R.string.status_paused)
                playPauseButton.setImageResource(R.drawable.ic_play)
            }
            is PlaybackState.Error -> {
                statusTextView.text = getString(R.string.status_error, state.message)
                playPauseButton.setImageResource(R.drawable.ic_play)
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 显示添加电台对话框
     */
    private fun showAddStationDialog() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_station, null)

        val nameInput = dialogView.findViewById<EditText>(R.id.station_name_input)
        val urlInput = dialogView.findViewById<EditText>(R.id.station_url_input)
        val descriptionInput = dialogView.findViewById<EditText>(R.id.station_description_input)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_station_title)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_add) { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                val description = descriptionInput.text.toString().trim()

                if (name.isNotEmpty() && url.isNotEmpty()) {
                    val station = Station(
                        name = name,
                        url = if (url.startsWith("http")) url else "http://$url",
                        description = description
                    )
                    addStation(station)
                } else {
                    Toast.makeText(this, R.string.error_invalid_input, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 显示删除确认对话框
     */
    private fun showDeleteDialog(station: Station) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, station.name))
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                deleteStation(station)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 添加电台
     */
    private fun addStation(station: Station) {
        if (station.isValid()) {
            stationStorage.addStation(station)
            loadStations()
            Toast.makeText(this, R.string.station_added, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.error_invalid_station, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 删除电台
     */
    private fun deleteStation(station: Station) {
        if (playerManager.getCurrentStation()?.id == station.id) {
            playerManager.stop()
        }

        if (selectedStation?.id == station.id) {
            selectedStation = null
        }

        stationStorage.removeStation(station)
        loadStations()
        Toast.makeText(this, R.string.station_deleted, Toast.LENGTH_SHORT).show()
    }

    /**
     * 更新空状态视图
     */
    private fun updateEmptyView() {
        if (stations.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        playerManager.getCurrentStation()?.let {
            stationStorage.saveLastPlayed(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::playerManager.isInitialized) {
            playerManager.release()
        }
    }

    /**
     * 显示设置对话框
     */
    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_settings, null)

        volumeSlider = dialogView.findViewById<Slider>(R.id.volume_slider)
        volumeIcon = dialogView.findViewById<ImageView>(R.id.volume_icon)
        val radioHardware = dialogView.findViewById<RadioButton>(R.id.radio_hardware)
        val radioSoftware = dialogView.findViewById<RadioButton>(R.id.radio_software)
        val autoPlaySwitch = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.auto_play_switch)
        val autoPlayLastStationSwitch = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.auto_play_last_station_switch)

        // 全局编码选择器
        val encodingSpinner = dialogView.findViewById<Spinner>(R.id.encoding_spinner)

        // 初始化全局编码选择器
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, encodingOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        encodingSpinner.adapter = adapter

        // 设置当前全局编码
        val currentEncoding = playerManager.getGlobalEncoding()
        val encodingIndex = encodingOptions.indexOf(currentEncoding)
        if (encodingIndex >= 0) {
            encodingSpinner.setSelection(encodingIndex)
        }

        // 监听全局编码选择变化
        encodingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedEncoding = encodingOptions[position]
                playerManager.setGlobalEncoding(selectedEncoding)
                Log.d(TAG, "Global encoding changed to: $selectedEncoding")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // 不做任何操作
            }
        }

        // 音量
        volumeSlider.value = stationStorage.getVolume()
        updateVolumeIcon(stationStorage.getVolume())

        // 解码方式
        val useHardware = stationStorage.getUseHardwareDecode()
        radioHardware.isChecked = useHardware
        radioSoftware.isChecked = !useHardware

        // 自动播放开关
        autoPlaySwitch.isChecked = autoPlayEnabled
        autoPlayLastStationSwitch.isChecked = autoPlayLastStationEnabled

        volumeSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (fromUser) {
                playerManager.setVolume(value)
                stationStorage.saveVolume(value)
                updateVolumeIcon(value)
            }
        }

        autoPlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            autoPlayEnabled = isChecked
        }

        autoPlayLastStationSwitch.setOnCheckedChangeListener { _, isChecked ->
            autoPlayLastStationEnabled = isChecked
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val useHardwareDecode = radioHardware.isChecked

                val volume = volumeSlider.value
                playerManager.setVolume(volume)
                stationStorage.saveVolume(volume)

                stationStorage.saveUseHardwareDecode(useHardwareDecode)
                playerManager.setHardwareDecode(useHardwareDecode)

                Log.d(TAG, "Settings saved. Global encoding: ${playerManager.getGlobalEncoding()}")
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(resources.getColor(R.color.colorPrimary))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(resources.getColor(R.color.colorPrimary))
        }

        dialog.show()
    }

    /**
     * 更新音量图标
     */
    private fun updateVolumeIcon(volume: Float) {
        val iconRes = when {
            volume <= 0f -> R.drawable.ic_volume_off
            volume < 0.5f -> R.drawable.ic_volume_down
            else -> R.drawable.ic_volume_up
        }
        volumeIcon.setImageResource(iconRes)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1)
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(1)
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                selectedStation?.let {
                    if (playerManager.isPlaying() && playerManager.getCurrentStation()?.id == it.id) {
                        playerManager.pause()
                    } else {
                        playStationAndUpdateUI(it)
                    }
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveSelection(delta: Int) {
        val count = stationAdapter.itemCount
        if (count == 0) return

        val currentPos = if (selectedStation == null) {
            if (delta > 0) -1 else count
        } else {
            stations.indexOfFirst { it.id == selectedStation?.id }.coerceAtLeast(0)
        }

        val newPos = ((currentPos + delta) % count + count) % count
        val station = stationAdapter.getItemAt(newPos)
        stationAdapter.setSelectedStation(station)
        recyclerView.smoothScrollToPosition(newPos)
        if (autoPlayEnabled && station != null) {
            playStationAndUpdateUI(station)
        }
    }

    private fun playStationAndUpdateUI(station: Station) {
        onStationClicked(station)
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
        super.onBackPressed()
    }
}
