package com.tao.tao.link.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
import com.tao.tao.link.net.DiscoveryService
import com.tao.tao.link.net.NsdDiscoveryController

/**
 * 附近设备（定稿原型 v2 s-discover / s-discover-empty）：NSD 浏览同一 WiFi 下的设备，点了连。
 * 发现逻辑归 net 层（NsdDiscoveryController），本 Activity 只做壳与列表展示（编码规范分层条款）。
 */
class DiscoverActivity : AppCompatActivity() {

    private lateinit var discovery: NsdDiscoveryController
    private lateinit var list: LinearLayout
    private lateinit var scanning: TextView
    private lateinit var emptyHint: TextView
    private val peers = LinkedHashMap<String, DiscoveryService.DiscoveredPeer>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discover)
        discovery = NsdDiscoveryController(this)
        list = findViewById(R.id.discover_list)
        scanning = findViewById(R.id.discover_scanning)
        emptyHint = findViewById(R.id.discover_empty_hint)

        findViewById<Button>(R.id.btn_back_input).setOnClickListener { finish() }
        renderEmptyState()
        discovery.startBrowsing { peer -> runOnUiThread { onPeerFound(peer) } }
    }

    override fun onDestroy() {
        discovery.stopBrowsing()
        super.onDestroy()
    }

    private fun onPeerFound(peer: DiscoveryService.DiscoveredPeer) {
        scanning.visibility = View.GONE
        if (!peer.supportsControlled) return // 只列可被控设备（roles=d），主控互见不入列
        peers[peer.host] = peer
        renderPeers()
    }

    private fun renderPeers() {
        list.removeAllViews()
        for (peer in peers.values) {
            list.addView(buildPeerRow(peer))
        }
        renderEmptyState()
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

    private fun renderEmptyState() {
        emptyHint.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
    }
}
