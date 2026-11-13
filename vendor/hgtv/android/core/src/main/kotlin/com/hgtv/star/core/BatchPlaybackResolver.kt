// GPL-3.0-only. Independent bounded resolver; request fields/signing adapted from
// tsukiyo-zero/hongguoTV ContentRepository.kt (5338c421a4d15dfbd1489f99ee92f59a70c05d91).
package com.hgtv.star.core

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Batch addresses and actual media buffering have separate lifetimes. No media bytes are fetched here. */
class BatchPlaybackResolver(private val repository: ContentRepository,private val clock: ()->Long=System::currentTimeMillis) {
    private class HttpFailure(val status: Int, source: String): IOException(
        if(status==429) "请求频率受限，请稍后重试" else "${source}请求失败（HTTP $status）"
    )
    private enum class Route(val label: String, val path: String) {
        BATCH("batch","/novel/player/multi_video_model/v1/"),
        SINGLE("single","/novel/player/video_model/v1/"),
        COMPATIBLE("compat","/novel/player/video_model/v1/"),
        TAB("tab","/reading/bookapi/video_tab/multi_video_model/v1/")
    }
    private class OwnerChanged: IOException("播放设备已更新")
    private class SessionChanged: IOException("播放账号已切换")
    private data class Profile(val values: Map<String,String>,val cookie: String,val signer: PlaybackSigner)
    private data class Cached(val model: JSONObject,val owner: String,val until: Long)
    private val models=linkedMapOf<String,Cached>()
    private val profileLock=Any()
    private val registrationLock=java.util.concurrent.locks.ReentrantLock()
    private val revision=java.util.concurrent.atomic.AtomicLong()
    @Volatile private var profile: Profile?=null
    @Volatile private var profileOwner=""
    private var lastRegistration=0L
    private var lastRegistrationOwner=""
    private var lastRegistrationRevision=-1L
    @Volatile var lastTrace="";private set
    private fun owner() = repository.deviceIdentity["device_id"].orEmpty()+":"+repository.registrationCookiesSnapshot().toSortedMap().toString()+":"+repository.credentials?.cookie.orEmpty()
    private fun key(sid: String,id: String)="$sid/$id"
    @Synchronized private fun cached(sid: String,id: String): JSONObject? {
        val current=owner();val now=clock();models.entries.removeAll { it.value.owner!=current || it.value.until<=now }
        return models[key(sid,id)]?.model
    }
    @Synchronized fun invalidate(sid: String,id: String) { models.remove(key(sid,id)) }
    @Synchronized fun clear() { revision.incrementAndGet();models.clear();profile=null;profileOwner="" }
    private fun model(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
            ?: value.takeIf { it.length<=360_000 }?.let {
                runCatching { JSONObject(String(decode64(it),Charsets.UTF_8)) }.getOrNull()
            }
        else -> null
    }
    private fun profile(): Profile = synchronized(profileLock) {
        val owner=owner()
        if(profile!=null&&owner==profileOwner)return@synchronized profile!!
        val values=(VendorConstants.device-setOf("device_id","iid"))+repository.deviceIdentity+mapOf("version_code" to "73932","manifest_version_code" to "73932","update_version_code" to "73932","pv_player" to "73932","version_name" to "7.3.9.32")
        val cookie=repository.credentials?.cookie?.takeIf{it.isNotBlank()} ?: repository.registrationCookiesSnapshot().entries.joinToString("; "){it.key+"="+it.value}
        Profile(values,cookie,PlaybackSigner(clock,values)).also { profile=it;profileOwner=owner }
    }
    private fun transport()=repository.http.newBuilder().connectTimeout(2500,TimeUnit.MILLISECONDS).readTimeout(3500,TimeUnit.MILLISECONDS).callTimeout(4000,TimeUnit.MILLISECONDS).build()
    private fun read(request: Request,trace: MutableList<String>,label: String,limit: Int=4*1024*1024): String {
        RequestScope.ensureActive()
        return try { RequestScope.execute(transport().newCall(request)) { response ->
            if(!response.isSuccessful){
                trace+="$label:H${response.code}"
                val source=when {
                    label.startsWith("web") -> "网页播放页"
                    label.startsWith("fallback") -> "播放备用地址"
                    label.startsWith("detail") -> "App 分集详情"
                    label=="creator" -> "创作者分享页"
                    else -> "播放信息接口"
                }
                throw HttpFailure(response.code,source)
            }
            val body=response.body ?: throw IOException("服务端返回了空内容")
            val source=body.source();if(source.request(limit.toLong()+1))throw IOException("播放信息过大")
            val text=source.readUtf8();RequestScope.ensureActive();trace+="$label:${text.toByteArray().size}B"
            if(text.isBlank())throw IOException("服务端返回了空内容")
            text
        }}catch(error: IOException){if(trace.lastOrNull()?.startsWith("$label:")!=true)trace+="$label:${if(error is java.net.SocketTimeoutException)"timeout" else "io"}";throw error}
    }
    private fun request(base: String,profile: Profile,extra: Map<String,String> = emptyMap(),body: ByteArray?=null): Request {
        val signed=profile.signer.signPlayback(base,profile.values+extra,body,clock())
        val request=Request.Builder().url(signed.url);signed.headers.forEach{(k,v)->request.header(k,v)}
        if(profile.cookie.isNotBlank())request.header("Cookie",profile.cookie)
        if(body!=null)request.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return request.build()
    }
    private fun valid(model: JSONObject): Boolean {
        val list=model.opt("video_list")
        return list is JSONArray&&list.length()>0 || list is JSONObject&&list.length()>0 || model.has("fallback_api")
    }
    @Synchronized private fun offer(sid: String,ids: List<String>,data: JSONObject,owner: String): JSONObject? {
        var current: JSONObject?=null
        val rows=data.optJSONObject("video_model_data") ?: data
        for((index,id) in ids.withIndex()) {
            val entry=rows.optJSONObject(id)
            val parsed=model(entry?.opt("video_model")) ?: entry?.takeIf(::valid)
                ?: (if(ids.size==1) model(rows.opt("video_model")) ?: rows.takeIf(::valid) else null)
                ?: continue
            if(!valid(parsed)||parsed.toString().toByteArray().size>256*1024)continue
            models[key(sid,id)]=Cached(parsed,owner,clock()+expiry(parsed))
            if(index==0)current=parsed
        }
        while(models.size>16)models.remove(models.keys.first())
        return current
    }
    private fun expiry(model: JSONObject): Long {
        // Preserve a batch only while its signed addresses remain valid; refresh two minutes early.
        val text=model.toString();val seconds=Regex("(?:expire|expires|x-expires)(?:=|%3[Dd])([0-9]{10})").findAll(text).mapNotNull{it.groupValues[1].toLongOrNull()}.minOrNull()
        return if(seconds==null)300_000 else (seconds*1000-clock()-120_000).coerceIn(1000,1_200_000)
    }
    private fun modelRequest(host: String, route: Route, ids: List<String>, profile: Profile): Request {
        val base=host+route.path
        if(route==Route.TAB) {
            return request(base,profile,mapOf(
                "video_id" to ids.first(),"video_id_type" to "0","device_level" to "1",
                "need_all_video_definition" to "1","need_mp4_align" to "0","use_os_player" to "0",
                "video_platform" to "1024","source" to "0","from_video_id" to ""
            ))
        }
        val biz=JSONObject().put("need_all_video_definition",true)
        val body=JSONObject().put("biz_param",biz)
        if(route==Route.COMPATIBLE) {
            // The App also exposes a single-episode model with this media profile.
            biz.put("video_id_type",0).put("video_platform",3)
            body.put("video_id",ids.first())
        } else {
            biz.put("detail_page_version",0).put("device_level",if(route==Route.BATCH)1 else 3)
                .put("disable_digg_stat",false).put("need_mp4_align",false).put("use_os_player",false)
                .put("use_server_dns",false).put("video_platform",1024)
            if(route==Route.BATCH)body.put("mixed_video_id_map",JSONObject().put("1004",JSONArray(ids)))
            else body.put("video_id",ids.first())
        }
        return request(base,profile,body=body.toString().toByteArray(Charsets.UTF_8))
    }
    private fun preferFailure(previous: Exception?, next: Exception): Exception {
        if(next is ProtectedWebPlaybackException) return previous ?: next
        if(previous is ProtectedWebPlaybackException) return next
        if(next is NoCompatibleStreamException) return next
        if(previous is NoCompatibleStreamException) return previous
        if(previous is HttpFailure && previous.status==404) return previous
        return next
    }
    private fun fetch(
        sid: String, ids: List<String>, profile: Profile, trace: MutableList<String>, expectedOwner: String,
        quality: Int, rejected: Set<String>, round: Int
    ): StreamInfo {
        val line=listOf("b","a","c")[round]
        val host="https://api5-normal-sinfonline$line.fqnovel.com"
        val routes=if(round==0)listOf(Route.BATCH,Route.SINGLE,Route.COMPATIBLE,Route.TAB)
            else listOf(Route.COMPATIBLE,Route.SINGLE,Route.BATCH,Route.TAB)
        var last: Exception?=null
        for(route in routes)try{
            RequestScope.ensureActive()
            if(owner()!=expectedOwner)throw OwnerChanged()
            val label="${route.label}-$line"
            val response=read(modelRequest(host,route,ids,profile),trace,label)
            val result=model(response) ?: throw IOException("播放信息格式异常")
            val code=result.optInt("code",result.optInt("Code",-1))
            if(code!=0){trace+="$label:code$code";throw IOException("播放接口返回错误码 $code")}
            val data=result.optJSONObject("data") ?: throw IOException("播放信息为空")
            if(owner()!=expectedOwner)throw OwnerChanged()
            val parsed=offer(sid,if(route==Route.BATCH)ids else listOf(ids.first()),data,expectedOwner)
                ?: throw IOException("播放模型未匹配当前分集")
            try {
                return select(parsed,quality,trace,rejected)
            } catch(error: Exception) {
                invalidate(sid,ids.first())
                throw error
            }
        }catch(error: Exception){
            if(error is OwnerChanged || error is HttpFailure&&error.status==429)throw error
            RequestScope.ensureActive()
            last=preferFailure(last,error)
        }
        throw last ?: IOException("播放信息获取失败")
    }
    private fun freshProfile(trace: MutableList<String>,expectedOwner: String,failed: Profile): Profile? {
        // Joining a registration must remain cancellable when the viewer changes episodes.
        try {
            while(!registrationLock.tryLock(100,TimeUnit.MILLISECONDS))RequestScope.ensureActive()
        } catch(error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("请求已取消")
        }
        try {
        RequestScope.ensureActive()
        if(owner()!=expectedOwner)throw OwnerChanged()
        val expectedRevision=revision.get()
        synchronized(profileLock){if(profileOwner==expectedOwner && profile!=null && profile!==failed)return profile}
        if(lastRegistrationOwner==expectedOwner && lastRegistrationRevision==expectedRevision && clock()-lastRegistration<30_000)return null
        val values=VendorConstants.device+mapOf("cdid" to java.util.UUID.randomUUID().toString())
        val body=DeviceRegister.payload(values);val request=Request.Builder().url(DeviceRegister.url()).post(body.toRequestBody("application/octet-stream".toMediaType()))
        DeviceRegister.headers(body,values).forEach{(k,v)->request.header(k,v)}
        val registered=RequestScope.execute(transport().newCall(request.build())) { response ->
            if(!response.isSuccessful){trace+="registration:H${response.code}";throw HttpFailure(response.code,"播放设备注册")}
            val content=response.body ?: return@execute null;val source=content.source();if(source.request(2*1024*1024L+1))return@execute null
            DeviceRegister.parse(source.readUtf8(),response.headers("Set-Cookie"))
        } ?: return null
        val identity=values+mapOf("device_id" to registered.deviceId,"iid" to registered.installId,"version_code" to "73932","manifest_version_code" to "73932","update_version_code" to "73932","pv_player" to "73932","version_name" to "7.3.9.32")
        trace+="registration:ok"
        if(revision.get()!=expectedRevision)throw SessionChanged()
        if(owner()!=expectedOwner)throw OwnerChanged()
        val cookie=repository.credentials?.cookie?.takeIf{it.isNotBlank()} ?: registered.cookies.entries.joinToString("; "){it.key+"="+it.value}
        // Keep this working profile for subsequent series and their next-episode batches.
        return synchronized(profileLock){
            if(revision.get()!=expectedRevision)throw SessionChanged()
            if(owner()!=expectedOwner)throw OwnerChanged()
            Profile(identity,cookie,PlaybackSigner(clock,identity)).also{
                profile=it;profileOwner=expectedOwner
                lastRegistration=clock();lastRegistrationOwner=expectedOwner;lastRegistrationRevision=expectedRevision
            }
        }
        } finally { registrationLock.unlock() }
    }
    private fun select(model: JSONObject,quality: Int,trace: MutableList<String>,rejected: Set<String>): StreamInfo {
        try{return PlaybackStreams.select(model,quality,rejected)}catch(first: NoCompatibleStreamException){
            val endpoints=linkedSetOf<String>()
            fun collect(value: Any?,depth: Int=0) {
                if(depth>4 || endpoints.size>=3)return
                when(value) {
                    is JSONObject -> collect(value.opt("fallback_api"),depth+1)
                    is JSONArray -> for(i in 0 until minOf(value.length(),16))collect(value.opt(i),depth+1)
                    is String -> if(value.startsWith("https://"))endpoints+=value else
                        collect(runCatching{JSONTokener(value).nextValue()}.getOrNull(),depth+1)
                }
            }
            collect(model.opt("fallback_api"))
            var failure=first
            for((index,url) in endpoints.withIndex())try {
                val request=Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).build()
                val data=model(read(request,trace,"fallback${index+1}")) ?: throw IOException("备用播放信息格式异常")
                val video=model(data.opt("video_info")) ?: data
                return PlaybackStreams.select(model(video.opt("data")) ?: video,quality,rejected)
            }catch(error: Exception){
                if(error is HttpFailure&&error.status==429)throw error
                RequestScope.ensureActive()
                if(error is NoCompatibleStreamException)failure=error
            }
            throw failure
        }
    }
    private fun website(sid: String,id: String,quality: Int,trace: MutableList<String>,rejected: Set<String>): StreamInfo {
        var last: Exception?=null
        for((index,host) in listOf("hongguoduanju.com","novelquickapp.com").withIndex())try{
            val request=Request.Builder().url("https://$host/player/$sid/$id")
                .header("User-Agent","Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                .header("Referer","https://$host/").header("Accept-Language","zh-CN,zh;q=0.9").build()
            val html=read(request,trace,"web${index+1}",16*1024*1024)
            return select(PlaybackStreams.webModel(html,sid,id),quality,trace,rejected)
        }catch(error: Exception){
            if(error is ProtectedWebPlaybackException || error is HttpFailure&&error.status==429)throw error
            RequestScope.ensureActive();last=preferFailure(last,error)
        }
        throw last ?: IOException("没有电视可播放的清晰度")
    }

    private fun creatorVideo(id: String,quality: Int,trace: MutableList<String>,rejected: Set<String>): StreamInfo {
        val request=Request.Builder()
            .url("https://novelquickapp.com/hongguo/ug/pages/video-pugc-share?video_id=$id&share_type=3")
            .header("User-Agent","Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .header("Referer","https://novelquickapp.com/").build()
        return PlaybackStreams.creatorWeb(read(request,trace,"creator"),quality,rejected)
    }

    /** An App-only series can still have a valid favourite and episode list. */
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val webFailure=try {
            return RequestScope.withDeadline(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)){repository.detail(id)}
        } catch(error: Exception) {
            RequestScope.ensureActive()
            if(error is ContentHttpFailure && error.status==429)throw error
            error
        }
        return RequestScope.withDeadline(System.nanoTime()+TimeUnit.SECONDS.toNanos(10)) {
            val expectedOwner=owner()
            val trace=mutableListOf<String>()
            val current=profile()
            val device=if(current.values["device_id"].isNullOrBlank()) {
                freshProfile(trace,expectedOwner,current) ?: throw IOException("播放设备暂未准备好",webFailure)
            } else current
            val album="/novel/player/album_detail/v1/"
            val single="/novel/player/video_detail/v1/"
            val batch="/novel/player/multi_video_detail/v1/"
            val paths=if(webFailure is ContentHttpFailure && webFailure.status==404)
                listOf(album,single,batch) else listOf(single,batch,album)
            var failure: Exception=webFailure
            for(path in paths) {
                RequestScope.ensureActive()
                if(owner()!=expectedOwner)throw OwnerChanged()
                val body=when(path) {
                    album -> JSONObject().put("album_id",id).put("need_video_detail_info",true)
                        .put("series_ids",JSONArray()).put("biz_param",JSONObject().put("video_platform",3))
                    batch -> JSONObject().put("series_id",id).put("biz_param",JSONObject().put("detail_page_version",0)
                        .put("source",7).put("screen_width_px","900").put("need_all_video_definition",false).put("use_os_player",false))
                    else -> JSONObject().put("series_id",id)
                }
                val label=when(path){album->"detail-album";batch->"detail-batch";else->"detail-single"}
                try {
                    val identity=if(path==album)device.copy(cookie="") else device
                    val req=request("https://api5-normal-sinfonlineb.fqnovel.com"+path,identity,
                        body=body.toString().toByteArray(Charsets.UTF_8))
                    val data=model(read(req,trace,label)) ?: throw IOException("App 分集详情格式异常")
                    val code=data.optInt("code",data.optInt("Code",-1))
                    if(code!=0){trace+="$label:code$code";throw IOException("App 分集详情暂不可用")}
                    if(owner()!=expectedOwner)throw OwnerChanged()
                    return@withDeadline if(path==album){
                        val info=model(data.optJSONObject("data")?.opt("album_data"))
                            ?: throw IOException("App 暂未返回合集目录")
                        repository.creatorAlbum(id,info)
                    } else repository.appDetail(id,data,path==batch)
                } catch(error: Exception) {
                    if(error is OwnerChanged || error is HttpFailure && error.status==429)throw error
                    RequestScope.ensureActive()
                    failure=error
                }
            }
            val status=trace.filter { it.substringAfter(':').startsWith("H") || it.substringAfter(':').startsWith("code") }
            val suffix=if(status.isEmpty())"" else "（"+status.joinToString("，")+"）"
            throw IOException("网页和 App 都未返回可打开的分集$suffix",failure)
        }
    }
    @JvmOverloads
    fun resolve(sid: String,id: String,ahead: List<String>,quality: Int,speculative: Boolean,
        rejected: Set<String> = emptySet(),contentType: Int = 0): StreamInfo {
        require(sid.matches(Regex("[0-9]{1,30}"))&&id.matches(Regex("[0-9]{1,30}")))
        val ids=(listOf(id)+ahead.take(15)).distinct();require(ids.all{it.matches(Regex("[0-9]{1,30}"))})
        val trace=mutableListOf<String>();val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(if(speculative)10 else 20)
        val expectedRevision=revision.get();val account=repository.credentials?.cookie
        fun active(){RequestScope.ensureActive();if(revision.get()!=expectedRevision || repository.credentials?.cookie!=account)throw SessionChanged()}
        fun critical(error: Exception){if(error is OwnerChanged || error is SessionChanged || error is HttpFailure&&error.status==429)throw error;active()}
        try{return RequestScope.withDeadline(end){
            var last: Exception?=null
            var webRestriction: ProtectedWebPlaybackException?=null
            val rounds=if(speculative)2 else 3
            for(round in 0 until rounds){
                active()
                if(round>0){Thread.sleep(if(round==1)250L else 600L);active();trace+="retry:$round"}
                val expectedOwner=owner()
                try {
                    // One slow endpoint cannot consume the entire retry/fallback budget.
                    val roundEnd=minOf(end,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(if(speculative)4500 else 6000))
                    var cachedFailure: Exception?=null
                    val stream=try {
                        RequestScope.withDeadline(roundEnd) roundDeadline@{
                            if(contentType==CREATOR_VIDEO_CONTENT_TYPE)return@roundDeadline creatorVideo(id,quality,trace,rejected)
                            val cachedModel=cached(sid,id)
                            if(cachedModel!=null)try {
                                trace+="cache:hit"
                                return@roundDeadline select(cachedModel,quality,trace,rejected)
                            } catch(error: Exception) {
                                critical(error)
                                invalidate(sid,id)
                                cachedFailure=error
                            }
                            var selected=profile()
                            if(selected.values["device_id"].isNullOrBlank()){
                                selected=freshProfile(trace,expectedOwner,selected) ?: throw IOException("播放设备暂未准备好")
                            }
                            try {
                                fetch(sid,ids,selected,trace,expectedOwner,quality,rejected,round)
                            } catch(first: Exception) {
                                critical(first)
                                // An unavailable route or unsupported format is independent of device registration.
                                if(round==0 || first is NoCompatibleStreamException ||
                                    first is HttpFailure && first.status==404)throw first
                                val fresh=freshProfile(trace,expectedOwner,selected) ?: throw first
                                fetch(sid,ids,fresh,trace,expectedOwner,quality,rejected,round)
                            }
                        }
                    }catch(error: Exception){
                        critical(error)
                        if(contentType==CREATOR_VIDEO_CONTENT_TYPE)throw error
                        if(webRestriction!=null)throw preferFailure(cachedFailure,error)
                        val webEnd=minOf(end,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(if(speculative)2500 else 4000))
                        try{RequestScope.withDeadline(webEnd){website(sid,id,quality,trace,rejected)}}catch(web: Exception){
                            critical(web)
                            if(web is ProtectedWebPlaybackException)webRestriction=web
                            throw preferFailure(preferFailure(cachedFailure,error),web)
                        }
                    }
                    active();if(owner()!=expectedOwner)throw OwnerChanged()
                    return@withDeadline stream
                }catch(error: Exception){
                    active();if(error is SessionChanged || error is HttpFailure&&error.status==429)throw error
                    invalidate(sid,id);last=preferFailure(last,error)
                    trace+="round:${round+1}:${if(error is OwnerChanged)"device" else if(error is java.net.SocketTimeoutException)"timeout" else "unready"}"
                }
            }
            throw last ?: IOException("播放信息暂未准备好")
        }}finally{lastTrace=trace.takeLast(16).joinToString(" ")}
    }
}
