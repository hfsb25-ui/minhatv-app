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
    private lateinit var rail: LinearLayout
    private val railItems = ArrayList<LinearLayout>()
    private var railOpen = false
    private lateinit var railLogo: ImageView
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
            playIntro()
            window.decorView.postDelayed({ startActivity(Intent(this, LoginActivity::class.java)); finish() }, 1800)
            return
        }
        // se a tela foi recriada (o sistema fechou para liberar memória), volta sem abertura e na mesma aba
        fastStart = savedInstanceState != null || Repo.vod.isNotEmpty() || Repo.live.isNotEmpty()
        savedInstanceState?.getString("tab")?.let { tab = it }
        buildShell()
        if (!fastStart) playIntro()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // como na Netflix: voltar leva primeiro ao menu lateral
                val f = currentFocus
                if (f != null && !isInRail(f)) { (railItems.firstOrNull { it.tag == tab } ?: railItems.firstOrNull())?.requestFocus(); return }
                if (tab != "home") { show("home"); railItems.firstOrNull()?.requestFocus(); return }
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
            val before = Discover.colls.size.toString() + "|" + Discover.because?.first?.key + "|" + Trend.updated
            withContext(Dispatchers.IO) {
                try { Trend.ensure(Trend.isStale()) } catch (e: Exception) {}
                try { Discover.refresh() } catch (e: Exception) {}
            }
            val after = Discover.colls.size.toString() + "|" + Discover.because?.first?.key + "|" + Trend.updated
            // só redesenha se algo mudou e a pessoa estiver no topo do Início (para não pular o foco)
            if (tab == "home" && before != after) {
                val f = currentFocus
                if (f == null || isInRail(f) || isInHero(f)) render(keepFocus = true)
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

    /* ---------- estrutura: menu lateral recolhível + conteúdo ---------- */
    private val RAIL_W = 64
    private val RAIL_OPEN_W = 220

    private fun buildShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(C.bg) }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH).apply { marginStart = dp(RAIL_W) })
        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(18), dp(10), dp(16))
            setBackgroundColor(C.bg)
            elevation = dpf(16f)
        }
        railLogo = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
        rail.addView(railLogo, llp(WRAP, dp(38)).apply { marginStart = dp(3); bottomMargin = dp(26) })
        listOf(
            Triple("home", "Início", R.drawable.ic_nav_home), Triple("live", "Ao vivo", R.drawable.ic_nav_live),
            Triple("movie", "Filmes", R.drawable.ic_nav_movie), Triple("series", "Séries", R.drawable.ic_nav_series),
            Triple("mylist", "Minha lista", R.drawable.ic_nav_list), Triple("search", "Buscar", R.drawable.ic_nav_search)
        ).forEach { (k, n, ic) -> val v = railItem(k, n, ic); railItems.add(v); rail.addView(v, llp(MATCH, WRAP).apply { bottomMargin = dp(4) }) }
        rail.addView(View(this), llp(1, 0, 1f))
        acct = text("", 11f, C.tx3).apply { visibility = View.GONE; setPadding(dp(10), 0, dp(6), dp(8)) }
        rail.addView(acct, llp(MATCH, WRAP))
        rail.addView(railItem("exit", "Sair da lista", R.drawable.ic_nav_exit), llp(MATCH, WRAP))
        root.addView(rail, FrameLayout.LayoutParams(dp(RAIL_W), MATCH))
        loading = splashView("Carregando…", !fastStart)
        root.addView(loading, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        // abre o menu quando o foco entra nele e recolhe quando sai
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, n -> setRailOpen(n != null && isInRail(n)) }
    }

    private fun railItem(key: String, label: String, icon: Int): LinearLayout = LinearLayout(this).apply {
        tag = key
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true; isClickable = true
        setPadding(dp(10), dp(9), dp(10), dp(9))
        addView(ImageView(this@MainActivity).apply { setImageResource(icon); setColorFilter(C.tx3) }, llp(dp(24), dp(24)))
        addView(text(label, 15f, C.tx2, false, 1).apply { visibility = View.GONE }, llp(WRAP, WRAP).apply { marginStart = dp(16) })
        setOnFocusChangeListener { _, _ -> paintRail() }
        setOnClickListener {
            if (key == "exit") confirmLogout()
            else { show(key); content.post { content.requestFocus(View.FOCUS_RIGHT) } }
        }
    }

    private fun isInRail(v: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === rail) return true; p = p.parent as? View }
        return false
    }

    private fun setRailOpen(open: Boolean) {
        if (open == railOpen || !::rail.isInitialized) return
        railOpen = open
        rail.layoutParams = rail.layoutParams.apply { width = dp(if (open) RAIL_OPEN_W else RAIL_W) }
        rail.background = if (open) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF0A0B0F.toInt(), 0xF50A0B0F.toInt(), 0xE60A0B0F.toInt())) else null
        if (!open) rail.setBackgroundColor(C.bg)
        railLogo.setImageResource(if (open) R.drawable.logo_brtv else R.mipmap.ic_launcher)
        acct.visibility = if (open) View.VISIBLE else View.GONE
        for (i in 0 until rail.childCount) {
            val item = rail.getChildAt(i) as? LinearLayout ?: continue
            (item.getChildAt(1) as? TextView)?.visibility = if (open) View.VISIBLE else View.GONE
        }
        rail.requestLayout()
        paintRail()
    }

    private fun paintRail() {
        for (i in 0 until rail.childCount) {
            val item = rail.getChildAt(i) as? LinearLayout ?: continue
            if (item.tag !is String) continue
            val on = item.tag == tab
            val f = item.isFocused
            (item.getChildAt(0) as? ImageView)?.setColorFilter(if (on) C.ac else if (f) Color.WHITE else C.tx3)
            (item.getChildAt(1) as? TextView)?.let { t ->
                t.setTextColor(if (on || f) Color.WHITE else C.tx2)
                t.paint.isFakeBoldText = on
                t.invalidate()
            }
            item.background = if (f) rounded(0x33FFFFFF, dpf(8f)) else null
        }
    }

    private fun setLoading(msg: String?) {
        if (msg == null) {
            val wait = if (fastStart) 0L else 1800 - (System.currentTimeMillis() - openedAt)
            if (wait > 0) loading.postDelayed({ loading.visibility = View.GONE }, wait) else loading.visibility = View.GONE
        } else { loading.visibility = View.VISIBLE; loadingTxt.text = msg }
    }

    /** som curto da abertura (original, gerado para o BR TV) */
    private fun playIntro() {
        try {
            android.media.MediaPlayer.create(this, R.raw.abertura)?.apply {
                setVolume(0.8f, 0.8f)
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (e: Exception) {}
    }

    /** tela de abertura: logo animada + "Desenvolvido por Bruno Carvalho" */
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

    private fun showAccount() {
        val exp = if (Repo.expDate > 0) "\naté " + SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date(Repo.expDate * 1000)) else ""
        acct.text = Repo.userName.ifEmpty { Xt.user } + exp
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
        paintRail()
        render(false)
    }

    /** resumo do que muda ao assistir/favoritar (para só redesenhar quando precisa) */
    private fun libSig(): String =
        Lib.progs().take(6).joinToString(",") { it.key + ":" + it.pct } + "|" + Lib.favs().take(30).joinToString(",") + "|" + Lib.seenKeys('m').size

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

    /** cartão largo do "continuar assistindo": imagem do episódio + nome do episódio */
    private fun contCard(p: Prog, onFocus: Boolean = false): Card {
        val item = if (p.kind == "series") Repo.find(Kind.SERIES, p.sid) else Repo.findKey(p.key)
        val sub = if (p.sub.isNotEmpty()) p.sub + (if (p.ept.isNotEmpty()) " · " + p.ept else "") else "Faltam " + fmtDur(p.dur - p.pos)
        return Card(item, p.name, Kind.MOVIE, p.icon, sub, p.pct, onFocus = if (onFocus && item != null) ({ preview(item) }) else null,
            art = p.img.ifEmpty { Art.backdrop(item) }.ifEmpty { item?.let { Discover.artCached(it)?.backdrop } ?: "" }) { resume(p) }
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

    /* ---------- Início (estilo Netflix: painel no topo que muda com a capa selecionada) ---------- */
    private var heroList: List<Pair<Item, String>> = emptyList()
    private var heroIdx = 0
    private var heroMode = true
    private var previewKey: String? = null
    private var previewJob: Job? = null
    private var panel: Panel? = null

    private class Panel(
        val root: FrameLayout, val bd: ImageView, val tag: TextView, val logo: ImageView, val title: TextView,
        val meta: TextView, val plot: TextView, val btns: LinearLayout, val dots: TextView
    )

    private fun hcard(it: Item, list: List<Item>? = null, rank: Int = 0) =
        Card(it, it.name, it.kind, rank = rank, onFocus = { preview(it) }) { open(it, list) }

    private fun homeView(): View {
        val e = ArrayList<HomeEntry>()
        val movies = Repo.recent(Repo.vod)
        heroList = heroCandidates(movies)
        val cont = Lib.progs().filter { it.dur > 0 && !Lib.isSeen(it.key) }.take(20)
        if (cont.isNotEmpty()) e.add(HomeEntry.Row("Continuar assistindo", cont.map { contCard(it, true) }, wide = true))
        val favs = Lib.favs().mapNotNull { Repo.findKey(it) }
        val myl = favs.filter { it.kind != Kind.LIVE }.take(30)
        if (myl.isNotEmpty()) e.add(HomeEntry.Row("Minha lista", myl.map { hcard(it) }))
        val fch = favs.filter { it.kind == Kind.LIVE }
        if (fch.isNotEmpty()) e.add(HomeEntry.Row("Canais favoritos", fch.map { hcard(it, fch) }))
        val upd = if (Trend.updated > 0) "no seu catálogo · atualizado às " + SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(Trend.updated)) else "no seu catálogo"
        listOf(Kind.MOVIE to "filmes", Kind.SERIES to "séries").forEach { (k, label) ->
            Trend.forKind(k).forEach { r ->
                e.add(HomeEntry.Row("Top 10 $label na", r.items.take(10).mapIndexed { i, it -> hcard(it, rank = i + 1) }, r.plat.n, r.plat.color, upd, ranked = true))
            }
        }
        Discover.because?.let { (src, l) -> e.add(HomeEntry.Row("Porque você assistiu " + src.name, l.map { hcard(it) }, wide = true)) }
        Discover.colls.forEach { c -> e.add(HomeEntry.Row(c.title, c.items.map { hcard(it) }, wide = true)) }
        if (Trend.rows.isEmpty() && Trend.error != null) e.add(HomeEntry.Note("Top 10 das plataformas: não consegui falar com o TMDB (${Trend.error}). Tento de novo mais tarde."))
        if (Repo.live.isNotEmpty()) e.add(HomeEntry.Row("Canais ao vivo", Repo.live.take(24).map { hcard(it) }, live = true))
        if (movies.isNotEmpty()) e.add(HomeEntry.Row("Filmes adicionados recentemente", movies.take(30).map { hcard(it) }))
        val sers = Repo.recent(Repo.series)
        if (sers.isNotEmpty()) e.add(HomeEntry.Row("Séries adicionadas recentemente", sers.take(30).map { hcard(it) }))
        Repo.vodCats.take(6).forEach { c -> val l = Repo.vod.filter { it.cat == c.id }.take(30); if (l.isNotEmpty()) e.add(HomeEntry.Row(c.name, l.map { hcard(it) })) }
        Repo.serCats.take(3).forEach { c -> val l = Repo.series.filter { it.cat == c.id }.take(30); if (l.isNotEmpty()) e.add(HomeEntry.Row("Séries · " + c.name, l.map { hcard(it) })) }
        e.add(HomeEntry.Note("Sua lista: ${Repo.live.size} canais · ${Repo.vod.size} filmes · ${Repo.series.size} séries"))
        lastSig = libSig()

        val frame = FrameLayout(this)
        // imagem de fundo do topo (muda com a capa selecionada)
        val bd = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.6f }
        frame.addView(bd, FrameLayout.LayoutParams(MATCH, dp(340), Gravity.TOP).apply { marginStart = dp(170) })
        frame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C.bg, C.bg, 0xD90A0B0F.toInt(), 0x330A0B0F, 0x000A0B0F))
        }, FrameLayout.LayoutParams(MATCH, dp(340), Gravity.TOP))
        frame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x000A0B0F, 0xCC0A0B0F.toInt(), C.bg))
        }, FrameLayout.LayoutParams(MATCH, dp(150), Gravity.TOP).apply { topMargin = dp(190) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = FrameLayout(this).apply { tag = "hero" }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(dp(40), dp(14), dp(40), dp(6)) }
        val tagTv = text("", 11f, C.ac, true).apply { letterSpacing = 0.1f }
        info.addView(tagTv)
        val logo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        info.addView(logo, llp(dp(380), dp(78)).apply { topMargin = dp(8) })
        val title = text("", 30f, Color.WHITE, true, 2).apply { setLineSpacing(0f, 0.95f) }
        info.addView(title, llp(dp(560), WRAP).apply { topMargin = dp(6) })
        val meta = text("", 13f, 0xFFD4D6DC.toInt(), false, 1)
        info.addView(meta, llp(WRAP, WRAP).apply { topMargin = dp(8) })
        val plot = text("", 13f, 0xFFC3C6CE.toInt(), false, 3).apply { setLineSpacing(0f, 1.25f) }
        info.addView(plot, llp(dp(500), dp(52)).apply { topMargin = dp(8) })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(2), dp(4), dp(2), dp(4)) }
        fun withBillboard(b: TextView): TextView {
            val old = b.onFocusChangeListener
            b.setOnFocusChangeListener { v, f -> old?.onFocusChange(v, f); if (f) billboard() }
            return b
        }
        btns.addView(withBillboard(button("▶  Assistir", true) {
            val it = heroList.getOrNull(heroIdx)?.first ?: return@button
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("mode", "movie").putExtra("id", it.id).putExtra("ext", it.ext)
                .putExtra("title", it.name).putExtra("key", it.key).putExtra("icon", it.icon).putExtra("name", it.name)
                .putExtra("plot", it.plot).putExtra("img", Art.backdrop(it)))
        }))
        btns.addView(withBillboard(button("ⓘ  Mais informações") { heroList.getOrNull(heroIdx)?.first?.let { open(it) } }), llp(WRAP, WRAP).apply { marginStart = dp(12) })
        btns.addView(withBillboard(button("Sortear filme") {
            val pick = Discover.randomPick()
            if (pick == null) Toast.makeText(this, "Nenhum filme para sortear", Toast.LENGTH_SHORT).show()
            else { Toast.makeText(this, "Que tal: " + pick.name + "?", Toast.LENGTH_SHORT).show(); open(pick) }
        }), llp(WRAP, WRAP).apply { marginStart = dp(12) })
        val dots = text("", 12f, C.tx3)
        btns.addView(dots, llp(WRAP, WRAP).apply { marginStart = dp(18) })
        info.addView(btns, llp(WRAP, WRAP).apply { topMargin = dp(10) })
        top.addView(info, FrameLayout.LayoutParams(MATCH, MATCH))
        col.addView(top, llp(MATCH, dp(262)))

        val rows = RecyclerView(this).apply {
            homeRv = this
            layoutManager = SnapColLM(this@MainActivity)
            adapter = RowsAdapter(e)
            clipChildren = false; clipToPadding = false
            setPadding(0, 0, 0, dp(220))
            setItemViewCacheSize(6)
        }
        col.addView(rows, llp(MATCH, 0, 1f))
        frame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        panel = Panel(top, bd, tagTv, logo, title, meta, plot, btns, dots)
        previewKey = null
        heroMode = true
        if (heroList.isNotEmpty()) showHero(0) else { btns.alpha = 1f; title.text = ""; }
        startHeroTimer(frame)
        frame.post { val f = currentFocus; if (tab == "home" && (f == null || !isInRail(f))) btns.getChildAt(0)?.requestFocus() }
        return frame
    }

    private var heroTimer: Runnable? = null
    private fun startHeroTimer(host: View) {
        heroTimer?.let { host.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (host.isAttachedToWindow && heroMode && heroList.size > 1) showHero((heroIdx + 1) % heroList.size)
                host.postDelayed(this, 12000)
            }
        }
        heroTimer = r
        host.postDelayed(r, 12000)
    }

    /** volta para o destaque (quando o foco está nos botões do topo) */
    private fun billboard() {
        val p = panel ?: return
        p.btns.animate().alpha(1f).setDuration(200).start()
        if (heroMode) return
        heroMode = true
        previewKey = null
        if (heroList.isNotEmpty()) showHero(heroIdx)
    }

    private fun showHero(i: Int) {
        val (it, label) = heroList.getOrNull(i) ?: return
        heroIdx = i
        fillPanel(it, "FILME · $label")
        panel?.dots?.text = if (heroList.size > 1) heroList.indices.joinToString(" ") { d -> if (d == i) "●" else "○" } else ""
    }

    /** mostra no topo o título com a capa selecionada */
    private fun preview(it: Item) {
        val p = panel ?: return
        if (heroMode) { heroMode = false; p.btns.animate().alpha(0f).setDuration(200).start() }
        if (previewKey == it.key) return
        val kind = when (it.kind) { Kind.MOVIE -> "FILME"; Kind.SERIES -> "SÉRIE"; Kind.LIVE -> "AO VIVO" }
        val cat = Repo.catName(it.kind, it.cat)
        fillPanel(it, kind + if (cat.isNotEmpty()) " · " + cat.uppercase() else "")
        p.dots.text = ""
    }

    private fun fillPanel(it: Item, tagText: String) {
        val p = panel ?: return
        previewKey = it.key
        p.tag.text = tagText
        val m = ArrayList<String>()
        scoreOf(it).takeIf { s -> s > 0 }?.let { s -> m.add("★ " + String.format(Locale("pt", "BR"), "%.1f", s)) }
        if (it.year > 0) m.add(it.year.toString())
        if (it.genre.isNotEmpty()) m.add(it.genre.split(",").take(2).joinToString(", ") { g -> g.trim() })
        val now = System.currentTimeMillis() / 1000
        if (it.added > 0 && now - it.added in 0..(7L * 24 * 3600)) m.add(if (it.kind == Kind.SERIES) "Novos episódios" else "Novidade")
        if (it.kind == Kind.LIVE && it.num.isNotEmpty()) m.add("Canal " + it.num)
        p.meta.text = m.joinToString("   ")
        p.plot.text = it.plot
        // logo e imagem larga: usa o que já tem guardado e busca o resto em seguida
        val cached = Discover.artCached(it)
        showLogo(cached?.logo ?: "", it.name)
        val bdUrl = Art.backdrop(it).ifEmpty { cached?.backdrop ?: "" }.ifEmpty { if (it.kind == Kind.LIVE) "" else it.icon }
        if (bdUrl.isNotEmpty()) p.bd.load(bdUrl) { crossfade(350) } else p.bd.setImageDrawable(null)
        previewJob?.cancel()
        if (it.kind == Kind.LIVE) return
        previewJob = lifecycleScope.launch {
            delay(400)
            val a = if (cached == null) withContext(Dispatchers.IO) { try { Discover.art(it) } catch (e: Exception) { null } } else cached
            if (previewKey != it.key) return@launch
            if (a != null) {
                if (a.logo.isNotEmpty()) showLogo(a.logo, it.name)
                if (cached == null && a.backdrop.isNotEmpty() && Art.backdrop(it).isEmpty()) p.bd.load(a.backdrop) { crossfade(350) }
            }
            if (it.plot.isEmpty() && it.kind == Kind.MOVIE) {
                val pl = withContext(Dispatchers.IO) { try { Repo.vodInfo(it.id).s("plot") } catch (e: Exception) { "" } }
                if (pl.isNotEmpty()) { it.plot = pl; if (previewKey == it.key) p.plot.text = pl }
            }
        }
    }

    /** logo oficial do título no lugar do nome escrito (quando existe) */
    private fun showLogo(url: String, name: String) {
        val p = panel ?: return
        p.title.text = name
        if (url.isEmpty()) { p.logo.visibility = View.GONE; p.title.visibility = View.VISIBLE; return }
        p.logo.load(url) {
            crossfade(250)
            listener(onSuccess = { _, _ -> p.logo.visibility = View.VISIBLE; p.title.visibility = View.GONE },
                onError = { _, _ -> p.logo.visibility = View.GONE; p.title.visibility = View.VISIBLE })
        }
    }

    /** nota que vale para o destaque: a da lista ou, se não tiver, a do TMDB */
    private fun scoreOf(it: Item): Double = if (it.rating > 0) it.rating else Trend.votes[it.key] ?: 0.0

    /**
     * escolhe os destaques:
     * 1) filmes em alta nas plataformas que estão na lista, com nota 6,5 ou mais
     * 2) se faltar, lançamentos recentes com nota 7 ou mais
     * 3) em último caso, o mais recente com capa
     */
    private fun heroCandidates(recentMovies: List<Item>): List<Pair<Item, String>> {
        val out = ArrayList<Pair<Item, String>>()
        val seen = HashSet<String>()
        val rows = Trend.forKind(Kind.MOVIE)
        for (pos in 0 until 10) {
            rows.forEach { r ->
                val it = r.items.getOrNull(pos) ?: return@forEach
                if (it.icon.isNotEmpty() && scoreOf(it) >= 6.5 && seen.add(it.id)) out.add(it to "EM ALTA NA " + r.plat.n.uppercase())
            }
        }
        if (out.size < 3) {
            recentMovies.take(400).filter { it.icon.isNotEmpty() && it.rating >= 7.0 && it.id !in seen }
                .sortedByDescending { it.rating }.take(8 - out.size)
                .forEach { seen.add(it.id); out.add(it to "LANÇAMENTO BEM AVALIADO") }
        }
        if (out.isEmpty()) (recentMovies.firstOrNull { it.icon.isNotEmpty() } ?: recentMovies.firstOrNull())?.let { out.add(it to "ADICIONADO RECENTEMENTE") }
        val top = out.take(8)
        if (top.size <= 1) return top
        // começa num destaque diferente a cada vez que abre
        val start = (System.currentTimeMillis() / 1000 % top.size).toInt()
        return top.drop(start) + top.take(start)
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
        return row
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
        if (cont.isNotEmpty()) e.add(HomeEntry.Row("Continuar assistindo", cont.map { contCard(it) }, wide = true))
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
            layoutManager = SnapColLM(this@MainActivity)
            adapter = RowsAdapter(e)
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(16), 0, dp(220))
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
