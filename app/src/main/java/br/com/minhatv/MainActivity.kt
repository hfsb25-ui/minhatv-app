package br.com.minhatv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** fila do player: canais para zapear e episódios para o próximo */
object Now {
    var channels: List<Item> = emptyList()
    var series: Item? = null
    var episodes: List<Episode> = emptyList()
}

class MainActivity : ComponentActivity() {
    private lateinit var content: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var acct: TextView
    private lateinit var loading: LinearLayout
    private lateinit var loadingTxt: TextView
    private var tab = "home"
    private var shownVersion = -1
    private var lastBack = 0L
    private val openedAt = System.currentTimeMillis()
    private var fastStart = false
    private var homeRv: RecyclerView? = null
    private var lastSig = ""
    private var epgJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // mostra o motivo se o app fechou sozinho da última vez
        Store.str("crash")?.let { c -> showCrash(c); return }
        if (!Repo.hasCredentials()) {
            setContentView(splashView(null, true))
            window.decorView.postDelayed({ startActivity(Intent(this, LoginActivity::class.java)); finish() }, 1800)
            return
        }
        // se a tela foi recriada (o sistema fechou para liberar memória), volta sem abertura e na mesma aba
        fastStart = savedInstanceState != null || Repo.vod.isNotEmpty() || Repo.live.isNotEmpty()
        savedInstanceState?.getString("tab")?.let { tab = it }
        buildShell()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tab != "home") { show("home"); tabs.getChildAt(0).requestFocus(); return }
                if (System.currentTimeMillis() - lastBack < 2500) { finish(); return }
                lastBack = System.currentTimeMillis()
                Toast.makeText(this@MainActivity, "Aperte voltar de novo para sair", Toast.LENGTH_SHORT).show()
            }
        })
        start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        // ao voltar do player/detalhes: atualiza "continuar assistindo", favoritos e selos
        if (::content.isInitialized && shownVersion >= 0) refreshCurrent()
    }

    private fun showCrash(c: String) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(40), dp(28), dp(40), dp(28)); setBackgroundColor(C.bg) }
        col.addView(text("O app fechou sozinho da última vez", 22f, Color.WHITE, true))
        col.addView(text("Tire uma foto desta tela e mande para quem fez o app. Depois aperte Continuar.", 14f, C.tx2), llp(WRAP, WRAP).apply { topMargin = dp(8) })
        val btn = button("Continuar", true) { Store.put("crash", null); recreate() }
        col.addView(btn, llp(WRAP, WRAP).apply { topMargin = dp(14); bottomMargin = dp(14) })
        val sv = android.widget.ScrollView(this)
        sv.addView(text(c, 11f, C.tx3).apply { setTextIsSelectable(false) })
        col.addView(sv, llp(MATCH, 0, 1f))
        setContentView(col)
        btn.requestFocus()
    }

    private fun start() {
        lifecycleScope.launch {
            val hasCache = Repo.vod.isNotEmpty() || Repo.live.isNotEmpty() || withContext(Dispatchers.IO) { try { Repo.loadCache() } catch (e: Throwable) { false } }
            if (hasCache) {
                withContext(Dispatchers.IO) { try { Trend.ensure(false) } catch (e: Exception) {}; try { Discover.loadCached() } catch (e: Exception) {} }
                showAccount(); show(tab); setLoading(null)
                // atualiza por trás se o catálogo guardado tiver mais de 2 horas
                if (Repo.cacheAge() > 2 * 3600 * 1000L) refreshInBackground()
                else refreshTrend()
            } else {
                setLoading("Conectando ao servidor…")
                val err = withContext(Dispatchers.IO) { Repo.login() }
                if (err != null) { goLogin(err); return@launch }
                try {
                    withContext(Dispatchers.IO) { Repo.fetchAll { m -> runOnUiThread { setLoading(m) } } }
                } catch (e: Throwable) { goLogin(if (e is OutOfMemoryError) "A lista é grande demais para a memória deste aparelho." else (e.message ?: "Não consegui baixar a lista.")); return@launch }
                setLoading("Buscando o que está em alta…")
                withContext(Dispatchers.IO) { try { Trend.ensure(false) } catch (e: Exception) {}; try { Discover.loadCached() } catch (e: Exception) {} }
                showAccount(); show(tab); setLoading(null)
                refreshTrend()
            }
        }
    }

    private fun refreshInBackground() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { if (Repo.login() == null) { Repo.fetchAll { }; true } else false } catch (e: Throwable) { false }
            }
            if (ok) { showAccount(); refreshTrend() }
        }
    }

    private fun refreshTrend() {
        lifecycleScope.launch {
            val before = Discover.colls.size.toString() + "|" + Discover.because?.first?.key + "|" + Trend.updated + "|" + Discover.trendDay.size + "|" + Discover.trendWeek.size
            withContext(Dispatchers.IO) {
                try { Trend.ensure(Trend.isStale()) } catch (e: Exception) {}
                try { Discover.refresh() } catch (e: Exception) {}
            }
            val after = Discover.colls.size.toString() + "|" + Discover.because?.first?.key + "|" + Trend.updated + "|" + Discover.trendDay.size + "|" + Discover.trendWeek.size
            // só redesenha se algo mudou e a pessoa estiver no topo do Início (para não pular o foco)
            if (tab == "home" && before != after) {
                val f = currentFocus
                if (f == null || f.parent === tabs || isInHero(f)) render(keepFocus = true)
            }
        }
    }

    private fun isInHero(v: View): Boolean {
        var p: View? = v
        while (p != null) { if (p.tag == "hero") return true; p = p.parent as? View }
        return false
    }

    private fun goLogin(msg: String) {
        startActivity(Intent(this, LoginActivity::class.java).putExtra("msg", msg))
        finish()
    }

    /* ---------- estrutura ---------- */
    private fun buildShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(C.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(14), dp(40), dp(10))
        }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.logo_brtv); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }, llp(WRAP, dp(40)))
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("home" to "Início", "live" to "Ao vivo", "movie" to "Filmes", "series" to "Séries", "mylist" to "Minha lista", "search" to "Buscar").forEach { (k, n) ->
            tabs.addView(tabView(k, n))
        }
        header.addView(tabs, llp(WRAP, WRAP).apply { marginStart = dp(28) })
        header.addView(View(this), llp(0, 1, 1f))
        acct = text("", 11f, C.tx3).apply { gravity = Gravity.END }
        header.addView(acct)
        header.addView(button("Sair") { confirmLogout() }.apply { textSize = 12f; setPadding(dp(12), dp(6), dp(12), dp(6)) }, llp(WRAP, WRAP).apply { marginStart = dp(14) })
        col.addView(header, llp(MATCH, WRAP))
        content = FrameLayout(this)
        col.addView(content, llp(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        rootF = root
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, nv -> onAnyFocus(nv) }
        loading = splashView("Carregando…", !fastStart)
        root.addView(loading, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    private fun setLoading(msg: String?) {
        if (msg == null) {
            val wait = if (fastStart) 0L else 1800 - (System.currentTimeMillis() - openedAt)
            if (wait > 0) loading.postDelayed({ loading.visibility = View.GONE }, wait) else loading.visibility = View.GONE
        } else { loading.visibility = View.VISIBLE; loadingTxt.text = msg }
    }

    /** tela de abertura: logo + "Desenvolvido por Bruno Carvalho" */
    private fun splashView(msg: String?, animate: Boolean = false): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(C.bg); isClickable = true
        }
        val logo = ImageView(this).apply { setImageResource(R.drawable.logo_brtv); adjustViewBounds = true }
        val credit = text("Desenvolvido por Bruno Carvalho", 12f, C.tx3).apply { gravity = Gravity.CENTER; letterSpacing = 0.04f }
        v.addView(logo, llp(WRAP, dp(170)))
        v.addView(credit, llp(WRAP, WRAP).apply { topMargin = dp(14) })
        if (animate) {
            logo.alpha = 0f; logo.scaleX = 0.82f; logo.scaleY = 0.82f
            credit.alpha = 0f; credit.translationY = dpf(8f)
            logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(750)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
            credit.animate().alpha(1f).translationY(0f).setStartDelay(650).setDuration(500).start()
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        bottom.addView(ProgressBar(this), llp(dp(30), dp(30)))
        loadingTxt = text(msg ?: "", 13f, C.tx2).apply { gravity = Gravity.CENTER }
        bottom.addView(loadingTxt, llp(WRAP, WRAP).apply { topMargin = dp(10) })
        v.addView(bottom, llp(WRAP, WRAP).apply { topMargin = dp(40) })
        return v
    }

    private fun tabView(key: String, label: String): TextView = text(label, 15f, C.tx2).apply {
        tag = key
        isFocusable = true; isClickable = true
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnFocusChangeListener { _, _ -> paintTabs() }
        setOnClickListener { show(key) }
    }

    private fun paintTabs() {
        for (i in 0 until tabs.childCount) {
            val t = tabs.getChildAt(i) as TextView
            val on = t.tag == tab
            val f = t.isFocused
            t.setTextColor(if (on || f) Color.WHITE else C.tx2)
            t.paint.isFakeBoldText = on
            t.background = when {
                f -> rounded(0x33FFFFFF, dpf(8f))
                on -> underline()
                else -> null
            }
            t.invalidate()
        }
    }

    private fun underline(): android.graphics.drawable.Drawable {
        val line = GradientDrawable().apply { setColor(C.ac) }
        return android.graphics.drawable.LayerDrawable(arrayOf(line)).apply {
            setLayerInset(0, 0, dp(30), 0, 0)
        }
    }

    private fun showAccount() {
        val exp = if (Repo.expDate > 0) "\naté " + SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date(Repo.expDate * 1000)) else ""
        acct.text = Repo.userName.ifEmpty { Xt.user } + exp
    }

    /* ---------- capa em destaque ao selecionar (como na Netflix) ---------- */
    private lateinit var rootF: FrameLayout
    private var pop: View? = null
    private var popJob: Job? = null
    private var browseHero: View? = null
    private var browseHeroH = 0
    private var browseHeroOpen = true

    private fun isInside(v: View, tagName: String): Boolean {
        var p: View? = v
        while (p != null) { if (p.tag == tagName) return true; p = p.parent as? View }
        return false
    }

    private fun onAnyFocus(v: View?) {
        // destaque do topo em Filmes/Séries: recolhe quando você entra na grade e volta quando sai dela
        browseHero?.let { h -> if (v != null) setBrowseHero(h, !isInside(v, "grid")) }
        hidePop()
        val fv = v ?: return
        val it = (fv.tag as? Card)?.item ?: return
        if (it.kind == Kind.LIVE) return
        popJob = lifecycleScope.launch {
            delay(600)
            if (currentFocus === fv && fv.isShown) showPop(fv, it)
        }
    }

    private fun setBrowseHero(h: View, open: Boolean) {
        if (open == browseHeroOpen || browseHeroH <= 0) return
        browseHeroOpen = open
        val from = h.layoutParams.height
        val to = if (open) browseHeroH else 0
        android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = 220
            addUpdateListener { va -> h.layoutParams = h.layoutParams.apply { height = va.animatedValue as Int }; h.requestLayout() }
            start()
        }
    }

    private fun hidePop() {
        popJob?.cancel()
        pop?.let { p -> rootF.removeView(p) }
        pop = null
    }

    /** cores da classificação indicativa do Brasil */
    private fun certView(): TextView = text("", 11f, Color.WHITE, true).apply {
        setPadding(dp(5), dp(1), dp(5), dp(1)); visibility = View.GONE
    }

    private fun setCert(tv: TextView, c: String) {
        val k = c.trim().uppercase().removePrefix("A")
        val (bg, fg) = when (k) {
            "L", "LIVRE" -> 0xFF0C9447.toInt() to Color.WHITE
            "10" -> 0xFF0F7DC2.toInt() to Color.WHITE
            "12" -> 0xFFF8C411.toInt() to Color.BLACK
            "14" -> 0xFFE67824.toInt() to Color.WHITE
            "16" -> 0xFFDB2827.toInt() to Color.WHITE
            "18" -> 0xFF1D1815.toInt() to Color.WHITE
            else -> { tv.visibility = View.GONE; return }
        }
        tv.text = if (k == "LIVRE") "L" else k
        tv.setTextColor(fg)
        tv.background = rounded(bg, dpf(3f), if (k == "18") dp(1) else 0, 0x66FFFFFF)
        tv.visibility = View.VISIBLE
    }

    private fun showPop(v: View, it: Item) {
        val w = dp(300)
        val imgH = w * 9 / 16
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF181A20.toInt(), dpf(10f))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = dpf(24f)
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val top = FrameLayout(this)
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(C.colorFor(it.name)) }
        top.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        top.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6181A20.toInt(), 0x00181A20)) }, FrameLayout.LayoutParams(MATCH, imgH / 2, Gravity.BOTTOM))
        val logo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        top.addView(logo, FrameLayout.LayoutParams(dp(170), dp(54), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(10)) })
        val ttl = text(it.name, 16f, Color.WHITE, true, 2)
        top.addView(ttl, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(10)) })
        card.addView(top, llp(MATCH, imgH))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12)) }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val kindTv = text(if (it.kind == Kind.SERIES) "Série" else "Filme", 12.5f, 0xFFBCBEC6.toInt())
        val cert = certView()
        val len = text("", 12.5f, 0xFFBCBEC6.toInt())
        val sc = text("", 12.5f, C.ac, true)
        r1.addView(kindTv)
        r1.addView(cert, llp(WRAP, WRAP).apply { marginStart = dp(10) })
        r1.addView(len, llp(WRAP, WRAP).apply { marginStart = dp(10) })
        r1.addView(sc, llp(WRAP, WRAP).apply { marginStart = dp(10) })
        val gen = text("", 12.5f, Color.WHITE, true, 1)
        val extra = text("", 11.5f, C.ac, false, 1)
        info.addView(r1)
        info.addView(gen, llp(MATCH, WRAP).apply { topMargin = dp(7) })
        info.addView(extra, llp(MATCH, WRAP).apply { topMargin = dp(5) })
        card.addView(info, llp(MATCH, WRAP))

        val s = scoreOf(it)
        sc.text = listOfNotNull(if (s > 0) "★ " + String.format(Locale("pt", "BR"), "%.1f", s) else null, if (it.year > 0) it.year.toString() else null).joinToString("   ")
        val p = Lib.prog(it.key)
        extra.text = if (Lib.isSeen(it.key)) "✓ Você já assistiu" else if (p != null && p.dur > 0) "Faltam " + fmtDur(p.dur - p.pos) else ""
        extra.visibility = if (extra.text.isEmpty()) View.GONE else View.VISIBLE
        fun fillMeta(mi: Discover.MetaInfo?) {
            setCert(cert, mi?.cert ?: "")
            len.text = mi?.len ?: ""
            val g = mi?.genres?.takeIf { l -> l.isNotEmpty() } ?: it.genre.split(",", "/").map { x -> x.trim() }.filter { x -> x.isNotEmpty() }.take(3)
                .ifEmpty { listOf(Repo.catName(it.kind, it.cat)).filter { x -> x.isNotEmpty() } }
            gen.text = g.joinToString("  •  ")
        }
        fun fillArt(a: Discover.ArtInfo?) {
            val bd = Art.backdrop(it).ifEmpty { a?.backdrop ?: "" }.ifEmpty { it.icon }
            if (bd.isNotEmpty()) img.load(bd) { crossfade(200) }
            val l = a?.logo ?: ""
            if (l.isNotEmpty()) logo.load(l) {
                listener(onSuccess = { _, _ -> logo.visibility = View.VISIBLE; ttl.visibility = View.GONE })
            }
        }
        val mc = Discover.metaCached(it); val ac = Discover.artCached(it)
        fillMeta(mc); fillArt(ac)
        if (mc == null || ac == null) lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                (try { Discover.meta(it) } catch (e: Exception) { null }) to (try { Discover.art(it) } catch (e: Exception) { null })
            }
            if (pop === card) { if (mc == null) fillMeta(r.first); if (ac == null) fillArt(r.second) }
        }

        // posição: centralizada em cima da capa, sem sair da tela
        val loc = IntArray(2); v.getLocationInWindow(loc)
        val rl = IntArray(2); rootF.getLocationInWindow(rl)
        val cx = loc[0] - rl[0] + (v.width * v.scaleX).toInt() / 2
        val cy = loc[1] - rl[1] + (v.height * v.scaleY).toInt() / 2
        val estH = imgH + dp(86)
        val left = (cx - w / 2).coerceIn(dp(12), maxOf(dp(12), rootF.width - w - dp(12)))
        val topY = (cy - estH / 2).coerceIn(dp(60), maxOf(dp(60), rootF.height - estH - dp(8)))
        rootF.addView(card, FrameLayout.LayoutParams(w, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = left; topMargin = topY })
        pop = card
        card.alpha = 0f; card.scaleX = 0.82f; card.scaleY = 0.82f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
    }

    /* ---------- tirar de "Continuar assistindo" (segurar OK) ---------- */
    private fun askRemove(p: Prog) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remover de Continuar assistindo?")
            .setMessage(p.name + (if (p.sub.isNotEmpty()) " · " + p.sub else ""))
            .setPositiveButton("Remover") { _, _ ->
                Lib.dropProg(p.key)
                Toast.makeText(this, "Removido de Continuar assistindo", Toast.LENGTH_SHORT).show()
                refreshCurrent()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmLogout() {
        if (System.currentTimeMillis() - lastBack < 3000) {
            Repo.logout()
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        lastBack = System.currentTimeMillis()
        Toast.makeText(this, "Aperte Sair de novo para sair da lista", Toast.LENGTH_SHORT).show()
    }

    /* ---------- troca de aba ---------- */
    private fun show(key: String) {
        tab = key
        paintTabs()
        render(false)
    }

    /** resumo do que muda ao assistir/favoritar (para só redesenhar quando precisa) */
    private fun libSig(): String =
        Lib.progs().size.toString() + "/" + Lib.progs().take(6).joinToString(",") { it.key + ":" + it.pct } + "|" + Lib.favs().take(30).joinToString(",") + "|" + Lib.seenKeys('m').size

    private fun refreshCurrent() {
        if (tab == "home" || tab == "mylist") {
            val sig = libSig()
            val rv = homeRv
            if (sig == lastSig || rv == null) {
                // nada mudou: só atualiza os selos das capas, sem mexer na posição
                rv?.let { r -> for (i in 0 until r.childCount) ((r.getChildAt(i) as? ViewGroup)?.getChildAt(1) as? RecyclerView)?.adapter?.notifyDataSetChanged() }
                return
            }
            // mudou (ex.: novo "continuar assistindo"): redesenha mantendo a rolagem e o foco
            val state = rv.layoutManager?.onSaveInstanceState()
            var rowPos = -1
            var cardPos = -1
            currentFocus?.let { f ->
                var v: View? = f
                var child: View? = null
                while (v != null && v.parent !== rv) { if ((v.parent as? RecyclerView) != null && v.parent !== rv) child = v; v = v.parent as? View }
                if (v != null) rowPos = rv.getChildAdapterPosition(v)
                child?.let { c -> (c.parent as? RecyclerView)?.let { inner -> cardPos = inner.getChildAdapterPosition(c) } }
            }
            render(false)
            val nrv = homeRv ?: return
            state?.let { nrv.layoutManager?.onRestoreInstanceState(it) }
            if (rowPos >= 0) nrv.post {
                val row = nrv.findViewHolderForAdapterPosition(rowPos)?.itemView
                val inner = (row as? ViewGroup)?.getChildAt(1) as? RecyclerView
                if (inner != null && cardPos >= 0) {
                    inner.scrollToPosition(cardPos)
                    inner.post { (inner.findViewHolderForAdapterPosition(cardPos)?.itemView ?: inner.getChildAt(0))?.requestFocus() }
                } else row?.requestFocus()
            }
        } else {
            // só atualiza os selos das capas
            content.findViewWithTag<RecyclerView>("grid")?.adapter?.notifyDataSetChanged()
        }
    }

    private fun render(keepFocus: Boolean) {
        shownVersion = Repo.version
        homeRv = null
        browseHero = null; browseHeroOpen = true
        hidePop()
        content.removeAllViews()
        try {
            when (tab) {
                "home" -> content.addView(homeView())
                "live" -> content.addView(browseView(Kind.LIVE))
                "movie" -> content.addView(browseView(Kind.MOVIE))
                "series" -> content.addView(browseView(Kind.SERIES))
                "mylist" -> content.addView(myListView())
                "search" -> content.addView(searchView())
            }
        } catch (e: Throwable) {
            content.removeAllViews()
            content.addView(text("Erro ao montar a tela. Tire uma foto e mande para quem fez o app.\n\n" + android.util.Log.getStackTraceString(e).take(2500), 12f, C.tx2).apply { setPadding(dp(40), dp(20), dp(40), dp(20)) })
        }
    }

    /* ---------- abrir itens ---------- */
    fun open(it: Item, channelList: List<Item>? = null) {
        when (it.kind) {
            Kind.LIVE -> {
                Now.channels = channelList ?: Repo.live
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("mode", "live").putExtra("id", it.id))
            }
            else -> startActivity(Intent(this, DetailActivity::class.java).putExtra("kind", it.kind.name).putExtra("id", it.id))
        }
    }

    private fun resume(p: Prog) {
        Now.series = null; Now.episodes = emptyList()
        val i = Intent(this, PlayerActivity::class.java)
            .putExtra("mode", if (p.kind == "series") "episode" else "movie")
            .putExtra("id", p.id).putExtra("ext", p.ext).putExtra("title", p.name + if (p.sub.isNotEmpty()) " · " + p.sub else "")
            .putExtra("key", p.key).putExtra("icon", p.icon).putExtra("name", p.name).putExtra("sub", p.sub)
            .putExtra("sid", p.sid).putExtra("start", p.pos).putExtra("img", p.img).putExtra("ept", p.ept)
            .putExtra("plot", (if (p.kind == "series") Repo.find(Kind.SERIES, p.sid) else Repo.findKey(p.key))?.plot ?: "")
        startActivity(i)
    }

    private fun card(it: Item, list: List<Item>? = null, rank: Int = 0, onFocus: (() -> Unit)? = null) =
        Card(it, it.name, it.kind, rank = rank, onFocus = onFocus) { open(it, list) }

    /* ---------- Início ---------- */
    private fun homeView(): View {
        val e = ArrayList<HomeEntry>()
        val movies = Repo.recent(Repo.vod)
        val heroes = homeHeroes()
        if (heroes.isNotEmpty()) e.add(HomeEntry.Hero(heroView(heroes, true)))
        val cont = Lib.progs().filter { it.dur > 0 && !Lib.isSeen(it.key) }.take(20)
        if (cont.isNotEmpty()) e.add(HomeEntry.Row("Continuar assistindo", extra = "segure OK para remover", cards = cont.map { p ->
            Card(null, p.name, Kind.MOVIE, p.icon, if (p.sub.isNotEmpty()) p.sub else "Faltam " + fmtDur(p.dur - p.pos), p.pct, onLong = { askRemove(p) }) { resume(p) }
        }))
        val favs = Lib.favs().mapNotNull { Repo.findKey(it) }
        val myl = favs.filter { it.kind != Kind.LIVE }.take(30)
        if (myl.isNotEmpty()) e.add(HomeEntry.Row("Minha lista", myl.map { card(it) }))
        val fch = favs.filter { it.kind == Kind.LIVE }
        if (fch.isNotEmpty()) e.add(HomeEntry.Row("Canais favoritos", fch.map { card(it, fch) }))
        val upd = if (Trend.updated > 0) "no seu catálogo · atualizado às " + SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(Trend.updated)) else "no seu catálogo"
        // "Porque você assistiu" logo no começo
        Discover.because?.let { (src, l) -> e.add(HomeEntry.Row("Porque você assistiu " + src.name, l.map { card(it) })) }
        // tendências do TMDB que existem na sua lista
        if (Discover.trendDay.size >= 3) e.add(HomeEntry.Row("Tendências de hoje", Discover.trendDay.map { card(it) }, extra = "no seu catálogo"))
        if (Discover.trendWeek.size >= 3) e.add(HomeEntry.Row("Tendências da semana", Discover.trendWeek.map { card(it) }, extra = "no seu catálogo"))
        // Top 10 e coleções intercalados (as coleções não ficam escondidas lá embaixo)
        val top10 = ArrayList<HomeEntry>()
        listOf(Kind.MOVIE to "filmes", Kind.SERIES to "séries").forEach { (k, label) ->
            Trend.forKind(k).forEach { r ->
                top10.add(HomeEntry.Row("Top 10 $label na", r.items.take(10).mapIndexed { i, it -> card(it, rank = i + 1) }, r.plat.n, r.plat.color, upd, ranked = true))
            }
        }
        val colls = Discover.colls.map { c -> HomeEntry.Row(c.title, c.items.map { card(it) }) }
        var ti = 0; var ci = 0
        // 2 de Top 10, depois alterna 1 coleção e 1 Top 10
        while (ti < top10.size && ti < 2) e.add(top10[ti++])
        while (ti < top10.size || ci < colls.size) {
            if (ci < colls.size) e.add(colls[ci++])
            if (ti < top10.size) e.add(top10[ti++])
        }
        if (Trend.rows.isEmpty() && Trend.error != null) e.add(HomeEntry.Note("Top 10 das plataformas: não consegui falar com o TMDB (${Trend.error}). Tento de novo mais tarde."))
        if (Repo.live.isNotEmpty()) e.add(HomeEntry.Row("Canais ao vivo", Repo.live.take(24).map { card(it) }, live = true))
        if (movies.isNotEmpty()) e.add(HomeEntry.Row("Filmes adicionados recentemente", movies.take(30).map { card(it) }))
        val sers = Repo.recent(Repo.series)
        if (sers.isNotEmpty()) e.add(HomeEntry.Row("Séries adicionadas recentemente", sers.take(30).map { card(it) }))
        Repo.vodCats.take(6).forEach { c -> val l = Repo.vod.filter { it.cat == c.id }.take(30); if (l.isNotEmpty()) e.add(HomeEntry.Row(c.name, l.map { card(it) })) }
        Repo.serCats.take(3).forEach { c -> val l = Repo.series.filter { it.cat == c.id }.take(30); if (l.isNotEmpty()) e.add(HomeEntry.Row("Séries · " + c.name, l.map { card(it) })) }
        e.add(HomeEntry.Note("Sua lista: ${Repo.live.size} canais · ${Repo.vod.size} filmes · ${Repo.series.size} séries · ${Discover.colls.size} coleções"))
        lastSig = libSig()
        return RecyclerView(this).apply {
            homeRv = this
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = RowsAdapter(e)
            clipChildren = false; clipToPadding = false
            setPadding(0, 0, 0, dp(40))
            setItemViewCacheSize(6)
        }
    }

    /** nota que vale para o destaque: a da lista ou, se não tiver, a do TMDB */
    private fun scoreOf(it: Item): Double = if (it.rating > 0) it.rating else Trend.votes[it.key] ?: 0.0

    /**
     * escolhe os destaques de um tipo (filmes ou séries):
     * 1) em alta nas plataformas e que estão na lista, com nota 6,5 ou mais
     * 2) se faltar, lançamentos recentes com nota 7 ou mais
     * 3) em último caso, o mais recente com capa
     */
    private fun heroCandidates(kind: Kind, max: Int = 8): List<Pair<Item, String>> {
        val recent = Repo.recent(Repo.list(kind))
        val out = ArrayList<Pair<Item, String>>()
        val seen = HashSet<String>()
        val rows = Trend.forKind(kind)
        for (pos in 0 until 10) {
            rows.forEach { r ->
                val it = r.items.getOrNull(pos) ?: return@forEach
                if (it.icon.isNotEmpty() && scoreOf(it) >= 6.5 && seen.add(it.id)) out.add(it to "Em alta na " + r.plat.n)
            }
        }
        if (out.size < 3) {
            recent.take(400).filter { it.icon.isNotEmpty() && it.rating >= 7.0 && it.id !in seen }
                .sortedByDescending { it.rating }.take(max - out.size)
                .forEach { seen.add(it.id); out.add(it to "Achamos que você vai amar isto!") }
        }
        if (out.isEmpty()) (recent.firstOrNull { it.icon.isNotEmpty() } ?: recent.firstOrNull())?.let { out.add(it to "Adicionado recentemente") }
        val top = out.take(max)
        if (top.size <= 1) return top
        // começa num destaque diferente a cada vez que abre
        val start = (System.currentTimeMillis() / 1000 % top.size).toInt()
        return top.drop(start) + top.take(start)
    }

    /** destaques do Início: filmes e algumas séries misturados */
    private fun homeHeroes(): List<Pair<Item, String>> {
        val mv = heroCandidates(Kind.MOVIE, 6)
        val sr = heroCandidates(Kind.SERIES, 2)
        val out = ArrayList<Pair<Item, String>>()
        var i = 0; var j = 0
        while (i < mv.size || j < sr.size) {
            if (i < mv.size) out.add(mv[i++])
            if (i < mv.size) out.add(mv[i++])
            if (i < mv.size) out.add(mv[i++])
            if (j < sr.size) out.add(sr[j++])
        }
        return out
    }

    private var heroTimer: Runnable? = null

    /** destaque do topo: cartão arredondado com a imagem larga, logo oficial, dados e botões */
    private fun heroView(list: List<Pair<Item, String>>, big: Boolean): View {
        val outer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, dp(if (big) 318 else 232)); tag = "hero"
            setPadding(dp(40), dp(4), dp(40), dp(if (big) 14 else 10))
        }
        val f = FrameLayout(this).apply {
            background = rounded(C.bg3, dpf(14f))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        outer.addView(f, FrameLayout.LayoutParams(MATCH, MATCH))
        val bd = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(bd, FrameLayout.LayoutParams(MATCH, MATCH))
        f.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF00A0B0F.toInt(), 0xB30A0B0F.toInt(), 0x330A0B0F, 0x000A0B0F))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        f.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC0A0B0F.toInt(), 0x000A0B0F)) }, FrameLayout.LayoutParams(MATCH, dp(120), Gravity.BOTTOM))
        // capa em pé (só quando não existe imagem larga)
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(C.bg3, dpf(10f)); clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = dpf(12f); visibility = View.GONE
        }
        val ph = if (big) 230 else 160
        f.addView(poster, FrameLayout.LayoutParams(dp(ph * 2 / 3), dp(ph), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(60) })
        // selo do canto (ex.: "Em alta na Netflix", "Achamos que você vai amar isto!")
        val chip = text("", 12f, Color.WHITE, true).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(0xB3101114.toInt(), dpf(6f))
        }
        f.addView(chip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(if (big) 22 else 16)) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(dp(30), 0, 0, dp(if (big) 20 else 14)) }
        val title = text("", if (big) 34f else 26f, Color.WHITE, true, 2).apply { setLineSpacing(0f, 0.95f) }
        col.addView(title, llp(dp(520), WRAP))
        // logo oficial do título (TMDB), no lugar do nome escrito quando existe
        val logo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        col.addView(logo, llp(dp(if (big) 380 else 300), dp(if (big) 100 else 70)))
        val metaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val meta = text("", 13.5f, Color.WHITE, true)
        val cert = certView()
        metaRow.addView(meta)
        metaRow.addView(cert, llp(WRAP, WRAP).apply { marginStart = dp(10) })
        col.addView(metaRow, llp(WRAP, WRAP).apply { topMargin = dp(if (big) 12 else 8) })
        val plot = text("", if (big) 14f else 12.5f, 0xFFE6E7EA.toInt(), false, if (big) 3 else 2).apply { setLineSpacing(0f, 1.2f) }
        col.addView(plot, llp(dp(if (big) 520 else 560), WRAP).apply { topMargin = dp(if (big) 10 else 6) })
        val dots = text("", 12f, C.tx3)
        var idx = 0
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        btns.addView(button("▶  Assistir", true) {
            val it = list[idx].first
            if (it.kind == Kind.SERIES) open(it)
            else startActivity(Intent(this, PlayerActivity::class.java).putExtra("mode", "movie").putExtra("id", it.id).putExtra("ext", it.ext)
                .putExtra("title", it.name).putExtra("key", it.key).putExtra("icon", it.icon).putExtra("name", it.name)
                .putExtra("plot", it.plot).putExtra("img", Art.backdrop(it)))
        })
        btns.addView(button("ⓘ  Mais informações") { open(list[idx].first) }, llp(WRAP, WRAP).apply { marginStart = dp(12) })
        if (big) btns.addView(button("Sortear filme") {
            val pick = Discover.randomPick()
            if (pick == null) Toast.makeText(this, "Nenhum filme para sortear", Toast.LENGTH_SHORT).show()
            else { Toast.makeText(this, "Que tal: " + pick.name + "?", Toast.LENGTH_SHORT).show(); open(pick) }
        }, llp(WRAP, WRAP).apply { marginStart = dp(12) })
        btns.addView(dots, llp(WRAP, WRAP).apply { marginStart = dp(18) })
        col.addView(btns, llp(WRAP, WRAP).apply { topMargin = dp(if (big) 14 else 10) })
        f.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        fun showHero(i: Int) {
            idx = i
            val (it, label) = list[i]
            chip.text = label
            title.text = it.name
            title.visibility = View.VISIBLE; logo.visibility = View.GONE
            fun showLogo(url: String) {
                if (url.isEmpty()) return
                logo.load(url) {
                    crossfade(250)
                    listener(onSuccess = { _, _ -> if (idx == i) { logo.visibility = View.VISIBLE; title.visibility = View.GONE } },
                        onError = { _, _ -> logo.visibility = View.GONE; title.visibility = View.VISIBLE })
                }
            }
            fun showBd(wide: String) {
                if (wide.isNotEmpty()) { bd.alpha = 1f; bd.load(wide) { crossfade(400) }; poster.visibility = View.GONE }
                else {
                    bd.alpha = 0.35f
                    if (it.icon.isNotEmpty()) { bd.load(it.icon) { crossfade(400) }; poster.load(it.icon) { crossfade(400) }; poster.visibility = View.VISIBLE }
                    else { bd.setImageDrawable(null); poster.visibility = View.GONE }
                }
            }
            fun showMeta(mi: Discover.MetaInfo?) {
                val m = ArrayList<String>()
                m.add(if (it.kind == Kind.SERIES) "Série" else "Filme")
                val g = mi?.genres?.firstOrNull() ?: it.genre.split(",", "/").map { x -> x.trim() }.firstOrNull { x -> x.isNotEmpty() } ?: Repo.catName(it.kind, it.cat)
                if (!g.isNullOrEmpty()) m.add(g)
                if (it.year > 0) m.add(it.year.toString())
                mi?.len?.takeIf { x -> x.isNotEmpty() }?.let { x -> m.add(x) }
                scoreOf(it).takeIf { s -> s > 0 }?.let { s -> m.add("★ " + String.format(Locale("pt", "BR"), "%.1f", s)) }
                meta.text = m.joinToString("  •  ")
                setCert(cert, mi?.cert ?: "")
            }
            val cached = Discover.artCached(it)
            val mc = Discover.metaCached(it)
            showLogo(cached?.logo ?: "")
            showBd(Art.backdrop(it).ifEmpty { cached?.backdrop ?: "" })
            showMeta(mc)
            plot.text = it.plot
            if (cached == null || mc == null) lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    (if (cached == null) (try { Discover.art(it) } catch (e: Exception) { null }) else cached) to
                        (if (mc == null) (try { Discover.meta(it) } catch (e: Exception) { null }) else mc)
                }
                if (idx == i) {
                    r.first?.let { a -> if (cached == null) { showLogo(a.logo); showBd(Art.backdrop(it).ifEmpty { a.backdrop }) } }
                    if (mc == null) showMeta(r.second)
                }
            }
            dots.text = if (list.size > 1) list.indices.joinToString(" ") { d -> if (d == i) "●" else "○" } else ""
            if (it.plot.isEmpty() && it.kind == Kind.MOVIE) lifecycleScope.launch {
                val p = withContext(Dispatchers.IO) { try { Repo.vodInfo(it.id).s("plot") } catch (e: Exception) { "" } }
                if (p.isNotEmpty()) { it.plot = p; if (idx == i) plot.text = p }
            }
        }
        showHero(0)
        lifecycleScope.launch(Dispatchers.IO) {
            list.forEach { (x, _) -> try { if (Discover.artCached(x) == null) Discover.art(x); if (Discover.metaCached(x) == null) Discover.meta(x) } catch (e: Exception) {} }
        }
        // troca o destaque sozinho a cada 12 segundos
        if (list.size > 1) {
            val r = object : Runnable {
                override fun run() {
                    if (outer.isAttachedToWindow && outer.isShown) showHero((idx + 1) % list.size)
                    outer.postDelayed(this, 12000)
                }
            }
            heroTimer = r
            outer.postDelayed(r, 12000)
        }
        return outer
    }

    /* ---------- Ao vivo / Filmes / Séries ---------- */
    private fun browseView(kind: Kind): View {
        val list = Repo.list(kind)
        val counts = HashMap<String, Int>()
        list.forEach { counts[it.cat] = (counts[it.cat] ?: 0) + 1 }
        data class Entry(val id: String, val name: String, val items: () -> List<Item>, val count: Int)
        val entries = ArrayList<Entry>()
        // favoritos sempre no topo (no Ao vivo aparece mesmo vazio, para lembrar que existe)
        val favList = Lib.favs().mapNotNull { Repo.findKey(it) }.filter { it.kind == kind }
        if (kind == Kind.LIVE || favList.isNotEmpty()) entries.add(Entry("fav", if (kind == Kind.LIVE) "★ Favoritos" else "★ Minha lista", { favList }, favList.size))
        entries.add(Entry("*", if (kind == Kind.LIVE) "Todos os canais" else "Todos", { list }, list.size))
        if (kind != Kind.LIVE) {
            entries.add(Entry("new", "Adicionados recentemente", { Repo.recent(list).take(200) }, minOf(200, list.size)))
            Trend.forKind(kind).forEach { r -> entries.add(Entry("pf:" + r.plat.k, "Em alta · " + r.plat.n, { r.items }, r.items.size)) }
            Discover.trendDay.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { l -> entries.add(Entry("td", "Tendências de hoje", { l }, l.size)) }
            Discover.trendWeek.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { l -> entries.add(Entry("tw", "Tendências da semana", { l }, l.size)) }
            Discover.colls.filter { it.kind == kind }.forEach { c -> entries.add(Entry("col:" + c.title, "Coleção · " + c.title, { c.items }, c.items.size)) }
        }
        Repo.catsOf(kind).forEach { c -> counts[c.id]?.let { n -> entries.add(Entry(c.id, c.name, { list.filter { it.cat == c.id } }, n)) } }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(34), dp(6), dp(30), 0) }
        val catList = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity) }
        row.addView(catList, llp(dp(210), MATCH))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, 0, 0) }
        val epg = text("Escolha um canal\nO que está passando aparece aqui.", 13f, C.tx2).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(C.bg2, dpf(8f), dp(1), C.line)
            setLineSpacing(0f, 1.3f)
            visibility = if (kind == Kind.LIVE) View.VISIBLE else View.GONE
        }
        right.addView(epg, llp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        val title = text("", 22f, Color.WHITE, true)
        right.addView(title, llp(MATCH, WRAP).apply { bottomMargin = dp(4); marginStart = dp(6) })
        val span = if (kind == Kind.LIVE) 4 else 6
        val grid = RecyclerView(this).apply {
            tag = "grid"
            layoutManager = GridLayoutManager(this@MainActivity, span)
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(4), 0, dp(40))
            setItemViewCacheSize(12)
        }
        right.addView(grid, llp(MATCH, 0, 1f))
        row.addView(right, llp(0, MATCH, 1f))

        var current = (getPreferences(Context.MODE_PRIVATE).getString("cat_$kind", "*") ?: "*")
        // o Ao vivo abre direto nos favoritos quando existe algum
        if (kind == Kind.LIVE && favList.isNotEmpty()) current = "fav"
        if (kind == Kind.LIVE && favList.isEmpty() && current == "fav") current = "*"
        fun pick(id: String, focusGrid: Boolean) {
            current = id
            getPreferences(Context.MODE_PRIVATE).edit().putString("cat_$kind", id).apply()
            val en = entries.firstOrNull { it.id == id } ?: entries[0]
            title.text = if (en.id == "fav" && en.count == 0)
                (if (kind == Kind.LIVE) "★ Favoritos — nenhum ainda. Abra um canal, aperte OK e escolha Favoritar canal." else "★ Minha lista está vazia")
            else en.name
            val items = en.items()
            grid.adapter = CardAdapter(items.map { it ->
                Card(it, it.name, kind, onFocus = if (kind == Kind.LIVE) ({ showEpg(epg, it) }) else null) { open(it, if (kind == Kind.LIVE) items else null) }
            }, fill = true)
            catList.adapter?.notifyDataSetChanged()
            if (focusGrid) grid.post { grid.getChildAt(0)?.requestFocus() }
        }
        catList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = entries.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = text("", 14f, C.tx2, false, 1).apply {
                    isFocusable = true; isClickable = true
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val en = entries[position]
                val tv = holder.itemView as TextView
                tv.text = en.name + "   " + en.count
                val on = en.id == current
                fun paint(f: Boolean) {
                    tv.setTextColor(if (on || f) Color.WHITE else C.tx2)
                    tv.paint.isFakeBoldText = on
                    tv.background = when { f -> rounded(0x33FFFFFF, dpf(6f), dp(2), C.ac); on -> rounded(0x24F5D400, dpf(6f)); else -> null }
                }
                paint(tv.isFocused)
                tv.setOnFocusChangeListener { _, f -> paint(f) }
                tv.setOnClickListener { pick(en.id, true) }
            }
        }
        pick(current, false)
        if (kind == Kind.LIVE) return row
        val heroes = heroCandidates(kind)
        if (heroes.isEmpty()) return row
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hero = heroView(heroes, false)
        browseHeroH = hero.layoutParams.height
        wrap.addView(hero, llp(MATCH, browseHeroH))
        wrap.addView(row, llp(MATCH, 0, 1f))
        browseHero = hero
        browseHeroOpen = true
        return wrap
    }

    private fun showEpg(box: TextView, it: Item) {
        box.text = it.name + "\nCarregando programação…"
        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            delay(350)
            val txt = withContext(Dispatchers.IO) {
                try {
                    val o = org.json.JSONObject(Xt.get(mapOf("action" to "get_short_epg", "stream_id" to it.id, "limit" to "2")))
                    val a = o.optJSONArray("epg_listings")
                    if (a == null || a.length() == 0) "Sem programação disponível para este canal."
                    else (0 until a.length()).joinToString("\n") { i ->
                        val p = a.getJSONObject(i)
                        val t = try { String(Base64.decode(p.s("title"), Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { p.s("title") }
                        val h = p.s("start").let { s -> if (s.length >= 16) s.substring(11, 16) else "" }
                        (if (i == 0) "Agora" else "Depois") + " · $h — $t"
                    }
                } catch (e: Exception) { "Sem programação disponível." }
            }
            box.text = it.name + "\n" + txt
        }
    }

    /* ---------- Minha lista ---------- */
    private fun myListView(): View {
        val e = ArrayList<HomeEntry>()
        val cont = Lib.progs().filter { it.dur > 0 }.take(30)
        if (cont.isNotEmpty()) e.add(HomeEntry.Row("Continuar assistindo", cont.map { p ->
            Card(null, p.name, Kind.MOVIE, p.icon, if (p.sub.isNotEmpty()) p.sub else "Faltam " + fmtDur(p.dur - p.pos), p.pct, onLong = { askRemove(p) }) { resume(p) }
        }))
        val favs = Lib.favs().mapNotNull { Repo.findKey(it) }
        favs.filter { it.kind == Kind.MOVIE }.takeIf { it.isNotEmpty() }?.let { l -> e.add(HomeEntry.Row("Filmes na minha lista", l.map { card(it) })) }
        favs.filter { it.kind == Kind.SERIES }.takeIf { it.isNotEmpty() }?.let { l -> e.add(HomeEntry.Row("Séries na minha lista", l.map { card(it) })) }
        favs.filter { it.kind == Kind.LIVE }.takeIf { it.isNotEmpty() }?.let { l -> e.add(HomeEntry.Row("Canais favoritos", l.map { card(it, l) })) }
        val seen = Lib.seenKeys('m').mapNotNull { Repo.findKey(it) }.take(40)
        if (seen.isNotEmpty()) e.add(HomeEntry.Row("Filmes que você já assistiu", seen.map { card(it) }))
        if (e.isEmpty()) e.add(HomeEntry.Note("Sua lista está vazia.\nAbra um filme ou série e aperte \"Minha lista\". Nos canais, aperte OK enquanto assiste e escolha \"Favoritar\"."))
        lastSig = libSig()
        return RecyclerView(this).apply {
            homeRv = this
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = RowsAdapter(e)
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(6), 0, dp(40))
        }
    }

    /* ---------- Busca ---------- */
    private fun searchView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        val box = EditText(this).apply {
            hint = "Buscar canal, filme ou série…"
            setHintTextColor(C.tx3); setTextColor(Color.WHITE); textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val n = rounded(0xFF171A22.toInt(), dpf(8f), dp(2), C.line)
            val f = rounded(0xFF171A22.toInt(), dpf(8f), dp(2), C.ac)
            background = n
            setOnFocusChangeListener { v, has -> v.background = if (has) f else n }
        }
        col.addView(box, llp(dp(520), WRAP).apply { marginStart = dp(40) })
        val res = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = RowsAdapter(listOf(HomeEntry.Note("Digite pelo menos 2 letras.")))
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(8), 0, dp(40))
        }
        col.addView(res, llp(MATCH, 0, 1f))
        var job: Job? = null
        fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                job?.cancel()
                job = lifecycleScope.launch {
                    delay(300)
                    val q = norm(s?.toString()?.trim() ?: "")
                    val a = res.adapter as RowsAdapter
                    if (q.length < 2) { a.entries = listOf(HomeEntry.Note("Digite pelo menos 2 letras.")); a.notifyDataSetChanged(); return@launch }
                    val r = withContext(Dispatchers.Default) {
                        listOf(Triple("Canais", Repo.live, Kind.LIVE), Triple("Filmes", Repo.vod, Kind.MOVIE), Triple("Séries", Repo.series, Kind.SERIES)).mapNotNull { (n, l, _) ->
                            val m = l.asSequence().filter { norm(it.name).contains(q) }.take(40).toList()
                            if (m.isEmpty()) null else n to m
                        }
                    }
                    a.entries = if (r.isEmpty()) listOf(HomeEntry.Note("Nada encontrado.")) else r.map { (n, m) -> HomeEntry.Row(n, m.map { card(it, if (it.kind == Kind.LIVE) m else null) }) }
                    a.notifyDataSetChanged()
                }
            }
        })
        box.post { box.requestFocus() }
        return col
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // tecla "menu" do controle leva direto à busca
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_SEARCH) { show("search"); return true }
        return super.dispatchKeyEvent(event)
    }
}
