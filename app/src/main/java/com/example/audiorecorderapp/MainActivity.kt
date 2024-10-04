package com.example.audiorecorderapp
import kotlinx.coroutines.delay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var btnBluetooth: Button
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFilePath: String = "" // Initialize recording file path

    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false
    private var outputFile: String = ""
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var selectedBluetoothDevice: BluetoothDevice? = null
    private lateinit var audioManager: AudioManager

    private val BLUETOOTH_PERMISSION_REQUEST = 1001
    private val TAG = "AudioRecorderApp"
    private fun setupMediaRecorder() {
        Log.d(TAG, "Setting up MediaRecorder with file path: $recordingFilePath")
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(recordingFilePath)
            try {
                prepare()
                Log.d(TAG, "MediaRecorder prepared successfully.")
            } catch (e: IOException) {
                Log.e(TAG, "Error preparing MediaRecorder: ${e.message}")
            }
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        btnPlay = findViewById(R.id.btnPlay)
        tvStatus = findViewById(R.id.tvStatus)
        listView = findViewById(R.id.listView)
        btnBluetooth = findViewById(R.id.btnBluetooth)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        initializeRecordingFilePath()

        if (!checkAndRequestPermissions()) {
            return
        }

        btnRecord.setOnClickListener {
            Log.d(TAG, "Button clicked, current isRecording: $isRecording") // 在這裡添加日誌

            CoroutineScope(Dispatchers.Main).launch {
                if (isRecording) {
                    stopRecording() // Call to stop recording
                } else {
                    startRecording() // Call to start recording
                }
            }
        }



        btnPlay.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                playRecording()
            }
        }

        btnBluetooth.setOnClickListener {
            showBluetoothDevices()
        }

        setupBluetooth()
        registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED))

        btnPlay.visibility = View.GONE
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissionsNeeded = mutableListOf<String>()

        listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).forEach { permission ->
            if (!checkPermission(permission)) {
                permissionsNeeded.add(permission)
            }
        }

        return if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), BLUETOOTH_PERMISSION_REQUEST)
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            BLUETOOTH_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    setupBluetooth()
                } else {
                    Toast.makeText(this, "Bluetooth permissions are required for this feature", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun initializeRecordingFilePath() {
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val musicDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            musicDir?.mkdirs() // Create the directory if it doesn't exist
            recordingFilePath = "${musicDir?.absolutePath}/audio_${System.currentTimeMillis()}.3gp"
            Log.d(TAG, "Recording file path initialized: $recordingFilePath") // Log for debugging
        } else {
            Log.e(TAG, "External storage is not available or writable")
        }
    }


    private fun startRecording() {
        Log.d(TAG, "isRecording before start: $isRecording")

        if (isRecording) {
            Log.d(TAG, "Recording is already in progress.")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (!checkPermission(Manifest.permission.RECORD_AUDIO)) {
                    showToast("Microphone permission is required")
                    return@launch
                }

                // Check if Bluetooth microphone is connected
                if (isBluetoothMicConnected()) {
                    Log.d(TAG, "Using Bluetooth microphone for recording.")
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    CoroutineScope(Dispatchers.IO).launch {
                        startBluetoothScoWithRetry() // Use the retry mechanism
                    }
                    // Continue with the recording logic...
                } else {
                    Log.d(TAG, "Using built-in microphone for recording.")
                    audioManager.mode = AudioManager.MODE_NORMAL
                }


                // Set up the MediaRecorder
                setupMediaRecorder()

                Log.d(TAG, "Attempting to start recording...")

                mediaRecorder?.apply {
                    Log.d(TAG, "Starting MediaRecorder...")
                    start() // Start recording
                    isRecording = true // Update the recording state
                    Log.d(TAG, "Recording started successfully.");
                    updateUIForRecording() // Update UI to reflect the recording state
                    Log.d(TAG, "isRecording updated to: $isRecording"); // Log the updated state
                } ?: run {
                    Log.e(TAG, "MediaRecorder is null after setup.")
                    showToast("MediaRecorder setup failed.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error starting recording: ${e.message}")
                showToast("Error starting recording: ${e.message}")
            }
        }
    }

    private suspend fun startBluetoothScoWithRetry() {
        var retries = 5
        val timeout = System.currentTimeMillis() + 10000 // 10 seconds timeout

        while (retries > 0 && System.currentTimeMillis() < timeout) {
            startBluetoothSco()
            delay(2000) // Wait for 2 seconds between retries

            if (audioManager.isBluetoothScoOn) {
                Log.d(TAG, "Bluetooth SCO is now active.")
                return
            }
            retries--
        }
        Log.d(TAG, "Failed to activate Bluetooth SCO after multiple attempts.")
    }









    private fun startBluetoothSco() {
        if (!checkPermission(Manifest.permission.BLUETOOTH_CONNECT) || !checkPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            showToast("Bluetooth permissions are required")
            return
        }

        try {
            bluetoothAdapter?.let {
                audioManager.startBluetoothSco()
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isBluetoothScoOn = true
                Log.i(TAG, "Requesting Bluetooth SCO connection for microphone")
            } ?: Log.e(TAG, "Bluetooth adapter is null.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Bluetooth SCO: ${e.message}")
            showToast("Error starting Bluetooth SCO: ${e.message}")
        }
    }



    private fun isBluetoothMicConnected(): Boolean {
        // Check if Bluetooth permissions are granted
        if (!checkPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.e(TAG, "Bluetooth connection permission is not granted.")
            return false
        }

        if (selectedBluetoothDevice == null) {
            Log.d(TAG, "No Bluetooth device selected.")
            return false
        }

        return try {
            // Check the connection state of the selected Bluetooth device
            val isConnected = bluetoothHeadset?.getConnectionState(selectedBluetoothDevice) == BluetoothProfile.STATE_CONNECTED
            Log.d(TAG, "Selected Bluetooth Device: ${selectedBluetoothDevice?.name}, Connection State: $isConnected")
            isConnected
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when checking Bluetooth connection state: ${e.message}")
            Toast.makeText(this, "Permission denied when checking Bluetooth connection state", Toast.LENGTH_SHORT).show()
            false
        }
    }



    private fun updateUIForRecording() {
        runOnUiThread {
            Log.d(TAG, "Updating UI for recording state.")
            btnRecord.text = "Stop Recording"
            tvStatus.text = "Status: Recording"
            btnPlay.visibility = View.GONE
        }
    }


    private fun stopRecording() {
        Log.d(TAG, "isRecording before stop: $isRecording") // Log before stopping

        if (!isRecording) {
            Log.d(TAG, "No recording in progress to stop.")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                mediaRecorder?.apply {
                    stop() // Stop recording
                    release() // Release resources
                    mediaRecorder = null // Set to null to avoid reusing
                    isRecording = false // Set recording state to false
                    outputFile = recordingFilePath // Save the output file path for playback
                }

                Log.d(TAG, "Recording stopped")
                updateUIAfterStopRecording() // Call to update UI after stopping
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording: ${e.message}")
                showToast("Error stopping recording: ${e.message}")
            } finally {
                // Stop Bluetooth SCO connection if necessary
                if (audioManager.isBluetoothScoOn) {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }
                audioManager.mode = AudioManager.MODE_NORMAL // Reset AudioManager mode
            }
        }
    }




    private fun updateUIAfterStopRecording() {
        Log.d(TAG, "isRecording after stop: $isRecording") // 在這裡添加日誌

        btnRecord.text = "Start Recording" // Change button text
        tvStatus.text = "Status: Recording stopped" // Update status text
        btnPlay.visibility = View.VISIBLE // Ensure the play button is visible
    }





    private fun saveAudioFile() {
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "audio_${System.currentTimeMillis()}.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/m4a")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
            put(MediaStore.Audio.Media.IS_MUSIC, 1) // Optionally mark it as music
        }

        try {
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { audioUri ->
                contentResolver.openOutputStream(audioUri)?.use { outputStream ->
                    FileInputStream(outputFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } ?: Log.e(TAG, "Failed to insert audio file into MediaStore.")
        } catch (e: IOException) {
            Log.e(TAG, "Error saving audio file: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when accessing MediaStore: ${e.message}")
        }
    }

    private suspend fun playRecording() {
        if (outputFile.isEmpty()) {
            showToast("No recording found!")
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
                        btnPlay.visibility = View.GONE // Hide play button
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error playing recording: ${e.message}")
                showToast("Error playing recording: ${e.message}")
            }
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun showBluetoothDevices() {
        if (!checkPermission(Manifest.permission.BLUETOOTH_CONNECT) || !checkPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val pairedDevices = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            if (pairedDevices.isEmpty()) {
                Toast.makeText(this, "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show()
                return
            }

            val deviceNames = pairedDevices.map { it.name }
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Select Bluetooth Device")
                .setItems(deviceNames.toTypedArray()) { _, which ->
                    selectedBluetoothDevice = pairedDevices[which]
                    Log.d(TAG, "Selected device: ${selectedBluetoothDevice?.name}")
                    Toast.makeText(this, "Selected device: ${selectedBluetoothDevice?.name}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .create()
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing Bluetooth devices: ${e.message}")
        }
    }


    private fun setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        bluetoothAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, headset: BluetoothProfile) {
                bluetoothHeadset = headset as? BluetoothHeadset
                Log.d(TAG, "Bluetooth headset profile connected")
            }

            override fun onServiceDisconnected(profile: Int) {
                bluetoothHeadset = null
                Log.d(TAG, "Bluetooth headset profile disconnected")
            }
        }, BluetoothProfile.HEADSET)
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    Log.d(TAG, "SCO audio connected")
                    // Optionally update UI or state
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    Log.d(TAG, "SCO audio disconnected")
                    // Handle disconnection if necessary
                    audioManager.mode = AudioManager.MODE_NORMAL // Reset AudioManager mode
                }
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(scoReceiver)
        mediaRecorder?.release()
        mediaPlayer?.release()
    }

    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
