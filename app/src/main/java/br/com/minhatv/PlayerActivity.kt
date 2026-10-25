package br.com.minhatv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var pv: PlayerView
    private lateinit var spin: ProgressBar
    private lateinit var errTv: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelEpg: TextView
    private lateinit var favBtn: TextView
    private lateinit var topTitle: TextView

    private var mode = "movie"
    private var id = ""
    private var ext = "mp4"
    private var title = ""
    private var key = ""
    private var icon = ""
    private var name = ""
    private var sub = ""
    private var sid = ""
    private var triedHls = false
    private var chIndex = 0
    private val h = Handler(Looper.getMainLooper())
    private val saver = object : Runnable {
        override fun run() { saveProg(false); h.postDelayed(this, 5000) }
    }
    private val hidePanel = Runnable { if (::panel.isInitialized) panel.visibility = View.GONE }
    private val hideTitle = Runnable { if (::topTitle.isInitialized) topTitle.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readIntent()
        build()
        initPlayer()
        play(intent.getLongExtra("start", 0L))
    }

    private fun readIntent() {
        mode = intent.getStringExtra("mode") ?: "movie"
        id = intent.getStringExtra("id") ?: ""
        ext = intent.getStringExtra("ext") ?: "mp4"
        title = intent.getStringExtra("title") ?: ""
        key = intent.getStringExtra("key") ?: ""
        icon = intent.getStringExtra("icon") ?: ""
        name = intent.getStringExtra("name") ?: title
        sub = intent.getStringExtra("sub") ?: ""
        sid = intent.getStringExtra("sid") ?: ""
        if (mode == "live") {
            chIndex = Now.channels.indexOfFirst { it.id == id }.coerceAtLeast(0)
            Now.channels.getOrNull(chIndex)?.let { title = (if (it.num.isNotEmpty()) it.num + " · " else "") + it.name; key = it.key }
        }
    }

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pv = PlayerView(this).apply {
            useController = mode != "live"
            controllerShowTimeoutMs = 3500
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            keepScreenOn = true
        }
        root.addView(pv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        spin = ProgressBar(this)
        root.addView(spin, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        errTv = text("", 15f, C.tx2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = rounded(0xF212151C.toInt(), dpf(12f), dp(1), C.line)
            visibility = View.GONE
            setLineSpacing(0f, 1.35f)
        }
        root.addView(errTv, FrameLayout.LayoutParams(dp(520), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        topTitle = text(title, 18f, Color.WHITE, true, 1).apply {
            setPadding(dp(32), dp(22), dp(32), dp(30))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        root.addView(topTitle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // painel do canal ao vivo (aparece ao trocar de canal ou apertar OK)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(40), dp(40), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2000000.toInt(), 0x00000000))
            visibility = View.GONE
        }
        panelTitle = text("", 22f, Color.WHITE, true, 1)
        panelEpg = text("", 14f, C.tx2).apply { setLineSpacing(0f, 1.3f) }
        panel.addView(panelTitle)
        panel.addView(panelEpg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(4), 0, 0) }
        favBtn = button("") { toggleFav() }.apply { textSize = 13f }
        row.addView(favBtn)
        row.addView(text("▲▼ trocar de canal   ·   Voltar para sair", 12f, C.tx3).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginStart = dp(18) })
        panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun initPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 60000, 2000, 4000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spin.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) errTv.visibility = View.GONE
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (mode == "live" && !triedHls) {
                    // tenta o outro formato do canal (.m3u8)
                    triedHls = true
                    startUrl(Xt.url(Kind.LIVE, id, "", forceHls = true), 0)
                    return
                }
                spin.visibility = View.GONE
                errTv.text = "Não foi possível reproduzir.\n" + when (mode) {
                    "live" -> "O canal pode estar fora do ar. Tente outro canal."
                    else -> "O servidor não respondeu a este vídeo. Tente de novo em instantes."
                }
                errTv.visibility = View.VISIBLE
            }
        })
        pv.player = p
        player = p
    }

    private fun play(start: Long) {
        triedHls = false
        errTv.visibility = View.GONE
        topTitle.text = title
        showTitle()
        val url = when (mode) {
            "live" -> Xt.url(Kind.LIVE, id, "")
            "episode" -> Xt.url(Kind.SERIES, id, ext)
            else -> Xt.url(Kind.MOVIE, id, ext)
        }
        startUrl(url, start)
        if (mode == "live") showPanel()
        h.removeCallbacks(saver)
        if (mode != "live") h.postDelayed(saver, 5000)
    }

    private fun startUrl(url: String, start: Long) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(url))
        if (start > 0) p.seekTo(start)
        p.prepare()
        p.playWhenReady = true
    }

    private fun showTitle() {
        topTitle.visibility = View.VISIBLE
        h.removeCallbacks(hideTitle)
        h.postDelayed(hideTitle, 4000)
    }

    /* ---------- ao vivo ---------- */
    private fun zap(d: Int) {
        val l = Now.channels
        if (l.isEmpty()) return
        chIndex = (chIndex + d + l.size) % l.size
        val c = l[chIndex]
        id = c.id; key = c.key
        title = (if (c.num.isNotEmpty()) c.num + " · " else "") + c.name
        play(0)
    }

    private fun showPanel() {
        val c = Now.channels.getOrNull(chIndex)
        panelTitle.text = title
        paintFav()
        panel.visibility = View.VISIBLE
        h.removeCallbacks(hidePanel)
        h.postDelayed(hidePanel, 5000)
        panelEpg.text = "Carregando programação…"
        val cid = c?.id ?: id
        lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) {
                try {
                    val o = org.json.JSONObject(Xt.get(mapOf("action" to "get_short_epg", "stream_id" to cid, "limit" to "2")))
                    val a = o.optJSONArray("epg_listings")
                    if (a == null || a.length() == 0) "" else (0 until a.length()).joinToString("\n") { i ->
                        val p = a.getJSONObject(i)
                        val tt = try { String(Base64.decode(p.s("title"), Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { p.s("title") }
                        val hh = p.s("start").let { s -> if (s.length >= 16) s.substring(11, 16) else "" }
                        (if (i == 0) "Agora" else "Depois") + " · $hh — $tt"
                    }
                } catch (e: Exception) { "" }
            }
            if (cid == id) panelEpg.text = t.ifEmpty { "Sem programação disponível." }
        }
    }

    private fun paintFav() {
        favBtn.text = if (Lib.isFav(key)) "★  Favorito" else "☆  Favoritar canal"
    }

    private fun toggleFav() {
        val on = Lib.toggleFav(key)
        paintFav()
        Toast.makeText(this, if (on) "Canal adicionado aos favoritos" else "Canal removido dos favoritos", Toast.LENGTH_SHORT).show()
        h.removeCallbacks(hidePanel); h.postDelayed(hidePanel, 5000)
    }

    /* ---------- progresso e próximo episódio ---------- */
    private fun saveProg(force: Boolean) {
        val p = player ?: return
        if (mode == "live" || key.isEmpty()) return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur <= 60000 || dur == androidx.media3.common.C.TIME_UNSET) return
        if (!force && pos < 10000) return
        if (pos.toDouble() / dur >= 0.92 || dur - pos < 120000) { if (!Lib.isSeen(key)) Lib.setSeen(key, true); return }
        Lib.saveProg(Prog(key, name, sub, icon, pos, dur, if (mode == "episode") "series" else "movie", id, ext, sid, System.currentTimeMillis()))
    }

    private fun onEnded() {
        if (mode == "live") return
        if (key.isNotEmpty() && !Lib.isSeen(key)) Lib.setSeen(key, true)
        if (mode == "episode") {
            val eps = Now.episodes
            val i = eps.indexOfFirst { it.id == id }
            if (i >= 0 && i + 1 < eps.size) {
                val n = eps[i + 1]
                val s = Now.series
                Toast.makeText(this, "Próximo episódio: T${n.season} E${n.num}", Toast.LENGTH_SHORT).show()
                id = n.id; ext = n.ext; key = "e" + n.id; sub = "T${n.season} E${n.num}"
                title = (s?.name ?: name) + " · " + sub + if (n.title.isNotEmpty()) " — ${n.title}" else ""
                h.postDelayed({ play(0) }, 1500)
                return
            }
        }
        finish()
    }

    /* ---------- controle remoto ---------- */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            val k = e.keyCode
            if (mode == "live") {
                when (k) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(-1); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (panel.visibility != View.VISIBLE) { showPanel(); favBtn.requestFocus(); return true }
                        h.removeCallbacks(hidePanel); h.postDelayed(hidePanel, 5000)
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (panel.visibility != View.VISIBLE) { showPanel(); return true }
                    }
                }
            } else if (!pv.isControllerFullyVisible) {
                val p = player
                when (k) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { p?.seekTo((p.currentPosition - 10000).coerceAtLeast(0)); pv.showController(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p?.seekTo(p.currentPosition + 10000); pv.showController(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        p?.let { it.playWhenReady = !it.playWhenReady }; pv.showController(); showTitle(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { pv.showController(); showTitle(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onPause() {
        super.onPause()
        saveProg(true)
        player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
