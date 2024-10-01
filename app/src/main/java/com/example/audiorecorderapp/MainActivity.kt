package com.example.audiorecorderapp

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var btnBluetooth: Button
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false
    private var outputFile: String = ""

    private lateinit var audioFiles: MutableList<String>
    private lateinit var adapter: ArrayAdapter<String>

    // Bluetooth variables
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var selectedBluetoothDevice: BluetoothDevice? = null

    // Request codes
    private val BLUETOOTH_PERMISSION_REQUEST = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        tvStatus = findViewById(R.id.tvStatus)
        listView = findViewById(R.id.listView)
        btnBluetooth = findViewById(R.id.btnBluetooth)

        audioFiles = mutableListOf()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, audioFiles)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            playAudio(audioFiles[position])
        }

        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        btnBluetooth.setOnClickListener {
            if (checkBluetoothPermissions()) {
                showBluetoothDevices()
            } else {
                requestBluetoothPermissions()
            }
        }

        loadAudioFiles()
        setupBluetooth()
    }

    private fun checkBluetoothPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return true
    }

    private fun requestBluetoothPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
            BLUETOOTH_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permissions granted", Toast.LENGTH_SHORT).show()
                showBluetoothDevices()
            } else {
                Toast.makeText(this, "Bluetooth permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            btnBluetooth.isEnabled = false
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, 1)
        }

        try {
            bluetoothAdapter!!.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        bluetoothHeadset = proxy as BluetoothHeadset
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) {
                        bluetoothHeadset = null
                    }
                }
            }, BluetoothProfile.HEADSET)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Bluetooth connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBluetoothDevices() {
        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter!!.bondedDevices
        val deviceNames = pairedDevices.map { it.name }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select Bluetooth Device")
        builder.setItems(deviceNames.toTypedArray()) { _, which ->
            selectedBluetoothDevice = pairedDevices.elementAt(which)
            Toast.makeText(this, "Selected: ${selectedBluetoothDevice?.name}", Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }

    private fun startRecording() {
        if (!checkAndRequestPermissions()) return

        try {
            outputFile = "${externalCacheDir?.absolutePath}/${System.currentTimeMillis()}.m4a"
            mediaRecorder = MediaRecorder().apply {
                // Use Bluetooth mic if available, otherwise fallback to MIC
                val audioSource = if (selectedBluetoothDevice != null && isBluetoothMicConnected()) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION  // VOICE_COMMUNICATION uses Bluetooth mic
                } else {
                    MediaRecorder.AudioSource.MIC
                }
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputFile)

                prepare()
                start()
                isRecording = true
                btnRecord.text = "Stop Recording"
                tvStatus.text = "Status: Recording"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isBluetoothMicConnected(): Boolean {
        val connectedDevices = bluetoothHeadset?.connectedDevices ?: return false
        return selectedBluetoothDevice != null && connectedDevices.contains(selectedBluetoothDevice)
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            btnRecord.text = "Start Recording"
            tvStatus.text = "Status: Idle"

            audioFiles.add(outputFile)
            adapter.notifyDataSetChanged()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            Toast.makeText(this@MainActivity, "Failed to stop recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playAudio(filePath: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
                tvStatus.text = "Status: Playing"
                setOnCompletionListener {
                    tvStatus.text = "Status: Idle"
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this@MainActivity, "Failed to play audio: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadAudioFiles() {
        val directory = externalCacheDir
        val files = directory?.listFiles { file -> file.extension == "m4a" }
        files?.forEach { file ->
            audioFiles.add(file.absolutePath)
        }
        adapter.notifyDataSetChanged()
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 1)
            return false
        }
        return true
    }
}
