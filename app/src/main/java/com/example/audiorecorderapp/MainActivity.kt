package com.example.audiorecorderapp

import android.Manifest
import android.content.pm.PackageManager
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
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false
    private var outputFile: String = ""

    private lateinit var audioFiles: MutableList<String>
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        tvStatus = findViewById(R.id.tvStatus)
        listView = findViewById(R.id.listView)

        // 初始化音频文件列表
        audioFiles = mutableListOf()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, audioFiles)
        listView.adapter = adapter

        // 点击列表项播放音频
        listView.setOnItemClickListener { _, _, position, _ ->
            playAudio(audioFiles[position])
        }

        // 点击按钮开始/停止录音
        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        // 加载已有的录音文件
        loadAudioFiles()
    }

    // 请求权限结果处理
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "录音权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 检查并请求录音权限
    private fun checkAndRequestPermissions(): Boolean {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 1)
            return false
        }
        return true
    }

    // 开始录音
    private fun startRecording() {
        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予录音权限", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            outputFile = "${externalCacheDir?.absolutePath}/${System.currentTimeMillis()}.m4a" // 使用更优质的格式
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC) // 使用麦克风作为音频源
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4) // 使用 MPEG-4 容器格式
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC) // 使用 AAC 编码器，常见于高质量录音
                setAudioChannels(1) // 单声道录音
                setAudioSamplingRate(44100) // 采样率设置为 44.1kHz，高质量音频标准
                setAudioEncodingBitRate(128000) // 比特率设置为 128 kbps，提高音频质量
                setOutputFile(outputFile)

                try {
                    prepare()
                    start()
                    isRecording = true
                    btnRecord.text = "Stop Recording"
                    tvStatus.text = "Status: Recording"
                } catch (e: IOException) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "录音准备失败: ${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            Toast.makeText(this@MainActivity, "初始化 MediaRecorder 失败: ${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            e.printStackTrace()
            Toast.makeText(this@MainActivity, "录音权限问题: ${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }


    // 停止录音
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

            // 将录音文件添加到列表
            audioFiles.add(outputFile)
            adapter.notifyDataSetChanged()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            Toast.makeText(this@MainActivity, "停止录音失败: ${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }

    // 播放音频
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
            Toast.makeText(this@MainActivity, "播放音频失败: ${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }

    // 加载已有的音频文件
    private fun loadAudioFiles() {
        val directory = externalCacheDir
        val files = directory?.listFiles { file -> file.extension == "3gp" }
        files?.forEach { file ->
            audioFiles.add(file.absolutePath)
        }
        adapter.notifyDataSetChanged()
    }
}
