package com.velmorath.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var webView: WebView
    private lateinit var webContainer: FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        output = findViewById(R.id.output)
        webView = findViewById(R.id.webView)
        webContainer = findViewById(R.id.webContainer)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.webViewClient = WebViewClient()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        findViewById<Button>(R.id.loginPortal).setOnClickListener {
            openLogin("https://zhlj.whu.edu.cn/pc/index")
        }
        findViewById<Button>(R.id.loginJwgl).setOnClickListener {
            openLogin("https://jwgl.whu.edu.cn/")
        }
        findViewById<Button>(R.id.closeWeb).setOnClickListener {
            CookieManager.getInstance().flush()
            webContainer.visibility = FrameLayout.GONE
        }
        findViewById<Button>(R.id.refresh).setOnClickListener { refreshAll() }
    }

    private fun openLogin(url: String) {
        webContainer.visibility = FrameLayout.VISIBLE
        webView.loadUrl(url)
    }

    private fun cookie(url: String): String =
        CookieManager.getInstance().getCookie(url).orEmpty()

    private fun refreshAll() {
        output.text = "正在请求…"
        lifecycleScope.launch {
            val portal = cookie("https://zhlj.whu.edu.cn")
            val jwgl = cookie("https://jwgl.whu.edu.cn")
            val text = StringBuilder()

            if (portal.isBlank()) {
                text.append("门户未登录，请先点「登录门户」。\n\n")
            } else {
                text.append("== 通知 ==\n")
                    .append(runCatching { ZhljApi.notices(portal) }
                        .getOrElse { "失败：${it.message}" })
                    .append("\n\n== 作业 ==\n")
                    .append(runCatching { ZhljApi.messages(portal) }
                        .getOrElse { "失败：${it.message}" })
                    .append("\n\n== 课表 ==\n")
                    .append(runCatching { ZhljApi.courses(portal) }
                        .getOrElse { "失败：${it.message}" })
            }

            if (jwgl.isBlank()) {
                text.append("\n\n教务未登录，请先点「登录教务」。")
            } else {
                text.append("\n\n== 考试 ==\n")
                    .append(runCatching { ZhljApi.exams(jwgl) }
                        .getOrElse { "失败：${it.message}" })
            }

            output.text = text.toString()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webContainer.visibility == FrameLayout.VISIBLE) {
            CookieManager.getInstance().flush()
            webContainer.visibility = FrameLayout.GONE
        } else {
            super.onBackPressed()
        }
    }
}
