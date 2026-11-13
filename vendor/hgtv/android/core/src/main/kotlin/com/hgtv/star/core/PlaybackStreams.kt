// Added for the local TV adaptation, 2026-11-07. GPL-3.0-only.
package com.hgtv.star.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI

class NoCompatibleStreamException(message: String): IOException(message)
class ProtectedWebPlaybackException: IOException("未获取到本集的播放地址")

/** Parse declared stream records only; never search recommendations for video URLs. */
object PlaybackStreams {
    private val unsupported=setOf("bytevc2","bvc2","h266","vvc")
    private val addressFields=listOf("main_url","backup_url","backup_url_1","backup_url_2","backup_urls","url_list")
    private data class Choice(val stream: StreamInfo,val quality: Int,val avc: Boolean)
    private fun objectOf(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    private fun url(value: String): String? {
        val cleaned=cleanUrl(value).trim()
        if(cleaned.length !in 8..16384)return null
        return runCatching {
            val parsed=URI(cleaned)
            cleaned.takeIf { parsed.scheme?.lowercase() in setOf("http","https")&&!parsed.host.isNullOrBlank()&&parsed.userInfo==null }
        }.getOrNull()
    }
    private fun address(value: String,seed: String): String? {
        url(value)?.let { return it }
        runCatching { String(decode64(value),Charsets.UTF_8) }.getOrNull()?.let { url(it)?.let { direct -> return direct } }
        if(seed.isNotBlank())runCatching { MediaCrypto.decryptUrl(value,decode64(seed)) }.getOrNull()?.let { url(it)?.let { decoded -> return decoded } }
        return null
    }
    private fun addresses(row: JSONObject): List<String> {
        val result=mutableListOf<String>()
        fun collect(value: Any?) {
            when(value) {
                is String -> if(value.isNotBlank())result+=value
                is JSONArray -> for(i in 0 until value.length())collect(value.opt(i))
            }
        }
        addressFields.forEach { collect(row.opt(it)) }
        return result.distinct()
    }
    private fun quality(row: JSONObject,meta: JSONObject,key: String): Int {
        for(value in listOf(row.str("quality_desc"),row.str("definition"),meta.str("definition"),
            row.str("gear_des_key").split('|').firstOrNull { it.startsWith("4:") }?.substringAfter(':').orEmpty())) {
            val n=ContentRepository.qualityNumber(value)
            if(n>0)return n
        }
        val width=meta.optInt("vwidth",row.optInt("vwidth",row.optInt("width")))
        val height=meta.optInt("vheight",row.optInt("vheight",row.optInt("height")))
        if(width>0&&height>0)return minOf(width,height)
        for(value in listOf(row.str("height"),row.str("vheight"),row.str("quality"),key)) {
            val n=ContentRepository.qualityNumber(value)
            if(n>0)return n
        }
        return 0
    }
    /** Resource identity stays internal; signed URLs and media keys must never enter diagnostics. */
    fun fingerprint(stream: StreamInfo): String {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        digest.update(stream.url.toByteArray(Charsets.UTF_8));digest.update(0.toByte())
        stream.key?.let { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @JvmOverloads
    fun select(info: JSONObject,maxQuality: Int=1080,rejected: Set<String> = emptySet()): StreamInfo {
        val entries=mutableListOf<Pair<String,JSONObject>>()
        val list=info.opt("video_list")
        when(list) {
            is JSONObject -> list.keys().asSequence().sorted().forEach { key -> objectOf(list.opt(key))?.let { entries+=key to it } }
            is JSONArray -> for(i in 0 until list.length())objectOf(list.opt(i))?.let { entries+=i.toString() to it }
            else -> if(addresses(info).isNotEmpty())entries+="" to info
        }
        val codecs=linkedSetOf<String>()
        val choices=mutableListOf<Choice>()
        var badAddresses=0
        var badKeys=0
        for((key,row) in entries) {
            val meta=objectOf(row.opt("video_meta")) ?: JSONObject()
            val gear=row.str("gear_des_key").split('|').firstOrNull { it.startsWith("2:") }?.substringAfter(':').orEmpty()
            val codec=row.str("codec_type").ifEmpty { meta.str("codec_type") }.ifEmpty { gear }.lowercase()
            codecs+=codec.ifEmpty { "unknown" }.replace(Regex("[^a-z0-9._-]"),"").take(24)
            if(codec in unsupported)continue
            val raw=addresses(row)
            if(raw.isEmpty()){badAddresses++;continue}
            val seed=row.str("key_seed").ifEmpty { info.str("key_seed") }
            val resolved=raw.firstNotNullOfOrNull { address(it,seed) }
            if(resolved==null){badAddresses++;continue}
            val encryption=objectOf(row.opt("encrypt_info")) ?: JSONObject()
            val spade=row.str("spade_a").ifEmpty { encryption.str("spade_a") }
            val method=row.str("encryption_method").ifEmpty { encryption.str("encryption_method") }
            val encrypted=spade.isNotEmpty()||encryption.opt("encrypt") in listOf(true,1,"1","true")||method=="cenc-aes-ctr"
            val contentKey=if(encrypted)runCatching { MediaCrypto.deriveKey(spade) }.getOrNull() else null
            if(encrypted&&contentKey==null){badKeys++;continue}
            val q=quality(row,meta,key)
            val label=if(q>0)q.toString()+"P" else "自动"
            choices+=Choice(StreamInfo(resolved,contentKey,label),q,codec in setOf("h264","avc","avc1"))
        }
        val sorted=choices.sortedWith(compareByDescending<Choice> { it.quality }.thenByDescending { it.avc })
        sorted.firstOrNull { it.quality<=maxQuality && fingerprint(it.stream) !in rejected }?.let { return it.stream }
        val details=mutableListOf("${entries.size}档","编码：${codecs.joinToString("/").ifEmpty { "无" }}")
        if(badAddresses>0)details+="无效地址$badAddresses"
        if(badKeys>0)details+="密钥异常$badKeys"
        throw NoCompatibleStreamException("接口未返回兼容视频（${details.joinToString("；")}）")
    }
    internal fun webModel(html: String,seriesID: String,videoID: String): JSONObject {
        val loader=ContentRepository.extractRouter(html).optJSONObject("loaderData")
            ?: throw IOException("网页播放数据缺失")
        val pages=loader.keys().asSequence().filter { it.startsWith("player_") }.mapNotNull { objectOf(loader.opt(it)) }.toList()
        val page=pages.firstOrNull {
            it.str("series_id").ifEmpty { it.optJSONObject("seriesDetail")?.str("series_id").orEmpty() }==seriesID&&it.str("vid").ifEmpty { it.str("video_id") }==videoID
        } ?: throw IOException("网页未返回所选分集，无法确认播放地址")
        return objectOf(page.opt("video_player_info")) ?: publicWebModel(html,videoID) ?: if (page.optBoolean("protectedPlayback")) {
            throw ProtectedWebPlaybackException()
        } else throw IOException("网页未提供本集播放信息")
    }

    /** Use the media URL explicitly published by the official page for this episode. */
    private fun publicWebModel(html: String,videoID: String): JSONObject? {
        if(!videoID.matches(Regex("[0-9]{1,30}")))return null
        val path=Regex("/video/public/$videoID/([1-9][0-9]{1,3})p\\.mp4")
        val rows=JSONArray()
        for(script in org.jsoup.Jsoup.parse(html).select("script[type=application/ld+json]")) {
            val data=objectOf(script.data()) ?: continue
            if(data.str("@type")!="VideoObject")continue
            val address=data.str("contentUrl")
            val uri=runCatching { URI(address) }.getOrNull() ?: continue
            if(uri.scheme!="https"||uri.rawAuthority!="hongguoduanju.com"||uri.rawQuery!=null||uri.rawFragment!=null)continue
            val quality=path.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1) ?: continue
            rows.put(JSONObject().put("main_url",address).put("quality_desc",quality+"P"))
        }
        return if(rows.length()>0)JSONObject().put("video_list",rows) else null
    }
    @JvmOverloads
    fun web(html: String,seriesID: String,videoID: String,maxQuality: Int=1080,rejected: Set<String> = emptySet()): StreamInfo =
        select(webModel(html,seriesID,videoID),maxQuality,rejected)

    /** The official creator share page sends its actual data in a deferred SSR script attribute. */
    internal fun creatorWeb(html: String, maxQuality: Int, rejected: Set<String>): StreamInfo {
        val pageKey="video-pugc-share_page"
        val routerPage=runCatching {
            ContentRepository.extractRouter(html).optJSONObject("loaderData")
                ?.optJSONObject(pageKey)?.optJSONObject("pageData")?.optJSONObject("series_data")
        }.getOrNull()
        val video=routerPage ?: org.jsoup.Jsoup.parse(html).select("script[data-fn-args]")
            .asSequence().mapNotNull { script ->
                val args=runCatching { JSONArray(script.attr("data-fn-args")) }.getOrNull()
                if(args?.optString(0)==pageKey && args.optString(1)=="pageData")
                    args.optJSONObject(2)?.optJSONObject("series_data")
                else null
            }.firstOrNull() ?: throw IOException("分享页暂未提供本集播放信息")
        val address=video.str("play_url")
        if(address.isBlank())throw IOException("分享页暂未提供本集播放地址")
        val dimensions=video.optJSONObject("video_meta")
        val stream=JSONObject().put("main_url",address)
            .put("width",dimensions?.optInt("width") ?: 0).put("height",dimensions?.optInt("height") ?: 0)
        return select(stream,maxQuality,rejected)
    }
}

