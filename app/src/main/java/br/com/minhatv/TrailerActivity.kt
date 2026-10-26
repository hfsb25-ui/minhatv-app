package br.com.minhatv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity

/** toca o trailer do YouTube dentro do próprio app */
class TrailerActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var spin: ProgressBar
    private lateinit var hint: TextView
    private var key = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        key = intent.getStringExtra("key") ?: ""
        if (key.isEmpty()) { finish(); return }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            }
            addJavascriptInterface(Bridge(), "BRTV")
            isFocusable = false
        }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        spin = ProgressBar(this)
        root.addView(spin, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
        hint = text("OK pausa  ·  ◀ ▶ volta/avança 10s  ·  Voltar fecha", 12f, 0xCCFFFFFF.toInt()).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(0x99000000.toInt(), dpf(16f))
        }
        root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(600).start() }, 5000)
        setContentView(root)

        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:fixed;inset:0;width:100%;height:100%}</style></head>
            <body><div id="p"></div>
            <script>
              var player;
              var tag=document.createElement('script');tag.src='https://www.youtube.com/iframe_api';document.head.appendChild(tag);
              function onYouTubeIframeAPIReady(){
                player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$key',host:'https://www.youtube-nocookie.com',
                  playerVars:{autoplay:1,controls:1,rel:0,modestbranding:1,playsinline:1,fs:0,iv_load_policy:3,origin:'https://www.youtube-nocookie.com'},
                  events:{onReady:function(e){BRTV.ready();e.target.playVideo();},
                          onStateChange:function(e){if(e.data===0)BRTV.ended();},
                          onError:function(e){BRTV.error(String(e.data));}}});
              }
              function toggle(){if(!player)return;var s=player.getPlayerState();if(s===1)player.pauseVideo();else player.playVideo();}
              function seek(d){if(!player)return;player.seekTo(Math.max(0,player.getCurrentTime()+d),true);}
            </script></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL("https://www.youtube-nocookie.com/", html, "text/html", "utf-8", null)
    }

    inner class Bridge {
        @JavascriptInterface fun ready() = runOnUiThread { spin.visibility = View.GONE }
        @JavascriptInterface fun ended() = runOnUiThread { finish() }
        @JavascriptInterface fun error(code: String) = runOnUiThread { fallback() }
    }

    /** se o YouTube não deixar tocar aqui, oferece abrir no app do YouTube */
    private fun fallback() {
        spin.visibility = View.GONE
        hint.alpha = 1f
        hint.text = "Este trailer não pode tocar aqui. Aperte OK para abrir no YouTube."
        openOutside = true
    }

    private var openOutside = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (openOutside) {
                        for (u in listOf("vnd.youtube:$key", "https://www.youtube.com/watch?v=$key")) {
                            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))); finish(); return true } catch (x: Exception) {}
                        }
                        return true
                    }
                    web.evaluateJavascript("toggle()", null); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { web.evaluateJavascript("seek(-10)", null); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { web.evaluateJavascript("seek(10)", null); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onPause() {
        super.onPause()
        web.evaluateJavascript("if(player)player.pauseVideo()", null)
    }

    override fun onDestroy() {
        if (::web.isInitialized) { web.loadUrl("about:blank"); web.destroy() }
        super.onDestroy()
    }
}
