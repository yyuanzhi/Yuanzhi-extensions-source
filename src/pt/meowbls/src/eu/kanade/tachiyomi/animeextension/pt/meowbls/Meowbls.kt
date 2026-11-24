package eu.kanade.tachiyomi.animeextension.pt.meowbls

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Suppress("unused", "DEPRECATION")
class Meowbls : ParsedAnimeHttpSource() {

    override val name = "Meow Fansub"
    override val baseUrl = "https://meowbls.com"
    override val lang = "pt-BR"
    override val supportsLatest = true
    override val supportsRelatedAnime = false

    private val itemSelector = "#archive-content article.item, div.items article.item"

    private fun wpPage(path: String, page: Int): String =
        if (page <= 1) "$baseUrl$path" else "$baseUrl${path.trimEnd('/')}/page/$page/"

    private fun Response.toDoc(): Document =
        Jsoup.parse(this.body!!.string(), this.request.url.toString())

    private fun toAnime(element: Element): SAnime? {
        val link = element.selectFirst("div.data h3 a") ?: return null
        val href = link.absUrl("href")
        val valid = href.contains("/series/") || href.contains("/filmes/") || href.contains("/episodios/")
        if (!valid) return null
        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            title = link.text().ifBlank { element.selectFirst("img")?.attr("alt").orEmpty() }
            thumbnail_url = imageFromElement(element)
        }
    }

    private fun imageFromElement(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }
        return src.ifBlank { null }
    }

    private fun parsePage(doc: Document): AnimesPage {
        val items = doc.select(itemSelector).mapNotNull { toAnime(it) }.distinctBy { it.url }
        val hasNext = doc.selectFirst("div.pagination a.arrow_pag:has(i#nextpagination)") != null
        return AnimesPage(items, hasNext)
    }

    // populares
    override fun popularAnimeRequest(page: Int): Request = GET(wpPage("/series/", page), headers)
    override fun popularAnimeParse(response: Response): AnimesPage = parsePage(response.toDoc())
    override fun popularAnimeSelector(): String = itemSelector
    override fun popularAnimeFromElement(element: Element): SAnime = toAnime(element)!!
    override fun popularAnimeNextPageSelector(): String? = null

    // recentes
    override fun latestUpdatesRequest(page: Int): Request = GET(wpPage("/episodios/", page), headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = parsePage(response.toDoc())
    override fun latestUpdatesSelector(): String = itemSelector
    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = toAnime(element)!!
        anime.title = "${element.selectFirst("span.serie")?.text().orEmpty()} - " +
            element.selectFirst("div.data h3 a")?.text().orEmpty()
        return anime
    }
    override fun latestUpdatesNextPageSelector(): String? = null

    // busca
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET(if (page <= 1) "$baseUrl/?s=$q" else "$baseUrl/page/$page/?s=$q", headers)
    }
    override fun searchAnimeParse(response: Response): AnimesPage = parsePage(response.toDoc())
    override fun searchAnimeSelector(): String = itemSelector
    override fun searchAnimeFromElement(element: Element): SAnime = toAnime(element)!!
    override fun searchAnimeNextPageSelector(): String? = null

    // detalhes
    override fun animeDetailsParse(response: Response): SAnime = animeDetailsParse(response.toDoc())

    override fun animeDetailsParse(document: Document): SAnime {
        var doc = document
        if (doc.location().contains("/episodios/")) {
            val serie = doc.selectFirst(".pag_episodes a[href*='/series/']")?.absUrl("href")
                ?: doc.selectFirst("div.dt-breadcrumb a[href*='/series/']")?.absUrl("href")
            if (serie != null) {
                doc = client.newCall(GET(serie, headers)).execute().toDoc()
            }
        }
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            title = doc.selectFirst("div.sheader div.data h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst("div.sheader div.poster img")?.absUrl("src")
            description = doc.select("div.wp-content p").joinToString("\n\n") { it.text() }.trim()
            genre = doc.select("div.sgeneros a").joinToString(", ") { it.text() }
        }
    }

    // temporadas (site de temporada única: lista plana)
    override fun seasonListParse(response: Response): List<SAnime> = emptyList()
    override fun seasonListSelector(): String = "div#seasons div.se-c"
    override fun seasonFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.selectFirst("span.title")?.ownText()?.trim().orEmpty().ifBlank { "Temporada" }
    }

    // episódios
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)
    override fun episodeListSelector(): String = "div#seasons ul.episodios li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.toDoc()
        val items = doc.select(episodeListSelector())

        if (items.isEmpty()) {
            if (doc.location().contains("/filmes/")) {
                return listOf(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(doc.location())
                        name = "Filme"
                        episode_number = 1f
                    },
                )
            }
            return emptyList()
        }

        return items.mapNotNull { li ->
            val a = li.selectFirst("div.episodiotitle a") ?: return@mapNotNull null
            SEpisode.create().apply {
                setUrlWithoutDomain(a.absUrl("href"))
                name = a.text()
                episode_number = li.selectFirst("div.numerando")?.text()
                    ?.split("-")?.lastOrNull()?.trim()?.toFloatOrNull() ?: 0f
                date_upload = li.selectFirst("span.date")?.text()?.let(::parseDate) ?: 0L
            }
        }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val a = element.selectFirst("div.episodiotitle a")!!
        setUrlWithoutDomain(a.absUrl("href"))
        name = a.text()
    }

    private fun parseDate(raw: String): Long = runCatching {
        SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH).parse(raw)?.time ?: 0L
    }.getOrDefault(0L)

    // hosters e vídeos
    override fun hosterListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun hosterListParse(response: Response): List<Hoster> {
        val doc = response.toDoc()
        return doc.select("#playeroptionsul li").mapNotNull { li ->
            val post = li.attr("data-post").ifBlank { return@mapNotNull null }
            val nume = li.attr("data-nume").ifBlank { "1" }
            val type = li.attr("data-type").ifBlank { "tv" }
            val hosterName = li.selectFirst("span.title")?.text()?.ifBlank { null } ?: "Player"
            Hoster(
                hosterUrl = "$baseUrl/wp-json/dooplayer/v2/$post/$type/$nume",
                hosterName = hosterName,
            )
        }
    }

    override fun videoListRequest(hoster: Hoster): Request = GET(hoster.hosterUrl, headers)

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        val json = response.body!!.string()
        val embed = EMBED_REGEX.find(json)?.groupValues?.get(1) ?: return emptyList()
        val embedHeaders = headers.newBuilder().add("Referer", "$baseUrl/").build()
        val embedHtml = client.newCall(GET(embed, embedHeaders)).execute().body!!.string()
        return M3U8_REGEX.findAll(embedHtml)
            .map { buildVideo(it.groupValues[1], embed) }
            .distinctBy { it.videoUrl }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun buildVideo(url: String, referer: String? = null): Video {
        val h = headers.newBuilder()
            .add("Referer", referer ?: "$baseUrl/")
            .add("Origin", "https://player.jmvstream.com")
            .build()
        return Video(url = url, quality = "JMV HD").copy(videoUrl = url, headers = h)
    }

    companion object {
        private val M3U8_REGEX = Regex(""""(https://[^"]+\.m3u8[^"]*)"""")
        private val EMBED_REGEX = Regex(""""embed_url":"([^"]+)"""")
    }
                                        }
