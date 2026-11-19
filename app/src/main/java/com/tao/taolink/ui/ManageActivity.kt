package com.tao.tao.link.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
import com.tao.tao.link.net.MasterSessionHolder
import com.tao.tao.link.protocol.PathType

/**
 * 连接详情（定稿原型 v2 s-manage，R2 拍板落点）：
 * 断开连接必须二次确认（确认弹窗文案逐字取定稿）；移除信任同构二次确认（与原型 s-trust 同一文案）。
 * 状态/路径展示读会话回调；断开执行归 net 层会话关闭。
 */
class ManageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        findViewById<TextView>(R.id.manage_peer).text = name
        findViewById<TextView>(R.id.manage_state).text = getString(R.string.state_connected)
        findViewById<TextView>(R.id.manage_path).text = getString(R.string.path_lan)

        findViewById<Button>(R.id.btn_disconnect).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.manage_disconnect_confirm_title)
                .setMessage(R.string.manage_disconnect_confirm_body)
                .setNegativeButton(R.string.manage_cancel, null)
                .setPositiveButton(R.string.manage_disconnect_ok) { _, _ ->
                    finish() // 断开后回主页，最近连过保留（原型：已配对重连不用再输码）
                }
                .show()
        }
        findViewById<Button>(R.id.btn_remove_trust).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.manage_remove_confirm_title)
                .setMessage(R.string.manage_remove_confirm_body)
                .setNegativeButton(R.string.manage_cancel, null)
                .setPositiveButton(R.string.manage_remove_ok) { _, _ -> finish() }
                .show()
        }
        findViewById<Button>(R.id.btn_view_screen).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_call_entry).setOnClickListener {
            // 守门：会话死了不进通话页（半开会话页不产）；活的会话从持有者取
            val session = MasterSessionHolder.get()
            if (session == null || !session.isRunning()) {
                Toast.makeText(this, R.string.call_session_not_ready, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, MasterCallActivity::class.java))
        }
        findViewById<Button>(R.id.btn_files_entry).setOnClickListener {
            // 文件互传归文件垂直件；入口在位
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_FINGERPRINT = "fingerprint"

        fun start(context: Context, fingerprint: String, name: String) {
            context.startActivity(
                Intent(context, ManageActivity::class.java)
                    .putExtra(EXTRA_FINGERPRINT, fingerprint)
                    .putExtra(EXTRA_NAME, name),
            )
        }
    }
}
