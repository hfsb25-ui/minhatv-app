package br.com.minhatv

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DetailActivity : ComponentActivity() {
    private lateinit var item: Item
    private lateinit var backdrop: ImageView
    private lateinit var body: LinearLayout
    private lateinit var btns: LinearLayout
    private lateinit var plotTv: TextView
    private lateinit var metaTv: TextView
    private lateinit var credTv: TextView
    private lateinit var resumeBox: LinearLayout
    private var episodes: List<Episode> = emptyList()
    private var seasons: List<String> = emptyList()
    private var curSeason = ""
    private var seasonBar: LinearLayout? = null
    private var epList: LinearLayout? = null
    private var lastFocusKey: String? = null
    private var trailerKey: String? = null
    private var cert = ""
    private var exGenre = ""
    private var exRuntime = ""
    private lateinit var castBox: LinearLayout
    private lateinit var similarBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = try { Kind.valueOf(intent.getStringExtra("kind") ?: "MOVIE") } catch (e: Exception) { Kind.MOVIE }
        val it = Repo.find(kind, intent.getStringExtra("id") ?: "")
        if (it == null) { finish(); return }
        item = it
        build()
        if (item.kind == Kind.MOVIE) loadMovie() else loadSeries()
        loadExtras()
    }

    override fun onResume() {
        super.onResume()
        if (::btns.isInitialized) {
            if (item.kind == Kind.MOVIE) movieButtons() else if (episodes.isNotEmpty()) { seriesButtons(); showSeason(curSeason, false) }
        }
    }

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(C.bg) }
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.5f }
        root.addView(backdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { marginStart = dp(280) })
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C.bg, C.bg, 0xD90A0B0F.toInt(), 0x1A0A0B0F))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (item.icon.isNotEmpty()) backdrop.load(item.icon)

        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(28), dp(48), dp(40)) }
        val tag = (if (item.kind == Kind.MOVIE) "FILME" else "SÉRIE") + Repo.catName(item.kind, item.cat).let { if (it.isNotEmpty()) " · " + it.uppercase() else "" }
        body.addView(text(tag, 11f, C.ac, true).apply { letterSpacing = 0.1f })
        val titleTv = text(item.name, 34f, Color.WHITE, true, 3)
        body.addView(titleTv, llp(dp(560), WRAP).apply { topMargin = dp(8) })
        // logo oficial do título (TMDB) no lugar do nome, quando existe
        val logoIv = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        body.addView(logoIv, llp(dp(420), dp(100)).apply { topMargin = dp(8) })
        fun showLogo(url: String) {
            if (url.isEmpty()) return
            logoIv.load(url) { crossfade(250); listener(onSuccess = { _, _ -> logoIv.visibility = View.VISIBLE; titleTv.visibility = View.GONE }) }
        }
        val cachedArt = Discover.artCached(item)
        if (cachedArt != null) { showLogo(cachedArt.logo); if (cachedArt.backdrop.isNotEmpty()) backdrop.load(cachedArt.backdrop) }
        else if (item.kind != Kind.LIVE) lifecycleScope.launch {
            val a = withContext(Dispatchers.IO) { try { Discover.art(item) } catch (e: Exception) { null } }
            if (a != null) { showLogo(a.logo); if (a.backdrop.isNotEmpty()) backdrop.load(a.backdrop) { crossfade(300) } }
        }
        metaTv = text("", 13f, 0xFFD4D6DC.toInt())
        body.addView(metaTv, llp(WRAP, WRAP).apply { topMargin = dp(10) })
        plotTv = text(item.plot.ifEmpty { "Carregando informações…" }, 14f, 0xFFC3C6CE.toInt(), false, 5).apply { setLineSpacing(0f, 1.3f) }
        body.addView(plotTv, llp(dp(560), WRAP).apply { topMargin = dp(12) })
        credTv = text("", 12f, C.tx3).apply { setLineSpacing(0f, 1.35f) }
        body.addView(credTv, llp(dp(560), WRAP).apply { topMargin = dp(10) })
        resumeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(resumeBox, llp(dp(300), WRAP).apply { topMargin = dp(12) })
        btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(6), dp(4), dp(6)) }
        body.addView(btns, llp(WRAP, WRAP).apply { topMargin = dp(10) })
        castBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(castBox, llp(MATCH, WRAP))
        similarBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(similarBox, llp(MATCH, WRAP))
        scroll.addView(body)
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        setMeta(item.genre, "")
    }

    private fun setMeta(genre: String, duration: String) {
        val m = ArrayList<String>()
        if (item.rating > 0) m.add("★ " + String.format(Locale("pt", "BR"), "%.1f", item.rating))
        if (item.year > 0) m.add(item.year.toString())
        if (cert.isNotEmpty()) m.add(if (cert.equals("L", true)) "Livre" else "$cert anos")
        genre.ifEmpty { exGenre }.takeIf { it.isNotEmpty() }?.let { m.add(it) }
        duration.ifEmpty { exRuntime }.takeIf { it.isNotEmpty() }?.let { m.add(it) }
        if (item.kind == Kind.MOVIE && Lib.isSeen(item.key)) m.add("✓ Assistido")
        metaTv.text = m.joinToString("   ")
    }

    /* ---------- filme ---------- */
    private fun loadMovie() {
        movieButtons()
        lifecycleScope.launch {
            val i = withContext(Dispatchers.IO) { try { Repo.vodInfo(item.id) } catch (e: Exception) { null } }
            if (i == null) { if (item.plot.isEmpty()) plotTv.text = "Sem sinopse disponível."; return@launch }
            val plot = i.s("plot").ifEmpty { i.s("description") }
            if (plot.isNotEmpty()) item.plot = plot
            plotTv.text = item.plot.ifEmpty { "Sem sinopse disponível." }
            setMeta(i.s("genre").ifEmpty { item.genre }, i.s("duration"))
            val cr = ArrayList<String>()
            i.s("cast").ifEmpty { i.s("actors") }.takeIf { it.isNotEmpty() }?.let { cr.add("Elenco: $it") }
            i.s("director").takeIf { it.isNotEmpty() }?.let { cr.add("Direção: $it") }
            credTv.text = cr.joinToString("\n")
            backdropOf(i)?.let { backdrop.load(it) }
            if (item.icon.isEmpty()) i.s("movie_image").takeIf { it.isNotEmpty() }?.let { item.icon = it }
        }
    }

    private fun backdropOf(i: org.json.JSONObject): String? {
        val b = i.opt("backdrop_path")
        val u = when (b) { is org.json.JSONArray -> if (b.length() > 0) b.optString(0) else ""; is String -> b; else -> "" }
        return u.takeIf { it.isNotEmpty() }
    }

    private fun movieButtons() {
        val p = Lib.prog(item.key)?.takeIf { it.pos > 30000 && !Lib.isSeen(item.key) }
        resumeBox.removeAllViews()
        if (p != null) {
            resumeBox.addView(progressBar(p.pct), llp(MATCH, dp(4)))
            resumeBox.addView(text("Você parou em ${fmtDur(p.pos)} — faltam ${fmtDur(p.dur - p.pos)}", 12f, C.tx3), llp(WRAP, WRAP).apply { topMargin = dp(6) })
        }
        btns.removeAllViews()
        val first: TextView
        if (p != null) {
            first = button("▶  Continuar", true) { playMovie(p.pos) }
            btns.addView(first)
            addBtn(button("↺  Do início") { playMovie(0) })
        } else {
            first = button("▶  Assistir", true) { playMovie(0) }
            btns.addView(first)
        }
        val fav = button("") {}
        fun paintFav() { fav.text = if (Lib.isFav(item.key)) "✓  Na minha lista" else "+  Minha lista" }
        paintFav()
        fav.setOnClickListener { val on = Lib.toggleFav(item.key); paintFav(); toast(if (on) "Adicionado à Minha lista" else "Removido da Minha lista") }
        addBtn(fav)
        val seen = button("") {}
        fun paintSeen() { val on = Lib.isSeen(item.key); seen.text = if (on) "✓  Assistido" else "Marcar como assistido"; seen.setTextColor(if (on) C.ok else Color.WHITE) }
        paintSeen()
        seen.setOnClickListener { val on = !Lib.isSeen(item.key); Lib.setSeen(item.key, on); paintSeen(); setMeta(item.genre, ""); movieButtons(); toast(if (on) "Marcado como assistido" else "Desmarcado") }
        addBtn(seen)
        trailerKey?.let { k -> addBtn(button("▶  Trailer") { openTrailer(k) }) }
        first.post { first.requestFocus() }
    }

    private fun addBtn(v: View) = btns.addView(v, llp(WRAP, WRAP).apply { marginStart = dp(12) })

    private fun progressBar(pct: Int): View {
        val f = FrameLayout(this).apply { background = rounded(0x33FFFFFF, dpf(2f)) }
        val bar = View(this).apply { background = rounded(C.ac, dpf(2f)) }
        f.addView(bar, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        f.post { bar.layoutParams = bar.layoutParams.apply { width = f.width * pct / 100 }; bar.requestLayout() }
        return f
    }

    private fun playMovie(start: Long) {
        startActivity(
            Intent(this, PlayerActivity::class.java).putExtra("mode", "movie").putExtra("id", item.id).putExtra("ext", item.ext)
                .putExtra("title", item.name).putExtra("key", item.key).putExtra("icon", item.icon).putExtra("name", item.name).putExtra("start", start)
                .putExtra("plot", item.plot).putExtra("img", Art.backdrop(item).ifEmpty { Discover.artCached(item)?.backdrop ?: "" })
        )
    }

    /* ---------- série ---------- */
    private fun loadSeries() {
        btns.removeAllViews()
        val fav = favButton()
        btns.addView(fav)
        fav.post { fav.requestFocus() }
        if (item.cast.isNotEmpty()) credTv.text = "Elenco: " + item.cast
        if (item.plot.isEmpty()) plotTv.text = ""
        val loadingTv = text("Carregando episódios…", 13f, C.tx3)
        addBody(loadingTv, llp(WRAP, WRAP).apply { topMargin = dp(16) })
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { try { Repo.seriesInfo(item.id) } catch (e: Exception) { null } }
            body.removeView(loadingTv)
            if (r == null || r.second.isEmpty()) { addBody(text("Não consegui carregar os episódios.", 13f, C.tx3), llp(WRAP, WRAP).apply { topMargin = dp(16) }); return@launch }
            val (info, eps) = r
            episodes = eps
            seasons = eps.map { it.season }.distinct()
            if (item.plot.isEmpty()) plotTv.text = info.s("plot")
            if (credTv.text.isEmpty() && info.s("cast").isNotEmpty()) credTv.text = "Elenco: " + info.s("cast")
            setMeta(info.s("genre").ifEmpty { item.genre }, "")
            backdropOf(info)?.let { backdrop.load(it) }
            val sv = HorizontalScrollView(this@DetailActivity).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
            seasonBar = LinearLayout(this@DetailActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(6), dp(4), dp(6)) }
            sv.addView(seasonBar)
            addBody(sv, llp(MATCH, WRAP).apply { topMargin = dp(16) })
            val el = LinearLayout(this@DetailActivity).apply { orientation = LinearLayout.VERTICAL }
            epList = el
            addBody(el, llp(dp(640), WRAP).apply { topMargin = dp(6) })
            seasons.forEach { sn ->
                val b = chip("Temporada $sn") { showSeason(sn, true) }
                b.tag = sn
                seasonBar?.addView(b, llp(WRAP, WRAP).apply { marginEnd = dp(8) })
            }
            val nx = nextEpisode()
            curSeason = nx.first.season
            seriesButtons()
            showSeason(curSeason, false)
            btns.getChildAt(0)?.requestFocus()
        }
    }

    private fun favButton(): TextView {
        val fav = button("") {}
        fun paint() { fav.text = if (Lib.isFav(item.key)) "✓  Na minha lista" else "+  Minha lista" }
        paint()
        fav.setOnClickListener { val on = Lib.toggleFav(item.key); paint(); toast(if (on) "Adicionado à Minha lista" else "Removido da Minha lista") }
        return fav
    }

    /** próximo episódio: o que estava vendo, ou o seguinte ao último assistido */
    private fun nextEpisode(): Triple<Episode, Boolean, Boolean> {
        var lastSeen = -1
        var resumeIdx = -1
        var resumeT = 0L
        episodes.forEachIndexed { i, e ->
            val k = "e" + e.id
            if (Lib.isSeen(k)) lastSeen = i
            val p = Lib.prog(k)
            if (p != null && p.pos > 30000 && !Lib.isSeen(k) && p.t >= resumeT) { resumeIdx = i; resumeT = p.t }
        }
        if (resumeIdx >= 0) return Triple(episodes[resumeIdx], true, false)
        if (lastSeen >= 0 && lastSeen + 1 < episodes.size) return Triple(episodes[lastSeen + 1], false, true)
        return Triple(episodes[0], false, false)
    }

    private fun seriesButtons() {
        val hadFocus = btns.findFocus() != null
        btns.removeAllViews()
        val (ep, resume, started) = nextEpisode()
        val label = when { resume -> "▶  Continuar T${ep.season} E${ep.num}"; started -> "▶  Próximo: T${ep.season} E${ep.num}"; else -> "▶  Assistir T${ep.season} E${ep.num}" }
        val play = button(label, true) { playEpisode(ep, if (resume) Lib.prog("e" + ep.id)?.pos ?: 0 else 0) }
        btns.addView(play)
        addBtn(favButton())
        val all = button("") {}
        fun paint() {
            val done = episodes.filter { it.season == curSeason }.all { Lib.isSeen("e" + it.id) }
            all.text = if (done) "✓  Temporada $curSeason assistida" else "Marcar temporada $curSeason como assistida"
            all.setTextColor(if (done) C.ok else Color.WHITE)
        }
        paint()
        all.setOnClickListener {
            val sEps = episodes.filter { it.season == curSeason }
            val done = sEps.all { Lib.isSeen("e" + it.id) }
            sEps.forEach { Lib.setSeen("e" + it.id, !done) }
            toast(if (done) "Temporada desmarcada" else "Temporada marcada como assistida")
            seriesButtons(); showSeason(curSeason, false)
            btns.getChildAt(2)?.requestFocus()
        }
        addBtn(all)
        trailerKey?.let { k -> addBtn(button("▶  Trailer") { openTrailer(k) }) }
        if (hadFocus) play.requestFocus()
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = text(label, 13f, Color.WHITE, true).apply {
        isFocusable = true; isClickable = true
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, _ -> paintChips() }
    }

    private fun paintChips() {
        val bar = seasonBar ?: return
        for (i in 0 until bar.childCount) {
            val c = bar.getChildAt(i) as TextView
            val on = c.tag == curSeason
            c.setTextColor(if (on) C.bg else Color.WHITE)
            c.background = rounded(if (on) Color.WHITE else 0x1FFFFFFF, dpf(18f), if (c.isFocused) dp(3) else 0, C.ac)
        }
    }

    private fun showSeason(sn: String, focus: Boolean) {
        curSeason = sn
        paintChips()
        val list = epList ?: return
        list.removeAllViews()
        episodes.filter { it.season == sn }.forEach { ep -> list.addView(epRow(ep), llp(MATCH, WRAP).apply { topMargin = dp(8) }) }
        if (btns.childCount >= 3) {
            val all = btns.getChildAt(2) as TextView
            val done = episodes.filter { it.season == curSeason }.all { Lib.isSeen("e" + it.id) }
            all.text = if (done) "✓  Temporada $curSeason assistida" else "Marcar temporada $curSeason como assistida"
            all.setTextColor(if (done) C.ok else Color.WHITE)
        }
        if (focus) list.getChildAt(0)?.requestFocus()
    }

    private fun epRow(ep: Episode): View {
        val k = "e" + ep.id
        val seen = Lib.isSeen(k)
        val p = Lib.prog(k)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val n = rounded(C.bg2, dpf(10f))
            val f = rounded(C.bg3, dpf(10f), dp(3), C.ac)
            background = n
            setOnFocusChangeListener { v, has -> v.background = if (has) f else n }
            setOnClickListener { playEpisode(ep, if (p != null && p.pos > 30000 && !seen) p.pos else 0) }
        }
        val th = FrameLayout(this).apply { background = rounded(C.bg3, dpf(6f)); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
        th.addView(text(ep.num, 20f, C.tx3, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (ep.image.isNotEmpty()) th.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; load(ep.image) }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (seen) th.addView(text("✓", 12f, C.ok, true).apply { gravity = Gravity.CENTER; background = rounded(0xD90A0B0F.toInt(), dpf(11f)) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
        row.addView(th, llp(dp(128), dp(72)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        col.addView(text("${ep.num}. ${ep.title.ifEmpty { "Episódio" }}" + if (seen) "  · assistido" else "", 14f, if (seen) 0xFFCFE9D3.toInt() else Color.WHITE, true, 1))
        val info = ep.plot.ifEmpty { if (ep.duration.isNotEmpty()) "Duração ${ep.duration}" else "" }
        if (info.isNotEmpty()) col.addView(text(info, 12f, C.tx3, false, 2).apply { setLineSpacing(0f, 1.25f) }, llp(MATCH, WRAP).apply { topMargin = dp(4) })
        if (p != null && p.pos > 30000 && !seen) col.addView(progressBar(p.pct), llp(dp(160), dp(3)).apply { topMargin = dp(8) })
        row.addView(col, llp(0, WRAP, 1f))
        return row
    }

    private fun playEpisode(ep: Episode, start: Long) {
        Now.series = item
        Now.episodes = episodes
        startActivity(
            Intent(this, PlayerActivity::class.java).putExtra("mode", "episode").putExtra("id", ep.id).putExtra("ext", ep.ext)
                .putExtra("title", "${item.name} · T${ep.season} E${ep.num}" + if (ep.title.isNotEmpty()) " — ${ep.title}" else "")
                .putExtra("key", "e" + ep.id).putExtra("icon", item.icon).putExtra("name", item.name).putExtra("sub", "T${ep.season} E${ep.num}")
                .putExtra("sid", item.id).putExtra("start", start)
                .putExtra("plot", ep.plot.ifEmpty { item.plot }).putExtra("img", ep.image.ifEmpty { Art.backdrop(item) }).putExtra("ept", ep.title)
        )
    }

    /** coloca na página antes do bloco "Parecidos" */
    private fun addBody(v: View, lp: LinearLayout.LayoutParams) = body.addView(v, body.indexOfChild(similarBox), lp)
    private fun addBody(v: View) = body.addView(v, body.indexOfChild(similarBox))

    /* ---------- Descobrir: trailer, classificação, elenco e parecidos ---------- */
    private fun loadExtras() {
        lifecycleScope.launch {
            val ex = withContext(Dispatchers.IO) { try { Discover.extra(item) } catch (e: Exception) { null } } ?: return@launch
            cert = ex.cert; exGenre = ex.genres; exRuntime = ex.runtime
            setMeta(item.genre, "")
            val p = plotTv.text.toString()
            if (ex.overview.isNotEmpty() && (p.isEmpty() || p.startsWith("Sem sinopse") || p.startsWith("Carregando"))) plotTv.text = ex.overview
            if (ex.backdrop.isNotEmpty()) backdrop.load(ex.backdrop) { crossfade(300) }
            if (ex.trailer != null) {
                trailerKey = ex.trailer
                val hadFocus = btns.findFocus() != null
                if (item.kind == Kind.MOVIE) movieButtons() else if (episodes.isNotEmpty()) seriesButtons()
                else addBtn(button("▶  Trailer") { openTrailer(ex.trailer) })
                if (!hadFocus) btns.clearFocus()
            }
            if (ex.cast.isNotEmpty()) showCast(ex.cast)
            if (ex.similar.isNotEmpty()) showSimilar(ex.similar)
        }
    }

    private fun showCast(cast: List<Pair<String, String>>) {
        castBox.removeAllViews()
        castBox.addView(text("Elenco", 16f, Color.WHITE, true), llp(WRAP, WRAP).apply { topMargin = dp(20) })
        val sv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(4)) }
        cast.forEach { (name, photo) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnFocusChangeListener { v, f -> v.background = if (f) rounded(0x26FFFFFF, dpf(10f), dp(2), C.ac) else null }
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.bg3) }
                clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                if (photo.isNotEmpty()) load(photo) { crossfade(200) }
            }
            col.addView(img, llp(dp(68), dp(68)))
            col.addView(text(name, 11f, C.tx2, false, 2).apply { gravity = Gravity.CENTER }, llp(dp(84), WRAP).apply { topMargin = dp(6) })
            row.addView(col, llp(WRAP, WRAP).apply { marginEnd = dp(6) })
        }
        sv.addView(row)
        castBox.addView(sv, llp(MATCH, WRAP))
    }

    private fun showSimilar(list: List<Item>) {
        similarBox.removeAllViews()
        similarBox.addView(text("Parecidos na sua lista", 16f, Color.WHITE, true), llp(WRAP, WRAP).apply { topMargin = dp(22) })
        val rv = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@DetailActivity, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
            adapter = CardAdapter(list.take(20).map { x ->
                Card(x, x.name, x.kind) { startActivity(Intent(this@DetailActivity, DetailActivity::class.java).putExtra("kind", x.kind.name).putExtra("id", x.id)) }
            })
        }
        similarBox.addView(rv, llp(MATCH, WRAP).apply { marginStart = -dp(6) })
    }

    /** toca o trailer dentro do app */
    private fun openTrailer(key: String) {
        startActivity(Intent(this, TrailerActivity::class.java).putExtra("key", key))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
