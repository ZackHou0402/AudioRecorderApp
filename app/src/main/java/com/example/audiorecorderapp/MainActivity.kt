package com.example.audiorecorderapp

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var btnBluetooth: Button
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFilePath: String = ""
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false
    private var outputFile: String = ""
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var selectedBluetoothDevice: BluetoothDevice? = null
    private val TAG = "AudioRecorderApp"
    private lateinit var audioManager: AudioManager
    private var isBluetoothScoOn = false
    private var isScoConnected = false
    private val PERMISSION_REQUEST_CODE = 1000  // 權限請求碼

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        btnPlay = findViewById(R.id.btnPlay)
        tvStatus = findViewById(R.id.tvStatus)
        listView = findViewById(R.id.listView)
        btnBluetooth = findViewById(R.id.btnBluetooth)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initializeRecordingFilePath()

        if (!checkAndRequestPermissions()) {
            Log.e(TAG, "Permissions not granted.")
            return
        }

        btnRecord.setOnClickListener {
            if (checkAndRequestPermissions()) {
                CoroutineScope(Dispatchers.Main).launch {
                    if (isRecording) {
                        stopRecording()
                    } else {
                        startRecording()
                    }
                }
            } else {
                Log.e(TAG, "Required permissions are not granted.")
            }
        }

        btnPlay.setOnClickListener {
            if (checkAndRequestPermissions()) {
                CoroutineScope(Dispatchers.IO).launch {
                    playRecording()
                }
            } else {
                Log.e(TAG, "Required permissions are not granted.")
            }
        }

        btnBluetooth.setOnClickListener {
            Log.d(TAG, "Bluetooth button clicked.")
            if (checkAndRequestPermissions()) {
                showBluetoothDevices()
            } else {
                Log.d(TAG, "Bluetooth permissions are required.")
            }
        }

        setupBluetooth()
        btnPlay.visibility = View.GONE
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }

    private fun initializeRecordingFilePath() {
        val musicDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        if (musicDir != null) {
            if (!musicDir.exists()) {
                musicDir.mkdirs()
            }
            recordingFilePath = "${musicDir.absolutePath}/audio_${System.currentTimeMillis()}.3gp"
            Log.d(TAG, "Recording file path initialized: $recordingFilePath")
        } else {
            Log.e(TAG, "Music directory is null.")
        }
    }

    private fun startBluetoothScoConnection() {
        // 檢查 MODIFY_AUDIO_SETTINGS 權限
        if (!checkPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS)) {
            Log.e(TAG, "MODIFY_AUDIO_SETTINGS permission is required.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (!isBluetoothScoOn) {
                Log.d(TAG, "Starting Bluetooth SCO...")

                try {
                    audioManager.startBluetoothSco()  // 啟動 SCO
                    audioManager.isBluetoothScoOn = true
                    isBluetoothScoOn = true
                    Log.d(TAG, "Bluetooth SCO started.")

                    // 設置 5 秒內重試 SCO 連接
                    var retries = 0
                    while (!isScoConnected && retries < 50) {  // 每 100 毫秒檢查一次，最多重試 5 秒
                        Log.d(TAG, "Waiting for SCO connection... Retry: $retries")
                        kotlinx.coroutines.delay(100)  // 每次等待 100 毫秒
                        retries++
                    }

                    if (isScoConnected) {
                        Log.d(TAG, "Bluetooth SCO connected successfully.")
                    } else {
                        Log.e(TAG, "Failed to establish Bluetooth SCO after retries.")
                        stopBluetoothScoConnection()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting Bluetooth SCO: ${e.message}")
                    stopBluetoothScoConnection()
                }
            } else {
                Log.d(TAG, "Bluetooth SCO is already started.")
            }
        }
    }






    private fun stopBluetoothScoConnection() {
        if (isBluetoothScoOn) {
            Log.d(TAG, "Stopping Bluetooth SCO...")
            try {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                isBluetoothScoOn = false
                isScoConnected = false
                Log.d(TAG, "Bluetooth SCO stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Bluetooth SCO: ${e.message}")
            }
        }
    }



    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                when (scoState) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        isScoConnected = true
                        Log.d(TAG, "SCO audio connected, starting recording with Bluetooth microphone.")

                        // 確保在 SCO 連接成功後調用錄音
                        startRecordingWithBluetoothMic()  // 新增：SCO 連接成功後開始錄音
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        isScoConnected = false
                        Log.d(TAG, "SCO audio disconnected.")
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        Log.e(TAG, "SCO audio connection error.")
                    }
                    else -> {
                        Log.d(TAG, "SCO state updated, state: $scoState")
                    }
                }
            }
        }
    }



    override fun onStart() {
        super.onStart()
        val scoIntentFilter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        registerReceiver(scoReceiver, scoIntentFilter)
        Log.d(TAG, "Registered SCO broadcast receiver.")
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(scoReceiver)
        Log.d(TAG, "Unregistered SCO broadcast receiver.")
    }

    private fun startRecording() {
        if (isRecording) return

        if (!checkPermission(Manifest.permission.RECORD_AUDIO)) {
            Log.d(TAG, "Microphone permission is required.")
            return
        }

        if (bluetoothAdapter?.isEnabled == true && isHeadsetConnected()) {
            Log.d(TAG, "Bluetooth headset connected, starting SCO.")
            startBluetoothScoConnection()  // 等待 SCO 連接後再開始錄音
        } else {
            Log.d(TAG, "No connected Bluetooth headset or Bluetooth is disabled.")
            // 禁止錄音，顯示錯誤信息
            tvStatus.text = "Bluetooth headset not connected or unavailable. Cannot record."
        }
    }

    private fun startRecordingWithBluetoothMic() {
        if (!isScoConnected) {
            Log.d(TAG, "SCO connection not established yet.")
            return
        }

        try {
            // 強制使用 VOICE_COMMUNICATION 作為音源
            setupMediaRecorder(useBluetoothMic = true)
            mediaRecorder?.start()
            isRecording = true
            updateUIForRecording()
            Log.d(TAG, "Recording started using Bluetooth microphone.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}")
        }
    }

    private fun setupMediaRecorder(useBluetoothMic: Boolean = false) {
        mediaRecorder = MediaRecorder().apply {
            val audioSource = if (useBluetoothMic) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION  // 強制使用 Bluetooth 麥克風
            } else {
                Log.d(TAG, "Bluetooth mic not available, cannot record.")
                return@apply
            }

            setAudioSource(audioSource)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(recordingFilePath)

            try {
                prepare()
                Log.d(TAG, "MediaRecorder prepared successfully.")
            } catch (e: IOException) {
                Log.e(TAG, "MediaRecorder preparation failed: ${e.message}")
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        CoroutineScope(Dispatchers.Main).launch {
            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                    mediaRecorder = null
                    isRecording = false
                    outputFile = recordingFilePath
                }
                stopBluetoothScoConnection()
                updateUIAfterStopRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording: ${e.message}")
            }
        }
    }

    private fun updateUIForRecording() {
        runOnUiThread {
            btnRecord.text = "Stop Recording"
            tvStatus.text = "Status: Recording"
            btnPlay.visibility = View.GONE
        }
    }

    private fun updateUIAfterStopRecording() {
        runOnUiThread {
            btnRecord.text = "Start Recording"
            tvStatus.text = "Status: Recording stopped"
            btnPlay.visibility = View.VISIBLE
        }
    }

    private suspend fun playRecording() {
        if (outputFile.isEmpty()) {
            Log.d(TAG, "No recording found!")
            return
        }

        withContext(Dispatchers.Main) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(outputFile)
                    prepare()
                    start()
                    setOnCompletionListener {
                        releaseMediaPlayer()
                        btnPlay.visibility = View.GONE
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error playing recording: ${e.message}")
            }
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun checkAndRequestPermissions(): Boolean {
        val requiredPermissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.MODIFY_AUDIO_SETTINGS // 新增的權限
        )

        val permissionsNeeded = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
            return false
        }
        return true
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val deniedPermissions = grantResults.indices.filter { grantResults[it] != PackageManager.PERMISSION_GRANTED }
            if (deniedPermissions.isNotEmpty()) {
                Log.e(TAG, "Some permissions were denied.")
                // Handle the case where the user denies some permissions
            } else {
                Log.d(TAG, "All required permissions granted.")
            }
        }
    }


    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth is not supported on this device.")
            return
        }

        if (checkPermission(Manifest.permission.BLUETOOTH) && checkPermission(Manifest.permission.BLUETOOTH_ADMIN)) {
            if (bluetoothAdapter!!.isEnabled) {
                Log.d(TAG, "Bluetooth is enabled.")
                bluetoothAdapter!!.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, headset: BluetoothProfile) {
                        bluetoothHeadset = headset as BluetoothHeadset
                        Log.d(TAG, "Bluetooth headset service connected.")
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        bluetoothHeadset = null
                        Log.d(TAG, "Bluetooth headset service disconnected.")
                    }
                }, BluetoothProfile.HEADSET)
            } else {
                Log.d(TAG, "Please enable Bluetooth.")
            }
        } else {
            Log.e(TAG, "Bluetooth permissions are required.")
        }
    }

    private fun showBluetoothDevices() {
        if (!checkPermission(Manifest.permission.BLUETOOTH) || !checkPermission(Manifest.permission.BLUETOOTH_ADMIN)) {
            Log.e(TAG, "Bluetooth permissions are required.")
            openAppSettings()
            return
        }

        if (bluetoothHeadset == null) {
            Log.e(TAG, "Bluetooth headset is not connected.")
            return
        }

        try {
            val devices = bluetoothHeadset!!.connectedDevices
            if (devices.isNotEmpty()) {
                val deviceNames = devices.map { it.name }.toTypedArray()
                val builder = AlertDialog.Builder(this)
                builder.setTitle("Select Bluetooth Device")
                    .setItems(deviceNames) { dialog, which ->
                        selectedBluetoothDevice = devices[which]
                        Log.d(TAG, "Selected Bluetooth device: ${selectedBluetoothDevice?.name}")
                    }
                    .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                    .show()
            } else {
                Log.d(TAG, "No connected Bluetooth devices found.")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}. Permission may be denied.")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing Bluetooth devices: ${e.message}")
        }
    }

    private fun isHeadsetConnected(): Boolean {
        if (!checkPermission(Manifest.permission.BLUETOOTH) || !checkPermission(Manifest.permission.BLUETOOTH_ADMIN)) {
            Log.e(TAG, "Bluetooth permissions are required.")
            return false
        }

        return try {
            val devices = bluetoothHeadset?.connectedDevices ?: return false
            devices.isNotEmpty().also { isConnected ->
                Log.d(TAG, "Bluetooth headset connection status: $isConnected")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}. Permission may be denied.")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking headset connection: ${e.message}")
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothHeadset?.let {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
        }
        mediaRecorder?.release()
        mediaRecorder = null
        mediaPlayer?.release()
        mediaPlayer = null
        stopBluetoothScoConnection()
    }
}
