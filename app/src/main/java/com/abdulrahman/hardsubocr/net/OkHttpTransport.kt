package com.abdulrahman.hardsubocr.net

import android.content.Context
import com.abdulrahman.hardsubocr.core.HttpRequest
import com.abdulrahman.hardsubocr.core.HttpResponse
import com.abdulrahman.hardsubocr.core.HttpTransport
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Net {
    lateinit var client: OkHttpClient
        private set
    lateinit var transport: HttpTransport
        private set

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16 // default 5 would silently cap the parallel workers
        }
        client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        transport = OkHttpTransport(client)
    }
}

class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url)
        for ((k, v) in request.headers) builder.header(k, v)
        val media = request.contentType?.toMediaTypeOrNull()
        if (request.method.equals("GET", ignoreCase = true)) {
            builder.get()
        } else {
            builder.method(request.method.uppercase(), (request.body ?: ByteArray(0)).toRequestBody(media))
        }
        val call = client.newBuilder()
            .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build())

        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val bytes = try {
                            r.body?.bytes() ?: ByteArray(0)
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                            return
                        }
                        val headers = HashMap<String, String>()
                        for (name in r.headers.names()) headers[name] = r.headers[name] ?: ""
                        if (cont.isActive) cont.resume(HttpResponse(r.code, bytes, headers))
                    }
                }
            })
        }
    }
}
