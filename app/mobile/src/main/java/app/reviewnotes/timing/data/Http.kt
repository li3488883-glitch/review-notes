package app.reviewnotes.timing.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Plain HTTP GET with a phone browser's identity and no Referer, the same way the page loads these sources.
 * At most two requests run at a time: the quote sources refuse clients that pull too hard.
 */
object Http {
    private const val UA = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val gate = Semaphore(2)

    class HttpException(val code: Int, url: String) : IOException("HTTP $code: $url")

    suspend fun get(url: String, charset: Charset = Charsets.UTF_8, timeoutMs: Int = 9000): String = gate.withPermit {
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            val t0 = System.currentTimeMillis()
            val tag = url.substringAfter("://").substringBefore("?").take(70)
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                val code = c.responseCode
                if (code !in 200..299) { android.util.Log.w("TimingNet", "$code ${System.currentTimeMillis() - t0}ms $tag"); throw HttpException(code, url) }
                val body = c.inputStream.use { String(it.readBytes(), charset) }
                android.util.Log.i("TimingNet", "$code ${System.currentTimeMillis() - t0}ms ${body.length}c $tag")
                body
            } catch (e: Exception) {
                if (e !is HttpException) android.util.Log.w("TimingNet", "ERR ${System.currentTimeMillis() - t0}ms $tag ${e.javaClass.simpleName}: ${e.message}")
                throw e
            } finally {
                c.disconnect()
            }
        }
    }

    /** One retry after a short pause. */
    suspend fun getR(url: String, charset: Charset = Charsets.UTF_8, timeoutMs: Int = 9000): String =
        try { get(url, charset, timeoutMs) } catch (e: Exception) { delay(700); get(url, charset, timeoutMs) }

    val GBK: Charset = Charset.forName("GBK")
}

fun bust(): String = System.currentTimeMillis().toString()
