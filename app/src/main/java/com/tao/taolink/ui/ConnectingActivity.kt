package com.tao.tao.link.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
import com.tao.tao.link.net.MasterSession
import com.tao.tao.link.net.MasterSessionHolder
import com.tao.tao.link.protocol.DeviceIdentity
import com.tao.tao.link.protocol.SessionState

/**
 * 正在连接（定稿原型 v2 s-connecting）：找到设备→配对成功→建立加密连接三步，
 * 连接执行归 net 层（MasterSession.pairAndConnect 阻塞调用放工作线程），本 Activity 只做壳与状态展示。
 * 成功转 ManageActivity（详情页），失败回输码页重输（原型：不会连错人）。
 */
class ConnectingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connecting)
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty()
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()

        findViewById<TextView>(R.id.connecting_peer).text = name
        findViewById<TextView>(R.id.connecting_path).text = getString(R.string.path_lan)
        setSteps(SessionState.PAIRING)

        findViewById<Button>(R.id.btn_connecting_cancel).setOnClickListener { finish() }

        Thread {
            val statusProvider = com.tao.tao.link.status.AndroidStatusProvider(this)
            val session = MasterSession(
                getString(R.string.default_device_name),
                DeviceIdentity.ownFingerprint(filesDir, getString(R.string.default_device_name)).hex,
                com.tao.tao.link.protocol.DefaultSessionCrypto(),
                { statusProvider.snapshot(online = true) },
            )
            val ok = session.pairAndConnect(host, port, code.toCharArray())
            runOnUiThread {
                if (ok) {
                    setSteps(SessionState.CONNECTED)
                    // 会话挂入持有者（通话/操控页从这里取活会话）：引用不随本页销毁而丢失
                    MasterSessionHolder.attach(session)
                    ManageActivity.start(this, fingerprint, name)
                    finish()
                } else {
                    // 0.2.8 修红：失败不再静默退出（真机复验：失败像消失一样没人知道为什么）——
                    // 留在页上说人话原因，按钮改「返回重输」，用户看得懂下一步。
                    setSteps(SessionState.DISCONNECTED)
                    val reasonText = when (session.lastPairFailure) {
                        MasterSession.PairFailure.UNREACHABLE -> getString(R.string.connecting_fail_unreachable)
                        MasterSession.PairFailure.NO_ANSWER -> getString(R.string.connecting_fail_no_answer)
                        MasterSession.PairFailure.CODE_WRONG -> getString(R.string.connecting_fail_code)
                        MasterSession.PairFailure.PROTOCOL_MISMATCH -> getString(R.string.connecting_fail_protocol)
                        MasterSession.PairFailure.NONE -> getString(R.string.connecting_fail_no_answer)
                    }
                    findViewById<TextView>(R.id.connecting_error).text = reasonText
                    findViewById<Button>(R.id.btn_connecting_cancel).setText(R.string.connecting_back_reinput)
                }
            }
        }.start()
    }

    private fun setSteps(state: SessionState) {
        val doing = getString(R.string.connecting_state_doing)
        val done = getString(R.string.connecting_state_done)
        val (found, paired, crypto) = when (state) {
            SessionState.PAIRING -> Triple(done, doing, getString(R.string.connecting_state_ready))
            SessionState.CONNECTED -> Triple(done, done, done)
            else -> Triple(doing, getString(R.string.connecting_state_ready), getString(R.string.connecting_state_ready))
        }
        findViewById<TextView>(R.id.step_found).text = "${getString(R.string.connecting_step_found)} $found"
        findViewById<TextView>(R.id.step_paired).text = "${getString(R.string.connecting_step_paired)} $paired"
        findViewById<TextView>(R.id.step_crypto).text = "${getString(R.string.connecting_step_crypto)} $crypto"
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_NAME = "name"

        fun start(context: Context, host: String, port: Int, code: String, fingerprint: String, name: String) {
            context.startActivity(
                Intent(context, ConnectingActivity::class.java)
                    .putExtra(EXTRA_HOST, host)
                    .putExtra(EXTRA_PORT, port)
                    .putExtra(EXTRA_CODE, code)
                    .putExtra(EXTRA_FINGERPRINT, fingerprint)
                    .putExtra(EXTRA_NAME, name),
            )
        }
    }
}
