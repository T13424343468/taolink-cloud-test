package com.tao.tao.link.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
import com.tao.tao.link.net.DiscoveryService
import com.tao.tao.link.net.MasterSession
import com.tao.tao.link.protocol.DeviceIdentity
import com.tao.tao.link.protocol.PairingRecord

/**
 * 输配对码（定稿原型 v2 s-pair-input）：6 位码提交给 MasterSession.pairAndConnect（net 层），
 * 成功进连接中态（ConnectingActivity），失败提示重输（s-pair-input 文案：不会连错人）。
 */
class PairInputActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair_input)
        val input = findViewById<EditText>(R.id.pair_code_input)
        findViewById<Button>(R.id.btn_connect_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_connect_submit).setOnClickListener {
            val code = input.text?.toString().orEmpty()
            if (code.length != PAIR_CODE_LEN) {
                Toast.makeText(this, R.string.pair_input_aux, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
            // 真机审查堵死路（0.2.5 验收教训）：无发现入口进来 host 为空，此前直接开连接必败且无提示。
            if (host.isEmpty()) {
                Toast.makeText(this, R.string.pair_input_need_peer, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ConnectingActivity.start(
                this,
                host,
                intent.getIntExtra(EXTRA_PORT, 0),
                code,
                intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty(),
                intent.getStringExtra(EXTRA_NAME).orEmpty(),
            )
        }
    }

    companion object {
        private const val PAIR_CODE_LEN = 6
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_NAME = "name"

        fun startForPeer(context: Context, peer: DiscoveryService.DiscoveredPeer) {
            context.startActivity(
                Intent(context, PairInputActivity::class.java)
                    .putExtra(EXTRA_HOST, peer.host)
                    .putExtra(EXTRA_PORT, peer.port)
                    .putExtra(EXTRA_NAME, peer.deviceName),
            )
        }

        fun startForFingerprint(context: Context, record: PairingRecord) {
            context.startActivity(
                Intent(context, PairInputActivity::class.java)
                    .putExtra(EXTRA_FINGERPRINT, record.fingerprintHex)
                    .putExtra(EXTRA_NAME, record.deviceName),
            )
        }
    }
}
