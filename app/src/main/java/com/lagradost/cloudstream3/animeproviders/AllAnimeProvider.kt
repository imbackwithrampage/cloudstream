package com.lagradost.cloudstream3.animeproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.get
import com.lagradost.cloudstream3.network.text
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import java.util.*

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.collections.ArrayList
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.net.URI
import java.net.URLDecoder
import com.lagradost.cloudstream3.utils.getQualityFromName


class AllAnimeProvider : MainAPI() {
    override val mainUrl: String
        get() = "https://allanime.site"
    override val name: String
        get() = "AllAnime"
    override val hasQuickSearch: Boolean
        get() = false
    override val hasMainPage: Boolean
        get() = false

    private fun getStatus(t: String): ShowStatus {
        return when (t) {
            "Finished" -> ShowStatus.Completed
            "Releasing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val supportedTypes: Set<TvType>
        get() = setOf(TvType.Anime, TvType.AnimeMovie)

    private data class Data (
        @JsonProperty("shows") val shows: Shows
    )

    private data class Shows (
        @JsonProperty("pageInfo") val pageInfo: PageInfo,
        @JsonProperty("edges") val edges: List<Edges>,
        @JsonProperty("__typename") val _typename: String
    )

    private data class Edges (
        @JsonProperty("_id") val Id: String?,
        @JsonProperty("name") val name: String,
        @JsonProperty("englishName") val englishName: String?,
        @JsonProperty("nativeName") val nativeName: String?,
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("season") val season: Season?,
        @JsonProperty("score") val score: Double?,
        @JsonProperty("airedStart") val airedStart: AiredStart?,
        @JsonProperty("availableEpisodes") val availableEpisodes: AvailableEpisodes?,
        @JsonProperty("availableEpisodesDetail") val availableEpisodesDetail: AvailableEpisodesDetail?,
        @JsonProperty("studios") val studios: List<String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("status") val status: String?,
    )

    private data class AvailableEpisodes (
        @JsonProperty("sub") val sub: Int,
        @JsonProperty("dub") val dub: Int,
        @JsonProperty("raw") val raw: Int
    )

    private data class AiredStart (
        @JsonProperty("year") val year: Int,
        @JsonProperty("month") val month: Int,
        @JsonProperty("date") val date: Int
    )

    private data class Season (
        @JsonProperty("quarter") val quarter: String,
        @JsonProperty("year") val year: Int
    )

    private data class PageInfo (
        @JsonProperty("total") val total: Int,
        @JsonProperty("__typename") val _typename: String
    )

    private data class AllAnimeQuery (
        @JsonProperty("data") val data: Data
    )

    override fun search(query: String): ArrayList<SearchResponse> {
        val link = """$mainUrl/graphql?variables=%7B%22search%22%3A%7B%22allowAdult%22%3Afalse%2C%22query%22%3A%22$query%22%7D%2C%22limit%22%3A26%2C%22page%22%3A1%2C%22translationType%22%3A%22sub%22%7D&extensions=%7B%22persistedQuery%22%3A%7B%22version%22%3A1%2C%22sha256Hash%22%3A%229343797cc3d9e3f444e2d3b7db9a84d759b816a4d84512ea72d079f85bb96e98%22%7D%7D"""
        var res = get(link).text
        if (res.contains("PERSISTED_QUERY_NOT_FOUND")) {
            res = get(link).text
            if (res.contains("PERSISTED_QUERY_NOT_FOUND")) return ArrayList()
        }
        val response = mapper.readValue<AllAnimeQuery>(res)

        val results = response.data.shows.edges.filter {
            // filtering in case there is an anime with 0 episodes available on the site.
            !(it.availableEpisodes?.raw == 0 && it.availableEpisodes.sub == 0 && it.availableEpisodes.dub == 0)
        }

        return ArrayList(results.map {
            AnimeSearchResponse(
                it.name,
                "$mainUrl/anime/${it.Id}",
                this.name,
                TvType.Anime,
                it.thumbnail,
                it.airedStart?.year,
                EnumSet.of(DubStatus.Subbed),  //, DubStatus.Dubbed),
                it.englishName,
                null, //it.availableEpisodes?.dub,
                it.availableEpisodes?.sub
            )
        })
    }

    private data class AvailableEpisodesDetail (
        @JsonProperty("sub") val sub: List<String>,
        @JsonProperty("dub") val dub: List<String>,
        @JsonProperty("raw") val raw: List<String>
    )


    override fun load(url: String): LoadResponse? {
        val rhino = Context.enter()
        rhino.initStandardObjects()
        rhino.optimizationLevel = -1
        val scope: Scriptable = rhino.initStandardObjects()


        val html = get(url).text
        val soup = Jsoup.parse(html)

        val script = soup.select("script").firstOrNull {
            it.html().contains("window.__NUXT__")
        } ?: return null

        val js = """
            const window = {}
            ${script.html()}
            const returnValue = JSON.stringify(window.__NUXT__.fetch[0].show)
        """.trimIndent()

        rhino.evaluateString(scope, js, "JavaScript", 1, null)
        val jsEval = scope.get("returnValue", scope) ?: return null
        val showData = mapper.readValue<Edges>(jsEval as String)

        val title = showData.name
        val description = showData.description
        val poster = showData.thumbnail

        val episodes = showData.availableEpisodes.let {
            if (it == null) return@let Pair(null, null)
            Pair(if (it.sub != 0) ArrayList((1 .. it.sub).map { epNum ->
                AnimeEpisode(
                    "$mainUrl/anime/${showData.Id}/episodes/sub/$epNum",
                    null,
                    null,
                    null,
                    null,
                    null,
                    epNum
                )
            }) else null, if (it.dub != 0) ArrayList((1 .. it.dub).map { epNum ->
                AnimeEpisode(
                    "$mainUrl/anime/${showData.Id}/episodes/dub/$epNum",
                    null,
                    null,
                    null,
                    null,
                    null,
                    epNum
                )
            }) else null)
        }

        return AnimeLoadResponse(
            null,
            null,
            title,
            url,
            this.name,
            TvType.Anime,
            poster,
            showData.airedStart?.year,
            null, // no dub, because there is no way to switch from dub to sub //episodes.second,
            episodes.first,
            getStatus(showData.status.toString()),
            description.replace(Regex("""\<(.*?)\>"""), "")
        )
    }

    private val embedBlackList = listOf(
        "https://mp4upload.com/",
        "https://streamsb.net/",
        "https://dood.to/",
        "https://videobin.co/",
        "https://ok.ru",
        "https://streamlare.com",
    )

    private fun embedIsBlacklisted(url: String): Boolean {
        embedBlackList.forEach {
            if (it.javaClass.name == "kotlin.text.Regex") {
                if ((it as Regex).matches(url)) {
                    return true
                }
            } else {
                if (url.contains(it as String)) {
                    return true
                }
            }
        }
        return false
    }

    private fun String.sanitize(): String {
        var out = this
        listOf(Pair("\\u002F", "/")).forEach {
            out = out.replace(it.first, it.second)
        }
        return out
    }

    private data class Links (
        @JsonProperty("link") val link: String,
        @JsonProperty("hls") val hls: Boolean?,
        @JsonProperty("resolutionStr") val resolutionStr: String,
        @JsonProperty("src") val src: String?
    )

    private data class AllAnimeVideoApiResponse (
        @JsonProperty("links") val links: List<Links>
    )

    private data class ApiEndPoint(
        @JsonProperty("episodeIframeHead") val episodeIframeHead: String
    )

    override fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var apiEndPoint = mapper.readValue<ApiEndPoint>(get("$mainUrl/getVersion").text).episodeIframeHead
        if (apiEndPoint.endsWith("/")) apiEndPoint = apiEndPoint.slice(0 until apiEndPoint.length - 1)

        val html = get(data).text

        val sources = Regex("""sourceUrl[:=]"(.+?)"""").findAll(html).toList().map { URLDecoder.decode(it.destructured.component1().sanitize(), "UTF-8") }
        sources.forEach {
            var link = it
            if (URI(link).isAbsolute || link.startsWith("//")) {
                if (link.startsWith("//")) link = "https:$it"

                if (Regex("""streaming\.php\?""").matches(link)) {
                    // for now ignore
                } else if (!embedIsBlacklisted(link)) {
                    callback(
                        ExtractorLink(
                            "AllAnime - " + URI(link).host,
                            "",
                            link,
                            data,
                            getQualityFromName("1080"),
                            URI(link).path.contains(".m3u")
                        )
                    )
                }
            } else {
                link = apiEndPoint + URI(link).path + ".json?" + URI(link).query
                val response = get(link)

                if (response.code < 400) {
                    val links = mapper.readValue<AllAnimeVideoApiResponse>(response.text).links
                    links.forEach { server ->
                        callback(ExtractorLink(
                            "AllAnime - " + URI(server.link).host,
                            server.resolutionStr,
                            server.link,
                            "$apiEndPoint/player?uri=" + (if (URI(server.link).host.isNotEmpty()) server.link else apiEndPoint + URI(server.link).path),
                            getQualityFromName("1080"),
                            server.hls != null && server.hls
                        ))
                    }
                }
            }
        }
        return true
    }

}
