package com.kintoretimer.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var timer: TimerController

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val closeEditorOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            web.evaluateJavascript("window.kintoreBack()", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        timer = TimerController.get(this)
        volumeControlStream = AudioManager.STREAM_MUSIC
        onBackPressedDispatcher.addCallback(this, closeEditorOnBack)

        val dark = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }

        web = WebView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            overScrollMode = WebView.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(), "Native")
            webViewClient = object : WebViewClient() {
                // メニュー編集のYouTubeリンクなどは外部アプリで開く
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (request.url.scheme == "file") return false
                    openExternal(request.url)
                    return true
                }
            }
        }

        val root = FrameLayout(this)
        root.addView(web)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        web.loadUrl("file:///android_asset/index.html")

        askNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        web.onResume()
        timer.viewListener = ::showState
        timer.pushState()
    }

    override fun onStop() {
        timer.viewListener = null
        web.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun showState(json: String) {
        web.evaluateJavascript("window.onTimerState && window.onTimerState($json)", null)
        // 動いている間は画面を消さない
        if (timer.engine.running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: android.content.ActivityNotFoundException) {
            // 開けるアプリがなければ何もしない
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** index.html から呼ばれる窓口。呼び出しは別スレッドで届くのでメインへ渡す。 */
    private inner class Bridge {
        @JavascriptInterface
        fun load(phasesJson: String, voice: Boolean, beeps: Boolean, vol: Double, force: Boolean) =
            ui { timer.load(phasesJson, voice, beeps, vol, force) }

        @JavascriptInterface
        fun toggle() = ui { timer.engine.toggle() }

        @JavascriptInterface
        fun pause() = ui { timer.engine.pause() }

        @JavascriptInterface
        fun next() = ui { timer.engine.next() }

        @JavascriptInterface
        fun prev() = ui { timer.engine.prev() }

        @JavascriptInterface
        fun reset() = ui { timer.engine.reset() }

        @JavascriptInterface
        fun setVolume(vol: Double) = ui { timer.setVolume(vol) }

        @JavascriptInterface
        fun test(vol: Double, voice: Boolean) = ui { timer.testSound(vol, voice) }

        @JavascriptInterface
        fun ttsReady(): Boolean = timer.ttsReady

        @JavascriptInterface
        fun setEditorOpen(open: Boolean) = ui { closeEditorOnBack.isEnabled = open }

        private fun ui(block: () -> Unit) {
            runOnUiThread(block)
        }
    }
}
