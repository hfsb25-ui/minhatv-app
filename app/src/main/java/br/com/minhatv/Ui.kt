package br.com.minhatv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/* ================= cores e medidas ================= */
object C {
    val bg = 0xFF0A0B0F.toInt()
    val bg2 = 0xFF12151C.toInt()
    val bg3 = 0xFF1A1E28.toInt()
    val line = 0xFF2A2F3A.toInt()
    val tx = 0xFFF2F2F4.toInt()
    val tx2 = 0xFFB9BDC7.toInt()
    val tx3 = 0xFF8A8F9B.toInt()
    val ac = 0xFFF5D400.toInt()
    val live = 0xFFFF6B6B.toInt()
    val ok = 0xFF7FD18B.toInt()
    val pal = intArrayOf(0xFF22324A.toInt(), 0xFF3D2430.toInt(), 0xFF1F3D2E.toInt(), 0xFF3A3420.toInt(), 0xFF2A2A3F.toInt(), 0xFF3B2A1E.toInt(), 0xFF203A3D.toInt(), 0xFF33223D.toInt(), 0xFF2D3320.toInt(), 0xFF3D2A24.toInt())
    fun colorFor(s: String): Int {
        var h = 0
        s.forEach { h = h * 31 + it.code }
        return pal[(h and 0x7fffffff) % pal.size]
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun Context.text(s: String = "", size: Float = 14f, color: Int = C.tx, bold: Boolean = false, lines: Int = 0): TextView =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
        includeFontPadding = false
    }

/** botão grande que funciona com o controle remoto */
fun Context.button(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
    text(label, 15f, if (primary) C.bg else Color.WHITE, true).apply {
        isFocusable = true; isClickable = true
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        val normal = rounded(if (primary) Color.WHITE else 0x29FFFFFF, dpf(8f))
        val focused = rounded(if (primary) Color.WHITE else 0x55FFFFFF, dpf(8f), dp(3), C.ac)
        background = normal
        setOnFocusChangeListener { v, f ->
            v.background = if (f) focused else normal
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start()
        }
        setOnClickListener { onClick() }
    }

fun lp(w: Int, h: Int) = ViewGroup.LayoutParams(w, h)
fun llp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/* ================= busca de capa para filmes sem capa ================= */
object Covers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sem = Semaphore(4)
    private val asked = HashSet<String>()

    fun fill(item: Item, onDone: (String) -> Unit) {
        if (item.kind != Kind.MOVIE || item.icon.isNotEmpty() || !asked.add(item.id)) return
        scope.launch {
            sem.withPermit {
                try {
                    val i = Repo.vodInfo(item.id)
                    val u = i.s("movie_image").ifEmpty { i.s("cover_big") }
                    if (item.plot.isEmpty()) item.plot = i.s("plot")
                    if (u.isNotEmpty()) {
                        item.icon = u
                        withContext(Dispatchers.Main) { onDone(u) }
                    }
                } catch (e: Exception) {}
            }
        }
    }
}

/** moldura que mantém sempre a proporção (pôster 2:3 ou canal 16:9), seja qual for a largura */
class RatioFrame(c: Context, private val ratio: Float) : FrameLayout(c) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0) {
            val h = (w * ratio).toInt()
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/* ================= capas (cards) ================= */
class Card(
    val item: Item?,
    val title: String,
    val kind: Kind,
    val icon: String = item?.icon ?: "",
    val sub: String = "",
    val progress: Int = -1,
    val rank: Int = 0,
    val onFocus: (() -> Unit)? = null,
    val onClick: () -> Unit
)

class CardVH(
    val root: LinearLayout, val box: FrameLayout, val img: ImageView, val fb: TextView,
    val title: TextView, val sub: TextView, val bar: View, val barBg: View, val badge: TextView, val num: TextView, val rankTv: TextView?
) : RecyclerView.ViewHolder(root)

class CardAdapter(var cards: List<Card>, private val ranked: Boolean = false, private val fill: Boolean = false) : RecyclerView.Adapter<CardVH>() {
    override fun getItemCount() = cards.size
    override fun getItemViewType(position: Int) = if (cards[position].kind == Kind.LIVE) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
        val c = parent.context
        val live = viewType == 1
        val w = if (live) c.dp(170) else c.dp(118)
        val h = if (live) c.dp(96) else c.dp(177)
        val outer = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            isFocusable = true; isClickable = true
            clipChildren = false; clipToPadding = false
            setPadding(c.dp(6), c.dp(8), c.dp(6), c.dp(8))
            layoutParams = RecyclerView.LayoutParams(if (fill) MATCH else WRAP, WRAP)
        }
        var rankTv: TextView? = null
        if (ranked) {
            rankTv = c.text("", 96f, C.bg, true).apply {
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = c.dpf(2f)
                setTextColor(0xFF7D828E.toInt())
                setPadding(0, 0, 0, c.dp(30))
                gravity = Gravity.BOTTOM
            }
            outer.addView(rankTv, llp(WRAP, WRAP).apply { marginEnd = -c.dp(14) })
        }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val box = RatioFrame(c, if (live) 9f / 16f else 1.5f).apply {
            background = rounded(C.bg3, c.dpf(8f))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val fb = c.text("", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8)) }
        val img = ImageView(c).apply {
            scaleType = if (live) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (live) setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
        }
        val barBg = View(c).apply { setBackgroundColor(0x33FFFFFF) }
        val bar = View(c).apply { setBackgroundColor(C.ac) }
        val badge = c.text("", 12f, C.ok, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xCC0A0B0F.toInt(), c.dpf(12f))
        }
        val num = c.text("", 10f, Color.WHITE, true).apply {
            setPadding(c.dp(5), c.dp(2), c.dp(5), c.dp(2))
            background = rounded(0x8C000000.toInt(), c.dpf(4f))
        }
        box.addView(fb, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(barBg, FrameLayout.LayoutParams(MATCH, c.dp(4), Gravity.BOTTOM))
        box.addView(bar, FrameLayout.LayoutParams(0, c.dp(4), Gravity.BOTTOM))
        box.addView(badge, FrameLayout.LayoutParams(c.dp(24), c.dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, c.dp(6), c.dp(6), 0) })
        box.addView(num, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(c.dp(6), c.dp(6), 0, 0) })
        val title = c.text("", 12f, 0xFFE3E4E8.toInt(), true, 1)
        val sub = c.text("", 11f, C.tx3, false, 1)
        col.addView(box, llp(if (fill) MATCH else w, WRAP))
        col.addView(title, llp(if (fill) MATCH else w, WRAP).apply { topMargin = c.dp(6) })
        col.addView(sub, llp(if (fill) MATCH else w, WRAP).apply { topMargin = c.dp(2) })
        outer.addView(col, llp(if (fill) MATCH else WRAP, WRAP))
        val normalBg = box.background
        val focusBg = rounded(C.bg3, c.dpf(8f), c.dp(3), C.ac)
        outer.setOnFocusChangeListener { v, f ->
            box.background = if (f) focusBg else normalBg
            title.setTextColor(if (f) Color.WHITE else 0xFFE3E4E8.toInt())
            rankTv?.setTextColor(if (f) C.ac else 0xFF7D828E.toInt())
            v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(130).start()
            v.translationZ = if (f) 10f else 0f
            if (f) (v.tag as? Card)?.onFocus?.invoke()
        }
        return CardVH(outer, box, img, fb, title, sub, bar, barBg, badge, num, rankTv)
    }

    override fun onBindViewHolder(h: CardVH, position: Int) {
        val card = cards[position]
        h.root.tag = card
        h.root.setOnClickListener { card.onClick() }
        h.title.text = card.title
        h.sub.text = card.sub
        h.sub.visibility = if (card.sub.isEmpty()) View.GONE else View.VISIBLE
        h.fb.text = card.title
        h.box.setBackgroundColor(0)
        h.box.background = rounded(C.colorFor(card.title), h.root.context.dpf(8f))
        if (card.kind == Kind.LIVE) h.box.background = rounded(0xFF1B2230.toInt(), h.root.context.dpf(8f))
        h.rankTv?.text = if (card.rank > 0) card.rank.toString() else ""
        h.num.text = card.item?.num ?: ""
        h.num.visibility = if (card.kind == Kind.LIVE && !card.item?.num.isNullOrEmpty()) View.VISIBLE else View.GONE
        // progresso / assistido / favorito
        var pct = card.progress
        var badge = ""
        val it = card.item
        if (it != null && pct < 0 && it.kind == Kind.MOVIE) {
            if (Lib.isSeen(it.key)) badge = "✓" else Lib.prog(it.key)?.let { p -> pct = p.pct }
        }
        if (it != null && it.kind == Kind.LIVE && Lib.isFav(it.key)) badge = "★"
        h.badge.text = badge
        h.badge.setTextColor(if (badge == "★") C.ac else C.ok)
        h.badge.visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
        if (pct > 0) {
            h.bar.visibility = View.VISIBLE; h.barBg.visibility = View.VISIBLE
            h.box.post { h.bar.layoutParams = h.bar.layoutParams.apply { width = h.box.width * pct / 100 }; h.bar.requestLayout() }
        } else { h.bar.visibility = View.GONE; h.barBg.visibility = View.GONE }
        loadImg(h, card.icon)
        if (it != null && it.icon.isEmpty() && it.kind == Kind.MOVIE) {
            Covers.fill(it) { u -> if (h.root.tag === card) loadImg(h, u) }
        }
    }

    private fun loadImg(h: CardVH, url: String) {
        h.fb.visibility = View.VISIBLE
        h.img.setImageDrawable(null)
        if (url.isEmpty()) return
        h.img.load(url) {
            crossfade(150)
            listener(onSuccess = { _, _ -> h.fb.visibility = View.GONE })
        }
    }
}

/* ================= fileiras (estilo Netflix) ================= */
sealed class HomeEntry {
    class Hero(val view: View) : HomeEntry()
    class Row(val title: String, val cards: List<Card>, val badge: String = "", val badgeColor: Int = 0, val extra: String = "", val ranked: Boolean = false, val live: Boolean = false) : HomeEntry()
    class Note(val text: String) : HomeEntry()
}

class RowsAdapter(var entries: List<HomeEntry>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val pool = RecyclerView.RecycledViewPool()

    class HeroVH(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)
    class RowVH(val root: LinearLayout, val title: TextView, val badge: TextView, val extra: TextView, val list: RecyclerView) : RecyclerView.ViewHolder(root)
    class NoteVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun getItemCount() = entries.size
    override fun getItemViewType(position: Int) = when (entries[position]) { is HomeEntry.Hero -> 0; is HomeEntry.Row -> if ((entries[position] as HomeEntry.Row).ranked) 3 else 1; is HomeEntry.Note -> 2 }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val c = parent.context
        return when (viewType) {
            0 -> HeroVH(FrameLayout(c).apply { layoutParams = RecyclerView.LayoutParams(MATCH, WRAP) })
            2 -> NoteVH(c.text("", 13f, C.tx3).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(c.dp(40), c.dp(6), c.dp(40), c.dp(10)) }
                setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
                background = rounded(0, c.dpf(8f), c.dp(1), C.line)
                setLineSpacing(0f, 1.3f)
            })
            else -> {
                val root = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                    setPadding(0, c.dp(10), 0, c.dp(4))
                    clipChildren = false; clipToPadding = false
                }
                val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(40), 0, c.dp(40), 0) }
                val title = c.text("", 17f, Color.WHITE, true)
                val badge = c.text("", 12f, Color.WHITE, true).apply { setPadding(c.dp(7), c.dp(3), c.dp(7), c.dp(3)) }
                val extra = c.text("", 11f, C.tx3)
                head.addView(title)
                head.addView(badge, llp(WRAP, WRAP).apply { marginStart = c.dp(8) })
                head.addView(extra, llp(WRAP, WRAP).apply { marginStart = c.dp(8) })
                val list = RecyclerView(c).apply {
                    layoutManager = LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false)
                    setRecycledViewPool(pool)
                    clipChildren = false; clipToPadding = false
                    setPadding(c.dp(34), c.dp(4), c.dp(34), c.dp(4))
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    setItemViewCacheSize(8)
                }
                root.addView(head)
                root.addView(list, llp(MATCH, WRAP))
                RowVH(root, title, badge, extra, list)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val e = entries[position]) {
            is HomeEntry.Hero -> {
                val f = (holder as HeroVH).frame
                f.removeAllViews()
                (e.view.parent as? ViewGroup)?.removeView(e.view)
                f.addView(e.view)
            }
            is HomeEntry.Note -> (holder as NoteVH).tv.text = e.text
            is HomeEntry.Row -> {
                val h = holder as RowVH
                h.title.text = e.title
                h.badge.text = e.badge
                h.badge.visibility = if (e.badge.isEmpty()) View.GONE else View.VISIBLE
                if (e.badge.isNotEmpty()) h.badge.background = rounded(e.badgeColor, h.root.context.dpf(4f))
                h.extra.text = e.extra
                val a = h.list.adapter as? CardAdapter
                if (a == null || h.list.tag != e.ranked) {
                    h.list.adapter = CardAdapter(e.cards, e.ranked)
                    h.list.tag = e.ranked
                } else {
                    a.cards = e.cards
                    a.notifyDataSetChanged()
                }
            }
        }
    }
}

/** barra de título simples para seções e telas */
fun Context.vspace(h: Int): View = View(this).apply { layoutParams = ViewGroup.LayoutParams(1, dp(h)) }
