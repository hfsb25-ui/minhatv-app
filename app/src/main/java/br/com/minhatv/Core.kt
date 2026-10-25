package br.com.minhatv

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/* ================= aplicação ================= */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this)
    }

    companion object {
        lateinit var instance: App
    }
}

val http: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
}

const val UA = "MinhaTV/1.0 (Linux; Android)"

/* ================= armazenamento simples ================= */
object Store {
    private lateinit var p: SharedPreferences
    fun init(c: Context) {
        p = c.getSharedPreferences("minhatv", Context.MODE_PRIVATE)
    }

    fun str(k: String): String? = p.getString(k, null)
    fun put(k: String, v: String?) {
        val e = p.edit()
        if (v == null) e.remove(k) else e.putString(k, v)
        e.apply()
    }
}

/* ================= modelos ================= */
enum class Kind { LIVE, MOVIE, SERIES }

data class Cat(val id: String, val name: String)

class Item(
    val kind: Kind,
    val id: String,
    val name: String,
    var icon: String,
    val cat: String,
    val rating: Double,
    val year: Int,
    val added: Long,
    val ext: String,
    val num: String,
    var plot: String,
    val tmdb: String,
    val genre: String,
    val cast: String
) {
    val key: String
        get() = (when (kind) {
            Kind.MOVIE -> "m"
            Kind.SERIES -> "s"
            Kind.LIVE -> "l"
        }) + id
}

data class Episode(
    val id: String,
    val season: String,
    val num: String,
    val title: String,
    val ext: String,
    val plot: String,
    val image: String,
    val duration: String
)

fun JSONObject.s(k: String): String {
    val v = opt(k)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

/** aceita lista ou objeto com itens (alguns painéis devolvem objeto) */
fun toArr(text: String): JSONArray? {
    val t = text.trim()
    return try {
        when {
            t.startsWith("[") -> JSONArray(t)
            t.startsWith("{") -> {
                val o = JSONObject(t)
                if (o.has("user_info") || o.has("erro")) null
                else JSONArray().also { a ->
                    o.keys().forEach { k -> (o.opt(k) as? JSONObject)?.let { a.put(it) } }
                }
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

private val yearInName = Regex("""\((19|20)\d{2}\)""")

fun parseItem(kind: Kind, o: JSONObject): Item? {
    val id = if (kind == Kind.SERIES) o.s("series_id") else o.s("stream_id")
    if (id.isEmpty()) return null
    val name = o.s("name").ifEmpty { o.s("title") }.ifEmpty { "Sem nome" }
    val icon = if (kind == Kind.SERIES) o.s("cover") else o.s("stream_icon")
    val r = o.s("rating").toDoubleOrNull() ?: o.s("rating_5based").toDoubleOrNull()?.times(2) ?: 0.0
    val ys = o.s("year").ifEmpty { o.s("releaseDate") }.ifEmpty { o.s("release_date") }.take(4)
    val year = ys.toIntOrNull() ?: yearInName.find(name)?.value?.filter { it.isDigit() }?.toIntOrNull() ?: 0
    val added = o.s("added").ifEmpty { o.s("last_modified") }.toLongOrNull() ?: 0L
    return Item(
        kind, id, name, icon, o.s("category_id"), r, year, added,
        o.s("container_extension").ifEmpty { "mp4" }, o.s("num"), o.s("plot"),
        o.s("tmdb").ifEmpty { o.s("tmdb_id") }, o.s("genre"), o.s("cast")
    )
}

/* ================= API Xtream ================= */
object Xt {
    var server = ""
    var user = ""
    var pass = ""
    var formats: List<String> = listOf("ts", "m3u8")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun encPath(s: String) = enc(s).replace("+", "%20")

    fun get(params: Map<String, String> = emptyMap()): String {
        val q = (mapOf("username" to user, "password" to pass) + params).entries
            .joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val req = Request.Builder().url("$server/player_api.php?$q").header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    fun url(kind: Kind, id: String, ext: String, forceHls: Boolean = false): String {
        val u = encPath(user)
        val p = encPath(pass)
        return when (kind) {
            Kind.LIVE -> "$server/live/$u/$p/$id." + (if (!forceHls && formats.contains("ts")) "ts" else "m3u8")
            Kind.MOVIE -> "$server/movie/$u/$p/$id.$ext"
            Kind.SERIES -> "$server/series/$u/$p/$id.$ext"
        }
    }

    /** aceita "servidor:porta", "http://..." ou o link M3U inteiro (get.php?username=..&password=..) */
    fun parseServer(raw: String): Triple<String, String?, String?> {
        var s = raw.trim()
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        return try {
            val uri = android.net.Uri.parse(s)
            val origin = uri.scheme + "://" + uri.host + (if (uri.port > 0) ":" + uri.port else "")
            Triple(origin, uri.getQueryParameter("username"), uri.getQueryParameter("password"))
        } catch (e: Exception) {
            Triple(s.trimEnd('/'), null, null)
        }
    }
}

/* ================= catálogo ================= */
object Repo {
    var liveCats = listOf<Cat>()
    var vodCats = listOf<Cat>()
    var serCats = listOf<Cat>()
    var live = listOf<Item>()
    var vod = listOf<Item>()
    var series = listOf<Item>()
    var expDate = 0L
    var userName = ""
    var version = 0

    private fun dir() = File(App.instance.filesDir, "cache").apply { mkdirs() }
    private fun write(n: String, t: String) = try { File(dir(), n).writeText(t) } catch (e: Exception) {}
    private fun read(n: String): String? = try { File(dir(), n).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }

    private fun cats(t: String?): List<Cat> {
        val a = t?.let { toArr(it) } ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Cat(it.s("category_id"), it.s("category_name")) } }
    }

    private fun items(k: Kind, t: String?): List<Item> {
        val a = t?.let { toArr(it) } ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { parseItem(k, it) } }
    }

    fun hasCredentials(): Boolean {
        val c = Store.str("cred") ?: return false
        return try {
            val o = JSONObject(c)
            Xt.server = o.s("server"); Xt.user = o.s("user"); Xt.pass = o.s("pass")
            Xt.server.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun saveCredentials() {
        Store.put("cred", JSONObject().put("server", Xt.server).put("user", Xt.user).put("pass", Xt.pass).toString())
    }

    fun logout() {
        Store.put("cred", null)
        Store.put("trend", null)
        logoutCacheOnly()
    }

    /** apaga só o catálogo guardado (usado ao entrar com outra lista) */
    fun logoutCacheOnly() {
        dir().listFiles()?.forEach { it.delete() }
        live = emptyList(); vod = emptyList(); series = emptyList()
        liveCats = emptyList(); vodCats = emptyList(); serCats = emptyList()
    }

    /** confere usuário e senha; devolve mensagem de erro ou null */
    fun login(): String? {
        val t = try { Xt.get() } catch (e: Exception) { return "Não consegui conectar ao servidor (${e.message})." }
        val o = try { JSONObject(t) } catch (e: Exception) { return "O servidor respondeu algo inesperado. Confira o endereço." }
        val ui = o.optJSONObject("user_info") ?: return "O servidor respondeu algo inesperado. Confira o endereço."
        if (ui.s("auth") == "0") return "Usuário ou senha inválidos."
        val st = ui.s("status")
        if (st.isNotEmpty() && !st.equals("active", true)) return "Sua conta está com status \"$st\". Fale com o seu provedor."
        expDate = ui.s("exp_date").toLongOrNull() ?: 0L
        userName = ui.s("username").ifEmpty { Xt.user }
        ui.optJSONArray("allowed_output_formats")?.let { f -> Xt.formats = (0 until f.length()).map { f.optString(it) } }
        Store.put("acct", JSONObject().put("exp", expDate).put("user", userName).put("formats", JSONArray(Xt.formats)).toString())
        return null
    }

    /** abre na hora com o que está guardado na TV */
    fun loadCache(): Boolean {
        try {
            Store.str("acct")?.let {
                val o = JSONObject(it)
                expDate = o.optLong("exp"); userName = o.s("user")
                o.optJSONArray("formats")?.let { f -> Xt.formats = (0 until f.length()).map { i -> f.optString(i) } }
            }
        } catch (e: Exception) {}
        liveCats = cats(read("live_cats")); vodCats = cats(read("vod_cats")); serCats = cats(read("ser_cats"))
        live = items(Kind.LIVE, read("live")); vod = items(Kind.MOVIE, read("vod")); series = items(Kind.SERIES, read("series"))
        if (live.isNotEmpty() || vod.isNotEmpty() || series.isNotEmpty()) { version++; return true }
        return false
    }

    fun cacheAge(): Long = File(dir(), "vod").let { if (it.exists()) System.currentTimeMillis() - it.lastModified() else Long.MAX_VALUE }

    /** baixa tudo do servidor e guarda na TV */
    fun fetchAll(progress: (String) -> Unit) {
        progress("Baixando categorias…")
        val lc = safe { Xt.get(mapOf("action" to "get_live_categories")) }
        val vc = safe { Xt.get(mapOf("action" to "get_vod_categories")) }
        val sc = safe { Xt.get(mapOf("action" to "get_series_categories")) }
        val nLiveCats = cats(lc); val nVodCats = cats(vc); val nSerCats = cats(sc)
        progress("Baixando canais…")
        val (l, lt) = fetchList("get_live_streams", Kind.LIVE, nLiveCats, "canais", progress)
        progress("Baixando filmes…")
        val (v, vt) = fetchList("get_vod_streams", Kind.MOVIE, nVodCats, "filmes", progress)
        progress("Baixando séries…")
        val (s, st) = fetchList("get_series", Kind.SERIES, nSerCats, "séries", progress)
        if (l.isEmpty() && v.isEmpty() && s.isEmpty()) throw Exception("O servidor não mandou nenhum canal, filme ou série.")
        liveCats = nLiveCats; vodCats = nVodCats; serCats = nSerCats
        live = l; vod = v; series = s
        version++
        lc?.let { write("live_cats", it) }; vc?.let { write("vod_cats", it) }; sc?.let { write("ser_cats", it) }
        write("live", lt); write("vod", vt); write("series", st)
    }

    private fun safe(f: () -> String): String? = try { f() } catch (e: Exception) { null }

    /** lista inteira; se falhar ou vier vazia, baixa categoria por categoria */
    private fun fetchList(action: String, kind: Kind, cs: List<Cat>, label: String, progress: (String) -> Unit): Pair<List<Item>, String> {
        try {
            val t = Xt.get(mapOf("action" to action))
            val l = items(kind, t)
            if (l.isNotEmpty()) return l to t
        } catch (e: Exception) {}
        val all = JSONArray()
        val out = ArrayList<Item>()
        cs.forEachIndexed { i, c ->
            progress("Baixando $label… ${i + 1} de ${cs.size} categorias")
            try {
                val a = toArr(Xt.get(mapOf("action" to action, "category_id" to c.id)))
                if (a != null) for (j in 0 until a.length()) {
                    val o = a.optJSONObject(j) ?: continue
                    all.put(o); parseItem(kind, o)?.let { out.add(it) }
                }
            } catch (e: Exception) {}
        }
        return out to all.toString()
    }

    fun list(k: Kind) = when (k) { Kind.LIVE -> live; Kind.MOVIE -> vod; Kind.SERIES -> series }
    fun catsOf(k: Kind) = when (k) { Kind.LIVE -> liveCats; Kind.MOVIE -> vodCats; Kind.SERIES -> serCats }
    fun find(k: Kind, id: String): Item? = list(k).firstOrNull { it.id == id }
    fun findKey(key: String): Item? {
        if (key.length < 2) return null
        val k = when (key[0]) { 'm' -> Kind.MOVIE; 's' -> Kind.SERIES; 'l' -> Kind.LIVE; else -> return null }
        return find(k, key.substring(1))
    }
    fun catName(k: Kind, id: String) = catsOf(k).firstOrNull { it.id == id }?.name ?: ""
    fun recent(l: List<Item>) = l.sortedByDescending { it.added }

    fun seriesInfo(id: String): Pair<JSONObject, List<Episode>> {
        val o = JSONObject(Xt.get(mapOf("action" to "get_series_info", "series_id" to id)))
        val eps = ArrayList<Episode>()
        val e = o.opt("episodes")
        val seasons = ArrayList<Pair<String, JSONArray>>()
        if (e is JSONObject) e.keys().forEach { k -> e.optJSONArray(k)?.let { seasons.add(k to it) } }
        else if (e is JSONArray) for (i in 0 until e.length()) e.optJSONArray(i)?.let { seasons.add((i + 1).toString() to it) }
        seasons.sortBy { it.first.toIntOrNull() ?: 0 }
        seasons.forEach { (sn, a) ->
            for (i in 0 until a.length()) {
                val ep = a.optJSONObject(i) ?: continue
                val inf = ep.optJSONObject("info") ?: JSONObject()
                eps.add(
                    Episode(
                        ep.s("id"), ep.s("season").ifEmpty { sn }, ep.s("episode_num").ifEmpty { (i + 1).toString() },
                        ep.s("title"), ep.s("container_extension").ifEmpty { "mp4" }, inf.s("plot"),
                        inf.s("movie_image").ifEmpty { inf.s("cover_big") }, inf.s("duration")
                    )
                )
            }
        }
        return (o.optJSONObject("info") ?: JSONObject()) to eps
    }

    fun vodInfo(id: String): JSONObject = JSONObject(Xt.get(mapOf("action" to "get_vod_info", "vod_id" to id))).optJSONObject("info") ?: JSONObject()
}

/* ================= minha lista, assistidos e progresso ================= */
class Prog(
    val key: String, val name: String, val sub: String, val icon: String, val pos: Long, val dur: Long,
    val kind: String, val id: String, val ext: String, val sid: String, val t: Long
) {
    fun json() = JSONObject().put("key", key).put("name", name).put("sub", sub).put("icon", icon).put("pos", pos).put("dur", dur)
        .put("kind", kind).put("id", id).put("ext", ext).put("sid", sid).put("t", t)

    val pct: Int get() = if (dur > 0) (pos * 100 / dur).toInt().coerceIn(0, 100) else 0

    companion object {
        fun of(o: JSONObject) = Prog(
            o.s("key"), o.s("name"), o.s("sub"), o.s("icon"), o.optLong("pos"), o.optLong("dur"),
            o.s("kind"), o.s("id"), o.s("ext"), o.s("sid"), o.optLong("t")
        )
    }
}

object Lib {
    private var favCache: MutableList<String>? = null
    private var seenCache: JSONObject? = null
    private var progCache: MutableList<Prog>? = null

    fun favs(): List<String> = favCache ?: run {
        val a = try { JSONArray(Store.str("fav") ?: "[]") } catch (e: Exception) { JSONArray() }
        (0 until a.length()).map { a.optString(it) }.toMutableList().also { favCache = it }
    }

    fun isFav(k: String) = favs().contains(k)
    fun toggleFav(k: String): Boolean {
        val l = favs().toMutableList()
        val on = if (l.remove(k)) false else { l.add(0, k); true }
        favCache = l
        Store.put("fav", JSONArray(l).toString())
        return on
    }

    private fun seen(): JSONObject = seenCache ?: (try { JSONObject(Store.str("seen") ?: "{}") } catch (e: Exception) { JSONObject() }).also { seenCache = it }
    fun isSeen(k: String) = seen().has(k)
    fun setSeen(k: String, on: Boolean) {
        val o = seen()
        if (on) o.put(k, System.currentTimeMillis()) else o.remove(k)
        Store.put("seen", o.toString())
        if (on) dropProg(k)
    }

    fun seenKeys(prefix: Char): List<String> {
        val o = seen()
        return o.keys().asSequence().filter { it.startsWith(prefix) }.sortedByDescending { o.optLong(it) }.toList()
    }

    fun progs(): List<Prog> = progCache ?: run {
        val a = try { JSONArray(Store.str("prog") ?: "[]") } catch (e: Exception) { JSONArray() }
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Prog.of(o) } }.toMutableList().also { progCache = it }
    }

    fun prog(k: String): Prog? = progs().firstOrNull { it.key == k }
    private fun saveProgs(l: List<Prog>) {
        progCache = l.toMutableList()
        Store.put("prog", JSONArray(l.map { it.json() }).toString())
    }

    fun saveProg(p: Prog) = saveProgs((listOf(p) + progs().filter { it.key != p.key }).take(60))
    fun dropProg(k: String) = saveProgs(progs().filter { it.key != k })
}

/* ================= Top 10 das plataformas (TMDB, Brasil) ================= */
object Trend {
    private const val KEY = "ed5ebc268ae652719b7df9c36af5cbe4"
    private const val MAX_AGE = 6 * 3600 * 1000L

    class Plat(val k: String, val n: String, val color: Int, val rx: Regex)
    class Row(val plat: Plat, val kind: Kind, val items: List<Item>)

    val PLATS = listOf(
        Plat("netflix", "Netflix", Color.parseColor("#E50914"), Regex("^netflix$", RegexOption.IGNORE_CASE)),
        Plat("prime", "Prime Video", Color.parseColor("#1A98FF"), Regex("^amazon prime video$", RegexOption.IGNORE_CASE)),
        Plat("disney", "Disney+", Color.parseColor("#113CCF"), Regex("^disney plus$", RegexOption.IGNORE_CASE)),
        Plat("max", "Max", Color.parseColor("#5822B4"), Regex("^(hbo )?max$", RegexOption.IGNORE_CASE)),
        Plat("globoplay", "Globoplay", Color.parseColor("#F2334B"), Regex("^globoplay$", RegexOption.IGNORE_CASE)),
        Plat("apple", "Apple TV+", Color.parseColor("#3A3A3C"), Regex("^apple tv( plus|\\+)$", RegexOption.IGNORE_CASE)),
        Plat("paramount", "Paramount+", Color.parseColor("#0064FF"), Regex("^paramount( plus|\\+)$", RegexOption.IGNORE_CASE))
    )

    var rows: List<Row> = emptyList()
    var updated = 0L
    var error: String? = null
    private var builtFor = -1

    private fun tm(path: String, params: Map<String, String>): JSONObject {
        val q = (params + ("api_key" to KEY)).entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val req = Request.Builder().url("https://api.themoviedb.org/3/$path?$q").header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw Exception("chave do TMDB recusada")
            if (!r.isSuccessful) throw Exception("TMDB ${r.code}")
            return JSONObject(r.body?.string() ?: "{}")
        }
    }

    private fun fetchRaw(): JSONObject {
        val provs = ArrayList<JSONObject>()
        listOf("movie", "tv").forEach { k ->
            try {
                val a = tm("watch/providers/$k", mapOf("watch_region" to "BR", "language" to "pt-BR")).optJSONArray("results")
                if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { provs.add(it) }
            } catch (e: Exception) { if (k == "movie") throw e }
        }
        val out = JSONArray()
        PLATS.forEach { p ->
            val ids = provs.filter { p.rx.matches(it.s("provider_name").trim()) }.map { it.s("provider_id") }.distinct()
            if (ids.isEmpty()) return@forEach
            val po = JSONObject().put("k", p.k)
            listOf("movie", "tv").forEach { kind ->
                val arr = JSONArray()
                for (page in 1..3) {
                    try {
                        val d = tm(
                            "discover/$kind", mapOf(
                                "watch_region" to "BR", "with_watch_providers" to ids.joinToString("|"),
                                "with_watch_monetization_types" to "flatrate", "sort_by" to "popularity.desc",
                                "language" to "pt-BR", "page" to page.toString(), "include_adult" to "false"
                            )
                        )
                        val res = d.optJSONArray("results") ?: continue
                        for (i in 0 until res.length()) {
                            val r = res.optJSONObject(i) ?: continue
                            val date = if (kind == "movie") r.s("release_date") else r.s("first_air_date")
                            arr.put(
                                JSONObject().put("id", r.s("id"))
                                    .put("t", if (kind == "movie") r.s("title") else r.s("name"))
                                    .put("ot", if (kind == "movie") r.s("original_title") else r.s("original_name"))
                                    .put("y", date.take(4).toIntOrNull() ?: 0)
                                    .put("pop", r.optDouble("popularity", 0.0))
                            )
                        }
                    } catch (e: Exception) {}
                }
                po.put(kind, arr)
            }
            out.put(po)
        }
        return JSONObject().put("t", System.currentTimeMillis()).put("plats", out)
    }

    /** usa o guardado; busca de novo se tiver mais de 6 horas (ou se force) */
    fun ensure(force: Boolean = false) {
        var raw = try { Store.str("trend")?.let { JSONObject(it) } } catch (e: Exception) { null }
        val stale = raw == null || System.currentTimeMillis() - raw.optLong("t") > MAX_AGE
        if (force || stale) {
            try {
                raw = fetchRaw()
                Store.put("trend", raw.toString())
                builtFor = -1
                error = null
            } catch (e: Exception) {
                if (raw == null) { error = e.message; return }
            }
        }
        if (raw != null && builtFor != Repo.version + raw.optLong("t").toInt()) {
            build(raw)
            builtFor = Repo.version + raw.optLong("t").toInt()
        }
    }

    fun isStale(): Boolean {
        val raw = try { Store.str("trend")?.let { JSONObject(it) } } catch (e: Exception) { null }
        return raw == null || System.currentTimeMillis() - raw.optLong("t") > MAX_AGE
    }

    private class Idx(list: List<Item>) {
        val byId = HashMap<String, Item>()
        val byName = HashMap<String, MutableList<Item>>()

        init {
            list.forEach { x ->
                if (x.tmdb.isNotEmpty() && x.tmdb != "0") byId[x.tmdb] = x
                val n = norm(x.name)
                if (n.isNotEmpty()) byName.getOrPut(n) { ArrayList() }.add(x)
            }
        }
    }

    private fun find(idx: Idx, id: String, t: String, ot: String, y: Int): Item? {
        idx.byId[id]?.let { return it }
        val nt = norm(t)
        val no = norm(ot)
        val c = ArrayList<Item>()
        idx.byName[nt]?.let { c.addAll(it) }
        if (no.isNotEmpty() && no != nt) idx.byName[no]?.let { c.addAll(it) }
        if (c.isEmpty()) return null
        if (y > 0) {
            c.firstOrNull { it.year == y }?.let { return it }
            return c.firstOrNull { it.year == 0 || kotlin.math.abs(it.year - y) <= 1 }
        }
        return c[0]
    }

    private fun build(raw: JSONObject) {
        updated = raw.optLong("t")
        val iv = Idx(Repo.vod)
        val isr = Idx(Repo.series)
        val res = ArrayList<Row>()
        val plats = raw.optJSONArray("plats") ?: JSONArray()
        listOf(Kind.MOVIE, Kind.SERIES).forEach { kind ->
            for (i in 0 until plats.length()) {
                val po = plats.optJSONObject(i) ?: continue
                val plat = PLATS.firstOrNull { it.k == po.s("k") } ?: continue
                val a = po.optJSONArray(if (kind == Kind.MOVIE) "movie" else "tv") ?: continue
                val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedByDescending { it.optDouble("pop") }
                val seen = HashSet<String>()
                val items = ArrayList<Item>()
                list.forEach { x ->
                    val it = find(if (kind == Kind.MOVIE) iv else isr, x.s("id"), x.s("t"), x.s("ot"), x.optInt("y"))
                    if (it != null && seen.add(it.id)) items.add(it)
                }
                if (items.isNotEmpty()) res.add(Row(plat, kind, items))
            }
        }
        rows = res
    }

    fun forKind(kind: Kind) = rows.filter { it.kind == kind }

    private val reBrackets = Regex("""\[[^\]]*]|\{[^}]*}""")
    private val reYearParen = Regex("""\((19|20)\d{2}\)""")
    private val rePrefix = Regex("""^\s*[a-z]{2,4}\s*[:|]\s*""")
    private val rePrefix2 = Regex("""^\s*(pt|br|ptbr|pt-br|leg|dub)\s*-\s+""")
    private val reTags = Regex("""\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|dublado|legendado|dual audio|dual|dub|leg|hdr|h265|hevc|cam|ts)\b""")
    private val reNonAlnum = Regex("[^a-z0-9]+")
    private val reTrailYear = Regex("""\s(19|20)\d{2}$""")
    private val reMarks = Regex("\\p{Mn}+")

    fun norm(s: String): String {
        var x = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(reMarks, "")
        x = x.replace(reBrackets, " ").replace(reYearParen, " ").replace(rePrefix, " ").replace(rePrefix2, " ")
            .replace(reTags, " ").replace("&", " e ").replace(reNonAlnum, " ").trim()
        val y = x.replace(reTrailYear, "").trim()
        return y.ifEmpty { x }
    }
}

fun fmtDur(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${"%02d".format(m)}min" else "${m}min"
}
