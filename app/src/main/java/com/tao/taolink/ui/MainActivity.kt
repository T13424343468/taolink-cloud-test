package com.tao.tao.link.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
import com.tao.tao.link.service.ControlledSessionService

/** 首页双入口：装上第一眼只见当前要办的两件事（需求清单 §1，大道至简）。 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 让人帮：起被控端驻留服务（配对码大字显示）——被控端垂直班落码入口
        findViewById<android.view.View>(R.id.btn_controlled).setOnClickListener {
            ControlledSessionService.start(this)
            startActivity(Intent(this, PairCodeActivity::class.java))
        }

        // 去帮忙：进主控主页（0.2.3 定稿原型 v2 主控侧屏入口）
        findViewById<android.view.View>(R.id.btn_help).setOnClickListener {
            startActivity(Intent(this, MasterHomeActivity::class.java))
        }
    }
}
