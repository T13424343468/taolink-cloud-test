package com.tao.tao.link.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
import com.tao.tao.link.protocol.DeviceFingerprint
import com.tao.tao.link.protocol.DeviceIdentity
import com.tao.tao.link.protocol.PairingResponder
import com.tao.tao.link.protocol.PairingResponderHolder

/**
 * 被控端配对码大字显示（需求清单 §1：配对码被控端大字显示、主控端输入，被控端全程零输入）。
 * 老人机场景字号拉满（大道至简：第一眼只见配对码这一件事）。
 */
class PairCodeActivity : AppCompatActivity() {

    private lateinit var responder: PairingResponder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair_code)
        responder = PairingResponderHolder.get(
            filesDir,
            DeviceIdentity.ownFingerprint(filesDir, getString(R.string.default_device_name)),
        )
        findViewById<TextView>(R.id.pair_code_text).text = responder.displayCode()
    }
}
