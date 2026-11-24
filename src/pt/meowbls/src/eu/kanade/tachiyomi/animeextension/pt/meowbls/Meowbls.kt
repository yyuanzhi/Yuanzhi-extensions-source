package eu.kanade.tachiyomi.animeextension.pt.meowbls

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class Meowbls : ParsedAnimeHttpSource() {

    override val name = "Meow Fansub"
    override val baseUrl = "https://meowbls.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val itemSelector = "#archive-content article.item, div.items article.item"

    private fun wpPage(path: String, page: Int): String =
        if (page <= 1) "$baseUrl$path" else "$baseUrl${path.trimEnd('/')}/page/$page/"

    override fun popularAnimeRequest(page: Int) = GET(wpPage("/series/", page), headers)
    override fun popularAnimeSelector() = itemSelector
    override fun popularAnimeNextPageSelector() = "div.pagination a.arrow_pag:has(i#nextpagination)"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(
            element.selectFirst("a[href*='/series/'], a[href*='/filmes/']")!!.absUrl("href"),
        )
        title = element.selectFirst("div.data h3 a")?.text() ?: element.selectFirst("img")!!.attr("alt")
        thumbnail_url = imageFromElement(element)
    }

    override fun latestUpdatesRequest(page: Int) = GET(wpPage("/episodios/", page), headers)
    override fun latestUpdatesSelector() = itemSelector
    override fun latestUpdatesNextPageSelector() = "div.pagination a.arrow_pag:has(i#nextpagination)"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("div.data h3 a")!!.absUrl("href"))
        title = "${element.selectFirst("span.serie")?.text() ?: ""} - ${element.selectFirst("div.data h3 a")?.text() ?: ""}"
        thumbnail_url = imageFromElement(element)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET(if (page <= 1) "$baseUrl/?s=$q" else "$baseUrl/page/$page/?s=$q", headers)
    }

    override fun searchAnimeSelector() = itemSelector
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    private fun imageFromElement(element: Element): String? =
        element.selectFirst("img")?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }?.ifBlank { null }

    override fun animeDetailsSelector() = "div.sheader"

    override fun animeDetailsParse(response: Response): SAnime {
        var doc = response.asJsoup()

        if (!doc.location().contains("/series/") && !doc.location().contains("/filmes/")) {
            val serieUrl = doc.selectFirst(".pag_episodes a[href*='/series/'], .pag_episodes a[href*='/filmes/']")
                ?.absUrl("href")
                ?: doc.selectFirst("div.dt-breadcrumb a[href*='/series/'], div.dt-breadcrumb a[href*='/filmes/']")
                    ?.absUrl("href")
            if (serieUrl != null) {
                doc = client.newCall(GET(serieUrl, headers)).execute().asJsoup()
            }
        }

        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            title = doc.selectFirst("div.sheader div.data h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" – ") ?: ""
            thumbnail_url = doc.selectFirst("div.sheader div.poster img")?.absUrl("src")
            description = doc.select("div.wp-content p").joinToString("\n\n") { it.text() }.trim()
            genre = doc.select("div.sgeneros a").joinToString(", ") { it.text() }
            doc.selectFirst("span.dt_rating_vgs")?.text()?.toFloatOrNull()?.let { score = it }
        }
    }

    override fun episodeListSelector() = "div#seasons ul.episodios li"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val a = element.selectFirst("div.episodiotitle a")!!
        setUrlWithoutDomain(a.absUrl("href"))
        name = a.text()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        var doc = response.asJsoup()

        if (doc.select("div#seasons ul.episodios li").isEmpty()) {
            if (doc.location().contains("/filmes/")) {
                return listOf(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(doc.location())
                        name = "Filme"
                        episode_number = 1f
                    },
                )
            }
            val serieUrl = doc.selectFirst(".pag_episodes a[href*='/series/']")?.absUrl("href") ?: return emptyList()
            doc = client.newCall(GET(serieUrl, headers)).execute().asJsoup()
        }

        return doc.select("div#seasons ul.episodios li").mapNotNull { li ->
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

    private fun parseDate(raw: String): Long = runCatching {
        SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH).parse(raw)?.time ?: 0L
    }.getOrDefault(0L)

    override fun videoListSelector() = ".dooplay_player_option"

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val html = doc.outerHtml()
        val videos = mutableListOf<Video>()

        M3U8_REGEX.findAll(html).forEach { videos.add(buildVideo(it.groupValues[1])) }

        val postId = doc.selectFirst("[data-post]")?.attr("data-post")
            ?: POST_ID_REGEX.find(html)?.groupValues?.get(1)

        if (postId != null) {
            runCatching {
                val api = "$baseUrl/wp-json/dooplayer/v2/$postId/tv/1"
                val json = client.newCall(GET(api, headers)).execute().body!!.string()
                val embed = EMBED_REGEX.find(json)?.groupValues?.get(1) ?: return@runCatching
                val embedHeaders = headers.newBuilder().add("Referer", "$baseUrl/").build()
                val embedHtml = client.newCall(GET(embed, embedHeaders)).execute().body!!.string()
                M3U8_REGEX.findAll(embedHtml).forEach { videos.add(buildVideo(it.groupValues[1], embed)) }
            }
        }

        return videos.distinctBy { it.videoUrl }
    }

    private fun buildVideo(url: String, referer: String? = null): Video {
        val h = headers.newBuilder()
            .add("Referer", referer ?: "$baseUrl/")
            .add("Origin", "https://player.jmvstream.com")
            .build()
        return Video("JMV HD", url, h)
    }

    override fun videoFromElement(element: Element) = Video("", "", headers)

    companion object {
        private val M3U8_REGEX = Regex(""""(https://[^"]+\.m3u8[^"]*)"""")
        private val POST_ID_REGEX = Regex("""data-post="(\d+)"""")
        private val EMBED_REGEX = Regex(""""embed_url":"([^"]+)"""")
    }
}
