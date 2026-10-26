package br.com.minhatv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Descobrir: trailer, classificação, elenco, parecidos, "porque você assistiu" e coleções.
 * Tudo vem do TMDB e é cruzado com a lista, então só aparece o que existe nela.
 */
object Discover {
    private const val IMG = "https://image.tmdb.org/t/p/"
    private const val COLL_MAX_AGE = 24 * 3600 * 1000L

    class Coll(val title: String, val kind: Kind, val items: List<Item>)
    class Extra(
        val trailer: String?, val cert: String, val cast: List<Pair<String, String>>,
        val similar: List<Item>, val overview: String, val backdrop: String, val runtime: String, val genres: String
    )

    private class Def(val title: String, val kind: String, val params: Map<String, String>)

    private val DEFS = listOf(
        Def("Universo Marvel", "movie", mapOf("with_companies" to "420", "sort_by" to "popularity.desc")),
        Def("Heróis da DC", "movie", mapOf("with_companies" to "128064|9993|174", "sort_by" to "popularity.desc")),
        Def("Animações Pixar e Disney", "movie", mapOf("with_companies" to "3|6125|2", "with_genres" to "16", "sort_by" to "popularity.desc")),
        Def("Clássicos que todo mundo ama", "movie", mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "8000")),
        Def("Cinema nacional", "movie", mapOf("with_origin_country" to "BR", "sort_by" to "vote_count.desc")),
        Def("Comédias para rir", "movie", mapOf("with_genres" to "35", "sort_by" to "popularity.desc", "vote_average.gte" to "6.3", "vote_count.gte" to "300")),
        Def("Terror e suspense", "movie", mapOf("with_genres" to "27|53", "sort_by" to "popularity.desc", "vote_count.gte" to "300")),
        Def("Ação e aventura", "movie", mapOf("with_genres" to "28|12", "sort_by" to "popularity.desc", "vote_average.gte" to "6.5", "vote_count.gte" to "500")),
        Def("Séries mais aclamadas", "tv", mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "2000"))
    )

    var colls: List<Coll> = emptyList()
    var because: Pair<Item, List<Item>>? = null
    private var builtFor = -1

    /* ---------- índices da lista (refeitos só quando a lista muda) ---------- */
    private var idxVersion = -1
    private var iv: Trend.Idx? = null
    private var isr: Trend.Idx? = null
    private fun idx(kind: Kind): Trend.Idx {
        if (idxVersion != Repo.version || iv == null || isr == null) {
            iv = Trend.Idx(Repo.vod); isr = Trend.Idx(Repo.series); idxVersion = Repo.version
        }
        return if (kind == Kind.SERIES) isr!! else iv!!
    }

    private fun matchList(kind: Kind, a: JSONArray?): List<Item> {
        if (a == null) return emptyList()
        val out = ArrayList<Item>(); val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val r = a.optJSONObject(i) ?: continue
            val t = r.s("title").ifEmpty { r.s("name") }
            val ot = r.s("original_title").ifEmpty { r.s("original_name") }
            val y = (r.s("release_date").ifEmpty { r.s("first_air_date") }).take(4).toIntOrNull() ?: 0
            val it = Trend.find(idx(kind), r.s("id"), t, ot, y)
            if (it != null && seen.add(it.id)) { out.add(it); Art.put(it, r.s("backdrop_path")) }
        }
        return out
    }

    /* ---------- id do TMDB de um item da lista ---------- */
    private val idCache: JSONObject by lazy { try { JSONObject(Store.str("tmdbids") ?: "{}") } catch (e: Exception) { JSONObject() } }

    fun tmdbId(it: Item): String? {
        if (it.tmdb.toIntOrNull() != null && it.tmdb != "0") return it.tmdb
        val cached = idCache.optString(it.key)
        if (cached.isNotEmpty()) return if (cached == "-") null else cached
        val name = Trend.norm(it.name)
        if (name.isEmpty()) return null
        val movie = it.kind != Kind.SERIES
        val p = HashMap<String, String>()
        p["query"] = name; p["language"] = "pt-BR"; p["include_adult"] = "false"
        if (it.year > 0) p[if (movie) "year" else "first_air_date_year"] = it.year.toString()
        var res = try { Trend.tm(if (movie) "search/movie" else "search/tv", p).optJSONArray("results") } catch (e: Exception) { null }
        if ((res == null || res.length() == 0) && it.year > 0) {
            p.remove("year"); p.remove("first_air_date_year")
            res = try { Trend.tm(if (movie) "search/movie" else "search/tv", p).optJSONArray("results") } catch (e: Exception) { null }
        }
        val id = res?.optJSONObject(0)?.s("id") ?: ""
        idCache.put(it.key, id.ifEmpty { "-" })
        Store.put("tmdbids", idCache.toString())
        return id.ifEmpty { null }
    }

    /* ---------- detalhes extras da página do filme/série ---------- */
    fun extra(it: Item): Extra? {
        val id = tmdbId(it) ?: return null
        val movie = it.kind != Kind.SERIES
        val o = try {
            Trend.tm(
                (if (movie) "movie/" else "tv/") + id, mapOf(
                    "language" to "pt-BR", "include_video_language" to "pt,en,null",
                    "append_to_response" to if (movie) "videos,credits,release_dates,recommendations" else "videos,credits,content_ratings,recommendations"
                )
            )
        } catch (e: Exception) { return null }
        // trailer: prefere português, depois qualquer trailer no YouTube
        var trailer: String? = null
        o.optJSONObject("videos")?.optJSONArray("results")?.let { v ->
            val list = (0 until v.length()).mapNotNull { v.optJSONObject(it) }.filter { x -> x.s("site") == "YouTube" }
            val best = list.firstOrNull { x -> x.s("type") == "Trailer" && x.s("iso_639_1") == "pt" }
                ?: list.firstOrNull { x -> x.s("type") == "Trailer" }
                ?: list.firstOrNull { x -> x.s("type") == "Teaser" }
            trailer = best?.s("key")
        }
        // classificação indicativa do Brasil
        var cert = ""
        if (movie) o.optJSONObject("release_dates")?.optJSONArray("results")?.let { r ->
            for (i in 0 until r.length()) {
                val c = r.optJSONObject(i) ?: continue
                if (c.s("iso_3166_1") != "BR") continue
                val d = c.optJSONArray("release_dates") ?: continue
                for (j in 0 until d.length()) { val ce = d.optJSONObject(j)?.s("certification") ?: ""; if (ce.isNotEmpty()) { cert = ce; break } }
            }
        } else o.optJSONObject("content_ratings")?.optJSONArray("results")?.let { r ->
            for (i in 0 until r.length()) { val c = r.optJSONObject(i) ?: continue; if (c.s("iso_3166_1") == "BR") cert = c.s("rating") }
        }
        val cast = ArrayList<Pair<String, String>>()
        o.optJSONObject("credits")?.optJSONArray("cast")?.let { c ->
            for (i in 0 until minOf(14, c.length())) {
                val p = c.optJSONObject(i) ?: continue
                val photo = p.s("profile_path").let { s -> if (s.isNotEmpty()) IMG + "w185" + s else "" }
                cast.add(p.s("name") to photo)
            }
        }
        val similar = matchList(it.kind, o.optJSONObject("recommendations")?.optJSONArray("results")).filter { x -> x.id != it.id }
        val runtime = if (movie) o.optInt("runtime").let { m -> if (m > 0) "${m / 60}h ${"%02d".format(m % 60)}min" else "" }
        else o.optInt("number_of_seasons").let { n -> if (n > 0) "$n temporada" + (if (n > 1) "s" else "") else "" }
        val genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.optJSONObject(it)?.s("name") }.joinToString(", ") } ?: ""
        val bd = o.s("backdrop_path").let { s -> if (s.isNotEmpty()) IMG + "w1280" + s else "" }
        return Extra(trailer, cert, cast, similar, o.s("overview"), bd, runtime, genres)
    }

    /* ---------- logo oficial e imagem larga de um título (para o topo da tela) ---------- */
    class ArtInfo(val logo: String, val backdrop: String)
    private val artCache: JSONObject by lazy { try { JSONObject(Store.str("tmdbart") ?: "{}") } catch (e: Exception) { JSONObject() } }

    fun artCached(it: Item): ArtInfo? = artCache.optJSONObject(it.key)?.let { o -> ArtInfo(o.s("l"), o.s("b")) }

    fun art(it: Item): ArtInfo? {
        artCached(it)?.let { return it }
        if (it.kind == Kind.LIVE) return null
        val id = tmdbId(it) ?: run { saveArt(it, "", ""); return null }
        val movie = it.kind != Kind.SERIES
        val o = try { Trend.tm((if (movie) "movie/" else "tv/") + id + "/images", mapOf("include_image_language" to "pt,en,null")) } catch (e: Exception) { return null }
        var logo = ""
        o.optJSONArray("logos")?.let { a ->
            val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            val best = l.firstOrNull { x -> x.s("iso_639_1") == "pt" } ?: l.firstOrNull { x -> x.s("iso_639_1") == "en" } ?: l.firstOrNull()
            best?.s("file_path")?.takeIf { p -> p.isNotEmpty() }?.let { p -> logo = IMG + "w500" + p.replace(".svg", ".png") }
        }
        var bd = ""
        o.optJSONArray("backdrops")?.let { a ->
            val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            val best = l.firstOrNull { x -> x.s("iso_639_1").isEmpty() || x.s("iso_639_1") == "null" } ?: l.firstOrNull()
            best?.s("file_path")?.takeIf { p -> p.isNotEmpty() }?.let { p -> bd = IMG + "w1280" + p }
        }
        saveArt(it, logo, bd)
        return ArtInfo(logo, bd)
    }

    private fun saveArt(it: Item, logo: String, bd: String) {
        artCache.put(it.key, JSONObject().put("l", logo).put("b", bd))
        // guarda só os últimos ~400 títulos
        if (artCache.length() > 450) { val ks = artCache.keys().asSequence().toList(); ks.take(ks.size - 400).forEach { k -> artCache.remove(k) } }
        Store.put("tmdbart", artCache.toString())
    }

    /* ---------- coleções e "porque você assistiu" ---------- */
    fun isStale(): Boolean {
        val raw = try { Store.str("disc")?.let { JSONObject(it) } } catch (e: Exception) { null }
        if (raw == null || System.currentTimeMillis() - raw.optLong("t") > COLL_MAX_AGE) return true
        // guardado vazio (busca anterior falhou): tenta de novo
        val a = raw.optJSONArray("colls") ?: return true
        var total = 0
        for (i in 0 until a.length()) total += a.optJSONObject(i)?.optJSONArray("items")?.length() ?: 0
        return total == 0
    }

    /** monta a partir do que está guardado (rápido) */
    fun loadCached() {
        val raw = try { Store.str("disc")?.let { JSONObject(it) } } catch (e: Exception) { null } ?: return
        if (builtFor == Repo.version + raw.optLong("t").toInt()) return
        val out = ArrayList<Coll>()
        val a = raw.optJSONArray("colls") ?: JSONArray()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            val kind = if (c.s("kind") == "tv") Kind.SERIES else Kind.MOVIE
            val items = matchList(kind, c.optJSONArray("items"))
            if (items.size >= 3) out.add(Coll(c.s("title"), kind, items.take(30)))
        }
        colls = out
        builtFor = Repo.version + raw.optLong("t").toInt()
        loadBecauseCached()
    }

    /** busca no TMDB de novo (coleções a cada 24 horas) */
    fun refresh(force: Boolean = false) {
        if (force || isStale()) {
            val arr = JSONArray()
            DEFS.forEach { d ->
                val items = JSONArray()
                for (page in 1..3) {
                    try {
                        val r = Trend.tm("discover/" + d.kind, d.params + mapOf("language" to "pt-BR", "page" to page.toString(), "include_adult" to "false"))
                        val res = r.optJSONArray("results") ?: continue
                        for (i in 0 until res.length()) {
                            val x = res.optJSONObject(i) ?: continue
                            items.put(JSONObject().put("id", x.s("id")).put("title", x.s("title")).put("name", x.s("name"))
                                .put("original_title", x.s("original_title")).put("original_name", x.s("original_name"))
                                .put("release_date", x.s("release_date")).put("first_air_date", x.s("first_air_date"))
                                .put("backdrop_path", x.s("backdrop_path")))
                        }
                    } catch (e: Exception) {}
                }
                arr.put(JSONObject().put("title", d.title).put("kind", d.kind).put("items", items))
            }
            // só marca como atualizado se veio algo (se falhar, tenta de novo na próxima abertura)
            var total = 0
            for (i in 0 until arr.length()) total += arr.optJSONObject(i)?.optJSONArray("items")?.length() ?: 0
            if (total > 0) Store.put("disc", JSONObject().put("t", System.currentTimeMillis()).put("colls", arr).toString())
            builtFor = -1
        }
        refreshBecause()
        loadCached()
    }

    /** último filme assistido (ou começado) */
    private fun lastWatched(): Item? {
        val seen = Lib.seenKeys('m').firstOrNull()?.let { Repo.findKey(it) }
        val prog = Lib.progs().firstOrNull { it.kind == "movie" }?.let { Repo.findKey(it.key) }
        return prog ?: seen
    }

    private fun loadBecauseCached() {
        val raw = try { Store.str("because")?.let { JSONObject(it) } } catch (e: Exception) { null } ?: run { because = null; return }
        val src = Repo.findKey(raw.s("key")) ?: run { because = null; return }
        val items = matchList(Kind.MOVIE, raw.optJSONArray("items")).filter { it.id != src.id && !Lib.isSeen(it.key) }
        because = if (items.size >= 3) src to items.take(20) else null
    }

    private fun refreshBecause() {
        val src = lastWatched() ?: return
        val old = try { Store.str("because")?.let { JSONObject(it) } } catch (e: Exception) { null }
        if (old != null && old.s("key") == src.key && System.currentTimeMillis() - old.optLong("t") < COLL_MAX_AGE) return
        val id = tmdbId(src) ?: return
        val items = JSONArray()
        for (page in 1..2) {
            try {
                val r = Trend.tm("movie/$id/recommendations", mapOf("language" to "pt-BR", "page" to page.toString()))
                val res = r.optJSONArray("results") ?: continue
                for (i in 0 until res.length()) res.optJSONObject(i)?.let { items.put(it) }
            } catch (e: Exception) {}
        }
        Store.put("because", JSONObject().put("key", src.key).put("t", System.currentTimeMillis()).put("items", items).toString())
    }

    /** sorteio: filme bem avaliado, com capa, que ainda não foi visto */
    fun randomPick(): Item? {
        val pool = Repo.vod.filter { it.icon.isNotEmpty() && !Lib.isSeen(it.key) && (it.rating >= 7.0 || (Trend.votes[it.key] ?: 0.0) >= 7.0) }
        val fallback = Repo.vod.filter { it.icon.isNotEmpty() && !Lib.isSeen(it.key) }
        val l = pool.ifEmpty { fallback }
        return if (l.isEmpty()) null else l[(Math.random() * l.size).toInt().coerceAtMost(l.size - 1)]
    }
}
