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
    private var epgJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // mostra o motivo se o app fechou sozinho da última vez
        Store.str("crash")?.let { c -> showCrash(c); return }
        if (!Repo.hasCredentials()) { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }
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
                withContext(Dispatchers.IO) { try { Trend.ensure(false) } catch (e: Exception) {} }
                showAccount(); show("home"); setLoading(null)
                // atualiza por trás se o catálogo guardado tiver mais de 2 horas
                if (Repo.cacheAge() > 2 * 3600 * 1000L) refreshInBackground()
                else if (Trend.isStale()) refreshTrend()
            } else {
                setLoading("Conectando ao servidor…")
                val err = withContext(Dispatchers.IO) { Repo.login() }
                if (err != null) { goLogin(err); return@launch }
                try {
                    withContext(Dispatchers.IO) { Repo.fetchAll { m -> runOnUiThread { setLoading(m) } } }
                } catch (e: Throwable) { goLogin(if (e is OutOfMemoryError) "A lista é grande demais para a memória deste aparelho." else (e.message ?: "Não consegui baixar a lista.")); return@launch }
                setLoading("Buscando o que está em alta…")
                withContext(Dispatchers.IO) { try { Trend.ensure(false) } catch (e: Exception) {} }
                showAccount(); show("home"); setLoading(null)
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
            withContext(Dispatchers.IO) { try { Trend.ensure(Trend.isStale()) } catch (e: Exception) {} }
            // só redesenha se a pessoa estiver no topo do Início (para não pular o foco)
            if (tab == "home") {
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
        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(C.bg); isClickable = true
        }
        loading.addView(ProgressBar(this), llp(dp(48), dp(48)))
        loadingTxt = text("Carregando…", 15f, C.tx2).apply { gravity = Gravity.CENTER }
        loading.addView(loadingTxt, llp(WRAP, WRAP).apply { topMargin = dp(16) })
        root.addView(loading, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    private fun setLoading(msg: String?) {
        if (msg == null) loading.visibility = View.GONE
        else { loading.visibility = View.VISIBLE; loadingTxt.text = msg }
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

    private fun refreshCurrent() {
        if (tab == "home" || tab == "mylist") {
            val f = currentFocus
            render(keepFocus = f != null && f.parent === tabs)
        } else {
            // só atualiza os selos das capas
            content.findViewWithTag<RecyclerView>("grid")?.adapter?.notifyDataSetChanged()
        }
    }

    private fun render(keepFocus: Boolean) {
        shownVersion = Repo.version
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
            .putExtra("sid", p.sid).putExtra("start", p.pos)
        startActivity(i)
    }

    private fun card(it: Item, list: List<Item>? = null, rank: Int = 0, onFocus: (() -> Unit)? = null) =
        Card(it, it.name, it.kind, rank = rank, onFocus = onFocus) { open(it, list) }

    /* ---------- Início ---------- */
    private fun homeView(): View {
        val e = ArrayList<HomeEntry>()
        val movies = Repo.recent(Repo.vod)
        val heroes = heroCandidates(movies)
        if (heroes.isNotEmpty()) e.add(HomeEntry.Hero(heroView(heroes)))
        val cont = Lib.progs().filter { it.dur > 0 && !Lib.isSeen(it.key) }.take(20)
        if (cont.isNotEmpty()) e.add(HomeEntry.Row("Continuar assistindo", cont.map { p ->
            Card(null, p.name, Kind.MOVIE, p.icon, if (p.sub.isNotEmpty()) p.sub else "Faltam " + fmtDur(p.dur - p.pos), p.pct) { resume(p) }
        }))
        val favs = Lib.favs().mapNotNull { Repo.findKey(it) }
        val myl = favs.filter { it.kind != Kind.LIVE }.take(30)
        if (myl.isNotEmpty()) e.add(HomeEntry.Row("Minha lista", myl.map { card(it) }))
        val fch = favs.filter { it.kind == Kind.LIVE }
        if (fch.isNotEmpty()) e.add(HomeEntry.Row("Canais favoritos", fch.map { card(it, fch) }))
        val upd = if (Trend.updated > 0) "no seu catálogo · atualizado às " + SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(Trend.updated)) else "no seu catálogo"
        listOf(Kind.MOVIE to "filmes", Kind.SERIES to "séries").forEach { (k, label) ->
            Trend.forKind(k).forEach { r ->
                e.add(HomeEntry.Row("Top 10 $label na", r.items.take(10).mapIndexed { i, it -> card(it, rank = i + 1) }, r.plat.n, r.plat.color, upd, ranked = true))
            }
        }
        if (Trend.rows.isEmpty() && Trend.error != null) e.add(HomeEntry.Note("Top 10 das plataformas: não consegui falar com o TMDB (${Trend.error}). Tento de novo mais tarde."))
        if (Repo.live.isNotEmpty()) e.add(HomeEntry.Row("Canais ao vivo", Repo.live.take(24).map { card(it) }, live = true))
        if (movies.isNotEmpty()) e.add(HomeEntry.Row("Filmes adicionados recentemente", movies.take(30).map { card(it) }))
        val sers = Repo.recent(Repo.series)
        if (sers.isNotEmpty()) e.add(HomeEntry.Row("Séries adicionadas recentemente", sers.take(30).map { card(it) }))
        Repo.vodCats.take(6).forEach { c -> val l = Repo.vod.filter { it.cat == c.id }.take(30); if (l.isNotEmpty()) e.add(HomeEntry.Row(c.name, l.map { card(it) })) }
        Repo.serCats.take(3).forEach { c -> val l = Repo.series.filter { it.cat == c.id }.take(30); if (l.isNotEmpty()) e.add(HomeEntry.Row("Séries · " + c.name, l.map { card(it) })) }
        e.add(HomeEntry.Note("Sua lista: ${Repo.live.size} canais · ${Repo.vod.size} filmes · ${Repo.series.size} séries"))
        return RecyclerView(this).apply {
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

    private var heroTimer: Runnable? = null

    private fun heroView(list: List<Pair<Item, String>>): View {
        val f = FrameLayout(this).apply { layoutParams = ViewGroup.LayoutParams(MATCH, dp(300)); tag = "hero" }
        val bd = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.45f }
        f.addView(bd, FrameLayout.LayoutParams(MATCH, MATCH).apply { marginStart = dp(260) })
        f.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C.bg, C.bg, 0xCC0A0B0F.toInt(), 0x000A0B0F))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        f.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(C.bg, 0x000A0B0F)) }, FrameLayout.LayoutParams(MATCH, dp(90), Gravity.BOTTOM))
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(C.bg3, dpf(10f)); clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = dpf(12f)
        }
        f.addView(poster, FrameLayout.LayoutParams(dp(150), dp(225), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(70) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(dp(40), 0, 0, dp(18)) }
        val tag = text("", 11f, C.ac, true).apply { letterSpacing = 0.1f }
        col.addView(tag)
        val title = text("", 36f, Color.WHITE, true, 2).apply { setLineSpacing(0f, 0.95f) }
        col.addView(title, llp(dp(520), WRAP).apply { topMargin = dp(8) })
        val meta = text("", 13f, 0xFFD4D6DC.toInt())
        col.addView(meta, llp(WRAP, WRAP).apply { topMargin = dp(10) })
        val plot = text("", 13f, 0xFFC3C6CE.toInt(), false, 3).apply { setLineSpacing(0f, 1.25f) }
        col.addView(plot, llp(dp(480), WRAP).apply { topMargin = dp(10) })
        val dots = text("", 12f, C.tx3)
        var idx = 0
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        btns.addView(button("▶  Assistir", true) {
            val it = list[idx].first
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("mode", "movie").putExtra("id", it.id).putExtra("ext", it.ext)
                .putExtra("title", it.name).putExtra("key", it.key).putExtra("icon", it.icon).putExtra("name", it.name))
        })
        btns.addView(button("ⓘ  Mais informações") { open(list[idx].first) }, llp(WRAP, WRAP).apply { marginStart = dp(12) })
        btns.addView(dots, llp(WRAP, WRAP).apply { marginStart = dp(18) })
        col.addView(btns, llp(WRAP, WRAP).apply { topMargin = dp(16) })
        f.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        fun showHero(i: Int) {
            idx = i
            val (it, label) = list[i]
            tag.text = "FILME · $label"
            title.text = it.name
            val m = ArrayList<String>()
            scoreOf(it).takeIf { s -> s > 0 }?.let { s -> m.add("★ " + String.format(Locale("pt", "BR"), "%.1f", s)) }
            if (it.year > 0) m.add(it.year.toString())
            Repo.catName(Kind.MOVIE, it.cat).takeIf { c -> c.isNotEmpty() }?.let { c -> m.add(c) }
            meta.text = m.joinToString("   ")
            plot.text = it.plot
            if (it.icon.isNotEmpty()) { bd.load(it.icon) { crossfade(400) }; poster.load(it.icon) { crossfade(400) }; poster.visibility = View.VISIBLE }
            else { bd.setImageDrawable(null); poster.visibility = View.GONE }
            dots.text = if (list.size > 1) list.indices.joinToString(" ") { d -> if (d == i) "●" else "○" } else ""
            if (it.plot.isEmpty()) lifecycleScope.launch {
                val p = withContext(Dispatchers.IO) { try { Repo.vodInfo(it.id).s("plot") } catch (e: Exception) { "" } }
                if (p.isNotEmpty()) { it.plot = p; if (idx == i) plot.text = p }
            }
        }
        showHero(0)
        // troca o destaque sozinho a cada 12 segundos
        heroTimer?.let { f.removeCallbacks(it) }
        if (list.size > 1) {
            val r = object : Runnable {
                override fun run() {
                    if (f.isAttachedToWindow && f.isShown) showHero((idx + 1) % list.size)
                    f.postDelayed(this, 12000)
                }
            }
            heroTimer = r
            f.postDelayed(r, 12000)
        }
        return f
    }

    /* ---------- Ao vivo / Filmes / Séries ---------- */
    private fun browseView(kind: Kind): View {
        val list = Repo.list(kind)
        val counts = HashMap<String, Int>()
        list.forEach { counts[it.cat] = (counts[it.cat] ?: 0) + 1 }
        data class Entry(val id: String, val name: String, val items: () -> List<Item>, val count: Int)
        val entries = ArrayList<Entry>()
        entries.add(Entry("*", if (kind == Kind.LIVE) "Todos os canais" else "Todos", { list }, list.size))
        val favList = Lib.favs().mapNotNull { Repo.findKey(it) }.filter { it.kind == kind }
        if (favList.isNotEmpty()) entries.add(Entry("fav", if (kind == Kind.LIVE) "★ Canais favoritos" else "★ Minha lista", { favList }, favList.size))
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
        fun pick(id: String, focusGrid: Boolean) {
            current = id
            getPreferences(Context.MODE_PRIVATE).edit().putString("cat_$kind", id).apply()
            val en = entries.firstOrNull { it.id == id } ?: entries[0]
            title.text = en.name
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
        if (cont.isNotEmpty()) e.add(HomeEntry.Row("Continuar assistindo", cont.map { p ->
            Card(null, p.name, Kind.MOVIE, p.icon, if (p.sub.isNotEmpty()) p.sub else "Faltam " + fmtDur(p.dur - p.pos), p.pct) { resume(p) }
        }))
        val favs = Lib.favs().mapNotNull { Repo.findKey(it) }
        favs.filter { it.kind == Kind.MOVIE }.takeIf { it.isNotEmpty() }?.let { l -> e.add(HomeEntry.Row("Filmes na minha lista", l.map { card(it) })) }
        favs.filter { it.kind == Kind.SERIES }.takeIf { it.isNotEmpty() }?.let { l -> e.add(HomeEntry.Row("Séries na minha lista", l.map { card(it) })) }
        favs.filter { it.kind == Kind.LIVE }.takeIf { it.isNotEmpty() }?.let { l -> e.add(HomeEntry.Row("Canais favoritos", l.map { card(it, l) })) }
        val seen = Lib.seenKeys('m').mapNotNull { Repo.findKey(it) }.take(40)
        if (seen.isNotEmpty()) e.add(HomeEntry.Row("Filmes que você já assistiu", seen.map { card(it) }))
        if (e.isEmpty()) e.add(HomeEntry.Note("Sua lista está vazia.\nAbra um filme ou série e aperte \"Minha lista\". Nos canais, aperte OK enquanto assiste e escolha \"Favoritar\"."))
        return RecyclerView(this).apply {
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
