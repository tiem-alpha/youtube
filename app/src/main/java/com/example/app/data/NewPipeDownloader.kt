package com.example.app.data

import android.util.Log
import com.example.app.BuildConfig
import org.json.JSONObject
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

internal class NewPipeDownloader : Downloader() {
    // Each extraction stays on one IO thread. Remove in finally to isolate parallel searches.
    private val searchParams = ThreadLocal<String?>()
    fun <T> withSearchParams(params: String?, block: () -> T): T {
        searchParams.set(params)
        return try { block() } finally { searchParams.remove() }
    }

    override fun execute(request: Request): Response {
        val url = URL(request.url())
        require(url.protocol == "https")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = request.httpMethod()
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            request.headers().forEach { (name, values) ->
                values.forEachIndexed { index, value ->
                    if (index == 0) connection.setRequestProperty(name, value)
                    else connection.addRequestProperty(name, value)
                }
            }
            var data = request.dataToSend()
            val params = searchParams.get()
            if (params != null && url.host == "www.youtube.com" && url.path == "/youtubei/v1/search" && data != null) {
                val json = JSONObject(data.toString(Charsets.UTF_8))
                if (json.has("query") && !json.has("continuation")) {
                    data = json.put("params", params).toString().toByteArray(Charsets.UTF_8)
                }
            }
            data?.let { bytes ->
                connection.doOutput = true
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            if (BuildConfig.DEBUG) runCatching { Log.d("NewPipeHttp", "${request.httpMethod()} ${url.host}${url.path} HTTP=$code") }
            if (code == 429) throw ReCaptchaException("YouTube rate limit", url.toString())
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let {
                val decoded = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(it) else it
                decoded.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
            }.orEmpty()
            return Response(code, connection.responseMessage.orEmpty(), connection.headerFields,
                body, connection.url.toString())
        } finally { connection.disconnect() }
    }
}
