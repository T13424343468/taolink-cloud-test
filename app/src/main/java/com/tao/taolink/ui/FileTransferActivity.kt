package com.tao.tao.link.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.LinkLog
import com.tao.tao.link.R
import com.tao.tao.link.file.FileReceiver
import com.tao.tao.link.file.MasterFileSender
import com.tao.tao.link.net.MasterSessionHolder
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 主控端文件互传页（定稿原型 s-manage 文件入口落点）：
 * 系统选件 → 拷入发件箱 → 逐块走已加密会话发送；对端回执在列（缺口续传/收妥收班）。
 * 会话从 [MasterSessionHolder] 取活会话，未连接守门直接退场（不产半开会话页）。
 * 云测直启钩子：extra auto_file = 文件名 + auto_file_size = 字节数；
 * 发件箱无此文件则按确定性内容生成（第 i 字节 = (i*31+7) mod 256，流水线可独立复算哈希对账）；
 * 无 extra 时产品行为不变。
 */
class FileTransferActivity : AppCompatActivity() {

    private val statusView: TextView by lazy { findViewById(R.id.file_status) }

    private lateinit var outboxDir: File
    private val senderExecutor = Executors.newSingleThreadExecutor()
    private var sender: MasterFileSender? = null
    private var sessionLive = false

    /** 在途文件账目：回执缺口续传时按原账重发（resume 的整册哈希对账用原 sha）。 */
    @Volatile
    private var pendingMeta: com.tao.tao.link.file.FileTransfer.Meta? = null

    /** 云测直启钩子参数；无 extra 时为 null，产品路径走系统选件。 */
    private val autoFile: String? by lazy { intent?.getStringExtra(EXTRA_AUTO_FILE) }
    private val autoFileSize: Int by lazy { intent?.getIntExtra(EXTRA_AUTO_FILE_SIZE, 0) ?: 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_transfer)
        outboxDir = File(filesDir, "outbox").apply { mkdirs() }
        findViewById<Button>(R.id.btn_file_pick).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.btn_file_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
        val session = MasterSessionHolder.get()
        if (session == null || !session.isRunning()) {
            Toast.makeText(this, R.string.call_session_not_ready, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sessionLive = true
        session.onFileReceipt = { payload -> onReceipt(payload) }
        autoFile?.let { name -> sendByHook(name, autoFileSize) }
    }

    override fun onDestroy() {
        sender?.cancel()
        senderExecutor.shutdownNow()
        // 只许在场实例拆回执入口：转场期 onDestroy 可能晚于新实例 onResume 迟到（MasterCallActivity 同款教训）
        if (activeInstance === this) {
            activeInstance = null
            MasterSessionHolder.get()?.onFileReceipt = null
        }
        super.onDestroy()
    }

    /** 产品路径：系统选件，拷入发件箱后发送。 */
    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("*/*")
        }
        startActivityForResult(intent, REQUEST_PICK_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val pickedName = uri.lastPathSegment?.substringAfterLast('/') ?: "picked-file"
        val safeName = pickedName.filter { it != '/' && it != '\\' }.ifEmpty { "picked-file" }
        val outFile = File(outboxDir, safeName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return
        } catch (e: Exception) {
            LinkLog.w(TAG, "选件拷贝失败：${e.message}")
            Toast.makeText(this, R.string.file_pick_failed, Toast.LENGTH_SHORT).show()
            return
        }
        startSend(outFile)
    }

    /** 云测直启：发件箱无文件则按确定性内容生成，字节公式与流水线复算口径一致。 */
    private fun sendByHook(name: String, sizeBytes: Int) {
        if (sizeBytes <= 0) {
            LinkLog.w(TAG, "auto_file size 非法：$sizeBytes")
            return
        }
        val outFile = File(outboxDir, name.filter { it != '/' && it != '\\' })
        if (!outFile.isFile || outFile.length() != sizeBytes.toLong()) {
            outFile.outputStream().use { out ->
                var i = 0L
                val buf = ByteArray(64 * 1024)
                while (i < sizeBytes) {
                    val n = minOf(buf.size.toLong(), sizeBytes - i).toInt()
                    for (j in 0 until n) buf[j] = (((i + j) * 31 + 7) % 256).toByte()
                    out.write(buf, 0, n)
                    i += n
                }
            }
        }
        startSend(outFile)
    }

    private fun startSend(file: File) {
        val session = MasterSessionHolder.get()
        if (session == null || !session.isRunning() || !sessionLive) {
            Toast.makeText(this, R.string.call_session_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val metaSha = FileReceiver.sha256Hex(file)
        val meta = com.tao.tao.link.file.FileTransfer.Meta(
            name = file.name,
            sizeBytes = file.length(),
            sha256 = metaSha,
        )
        // 元数据先于首块入链：收端据 name/sizeBytes/sha256 建档（协议文档 §2-8）
        session.sendFileMeta(
            JSONObject()
                .put("name", meta.name)
                .put("sizeBytes", meta.sizeBytes)
                .put("sha256", meta.sha256)
                .put("chunkSize", MasterFileSender.DEFAULT_CHUNK_SIZE)
                .toString()
                .toByteArray(Charsets.UTF_8),
        )
        val sender = MasterFileSender(outboxDir, sendChunk = { payload -> session.sendFileChunk(payload) })
        this.sender = sender
        pendingMeta = meta
        LinkLog.d(TAG, "文件发送已发起 ${meta.name} ${meta.sizeBytes}字节 sha=$metaSha")
        runOnUiThread { statusView.text = getString(R.string.file_status_sending, 0) }
        senderExecutor.execute {
            sender.send(meta, onProgress = { percent ->
                runOnUiThread { statusView.text = getString(R.string.file_status_sending, percent) }
            })
            LinkLog.d(TAG, "文件发送完成 ${meta.name}")
        }
    }

    /** 对端回执（收端账本）：缺口 → 续传；收妥 → 收班。 */
    private fun onReceipt(payload: ByteArray) {
        val obj = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        if (!obj.optBoolean("complete", false)) {
            val missing = obj.optJSONArray("missing") ?: return
            if (missing.length() == 0) return
            val sender = this.sender ?: return
            val meta = pendingMeta
            val from = missing.optInt(0, -1)
            if (from >= 0 && meta != null) {
                LinkLog.d(TAG, "对端回执缺口，从第 $from 块续传")
                senderExecutor.execute { sender.resume(meta, from) }
            }
            return
        }
        if (obj.optString("error") == "sha_mismatch") {
            LinkLog.w(TAG, "对端校验失败，整件作废：${obj.optString("name")}")
            runOnUiThread { statusView.text = getString(R.string.file_status_verify_failed) }
            return
        }
        LinkLog.d(TAG, "文件回执 确认完成 ${obj.optString("name")}")
        runOnUiThread {
            statusView.text = getString(R.string.file_status_done)
            Toast.makeText(this, R.string.file_status_done, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val TAG = "FileTransfer"
        const val REQUEST_PICK_FILE = 2001

        /** 云测直启参数名（pipeline 用 adb shell am start 传入）。 */
        const val EXTRA_AUTO_FILE = "auto_file"
        const val EXTRA_AUTO_FILE_SIZE = "auto_file_size"

        /** 在场实例口（转场期 onDestroy 迟到不误拆活注册）。 */
        @Volatile
        var activeInstance: FileTransferActivity? = null
    }
}
