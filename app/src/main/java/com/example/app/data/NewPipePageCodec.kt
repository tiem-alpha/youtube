package com.example.app.data

import com.example.app.domain.FeedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/** Persist every continuation field, including the channel context and POST body. */
internal object NewPipePageCodec {
    private const val PREFIX = "newpipe-v1:"
    private fun fingerprint(request: FeedRequest): String = MessageDigest.getInstance("SHA-256")
        .digest(JSONArray(listOf(request.kind.name, request.query, request.resourceId, request.order,
            request.duration, request.liveOnly, request.categoryId)).toString().toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun encode(request: FeedRequest, page: Page?): String? {
        if (!Page.isValid(page)) return null
        return PREFIX + JSONObject().put("request", fingerprint(request))
            .put("url", page!!.url).put("id", page.id)
            .put("ids", page.ids?.let(::JSONArray)).put("cookies", page.cookies?.let(::JSONObject))
            .put("body", page.body?.let { Base64.getEncoder().encodeToString(it) }).toString()
    }

    fun decode(request: FeedRequest, token: String): Page {
        try {
            require(token.startsWith(PREFIX) && token.length <= 128 * 1024)
            val json = JSONObject(token.removePrefix(PREFIX))
            require(json.getString("request") == fingerprint(request))
            val url = json.optString("url").takeIf(String::isNotBlank)
            if (url != null) {
                val uri = URI(url)
                require(uri.scheme == "https" && uri.host in setOf("www.youtube.com", "youtube.com", "youtubei.googleapis.com") && uri.userInfo == null)
            }
            val ids = json.optJSONArray("ids")?.let { a -> (0 until a.length()).map(a::getString) }
            val cookies = json.optJSONObject("cookies")?.let { o -> o.keys().asSequence().associateWith(o::getString) }
            return Page(url, json.optString("id").takeIf(String::isNotBlank), ids, cookies,
                json.optString("body").takeIf(String::isNotBlank)?.let { Base64.getDecoder().decode(it) })
                .also { require(Page.isValid(it)) }
        } catch (e: Exception) {
            throw PublicBrowseException("Danh sách đã thay đổi. Hãy làm mới để tải tiếp.", e)
        }
    }
}
