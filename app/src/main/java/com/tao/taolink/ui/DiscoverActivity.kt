package com.tao.tao.link.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
import com.tao.tao.link.net.DiscoveryService
import com.tao.tao.link.net.NsdDiscoveryController

/**
 * 附近设备（原型 v8 s-discover 四态定稿）：NSD 浏览同一 WiFi 下的设备，点了连。
 * 四态状态机（R4，设计规范七.2 空/加载/失败/取消 + 原型 found/loading/empty/fail）：
 * 加载（进页即扫）→ 找到（列表）/ 空（超时没找到，不装死）/ 失败（启动失败，三段式+重试）。
 * 取消=退出本页即 stopBrowsing（onDestroy 收口）。发现逻辑归 net 层，本 Activity 只做壳与状态展示。
 */
class DiscoverActivity : AppCompatActivity() {

    private enum class State { LOADING, FOUND, EMPTY, FAILED }

    private lateinit var discovery: NsdDiscoveryController
    private lateinit var list: LinearLayout
    private lateinit var loadingHint: TextView
    private lateinit var emptyHint: TextView
    private lateinit var failCard: View
    private val peers = LinkedHashMap<String, DiscoveryService.DiscoveredPeer>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = State.LOADING

    private val emptyTimeout = Runnable { if (state == State.LOADING) render(State.EMPTY) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discover)
        discovery = NsdDiscoveryController(this)
        list = findViewById(R.id.discover_list)
        loadingHint = findViewById(R.id.discover_loading)
        emptyHint = findViewById(R.id.discover_empty_hint)
        failCard = findViewById(R.id.discover_fail)

        findViewById<Button>(R.id.btn_back_input).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_discover_retry).setOnClickListener { retry() }
        beginScan()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(emptyTimeout)
        discovery.stopBrowsing() // 取消口径：离页即停扫，无残留回调
        super.onDestroy()
    }

    private fun beginScan() {
        render(State.LOADING)
        mainHandler.postDelayed(emptyTimeout, EMPTY_TIMEOUT_MS)
        discovery.startBrowsing(
            onFound = { peer -> runOnUiThread { onPeerFound(peer) } },
            onError = { code -> runOnUiThread { onScanFailed(code) } },
        )
    }

    private fun retry() {
        discovery.stopBrowsing()
        beginScan()
    }

    private fun onPeerFound(peer: DiscoveryService.DiscoveredPeer) {
        if (!peer.supportsControlled) return // 只列可被控设备（roles=d），主控互见不入列
        mainHandler.removeCallbacks(emptyTimeout)
        peers[peer.host] = peer
        renderPeers()
    }

    private fun onScanFailed(@Suppress("UNUSED_PARAMETER") errorCode: Int) {
        mainHandler.removeCallbacks(emptyTimeout)
        render(State.FAILED)
    }

    private fun renderPeers() {
        list.removeAllViews()
        for (peer in peers.values) {
            list.addView(buildPeerRow(peer))
        }
        render(if (peers.isEmpty()) State.EMPTY else State.FOUND)
    }

    private fun buildPeerRow(peer: DiscoveryService.DiscoveredPeer): View {
        val row = layoutInflater.inflate(R.layout.item_discover_peer, list, false)
        row.findViewById<TextView>(R.id.peer_name).text = peer.deviceName
        row.findViewById<TextView>(R.id.peer_meta).text = getString(R.string.discover_same_wifi)
        row.findViewById<Button>(R.id.btn_connect_peer).setOnClickListener {
            PairInputActivity.startForPeer(this, peer)
        }
        return row
    }

    private fun render(next: State) {
        state = next
        loadingHint.visibility = if (next == State.LOADING) View.VISIBLE else View.GONE
        list.visibility = if (next == State.FOUND) View.VISIBLE else View.GONE
        emptyHint.visibility = if (next == State.EMPTY) View.VISIBLE else View.GONE
        failCard.visibility = if (next == State.FAILED) View.VISIBLE else View.GONE
    }

    private companion object {
        /** 加载→空 的判定期：超过仍未发现任何可被控设备即落空态，不装死。 */
        const val EMPTY_TIMEOUT_MS = 12_000L
    }
}
