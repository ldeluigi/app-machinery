package app.machinery.release

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sends requests to one of Google's APIs, named [service] in its errors, which add [forbiddenHint] to a 403: the
 * permission the service account lacks.
 */
class GoogleRequests(
    private val service: String,
    private val forbiddenHint: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_S)).build()

    /** Sends the request, and returns the answer's body; fails with Google's answer unless it is a success. */
    fun send(builder: HttpRequest.Builder): String {
        val request = builder.timeout(Duration.ofSeconds(TIMEOUT_S)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        if (status !in OK) {
            val hint = if (status == FORBIDDEN) forbiddenHint else ""
            throw IOException(
                "$service answered $status to ${request.method()} ${request.uri().path}$hint\n" +
                    response.body().take(ERROR_BODY_CHARS),
            )
        }
        return response.body()
    }

    private companion object {
        const val TIMEOUT_S = 120L
        const val FORBIDDEN = 403
        const val ERROR_BODY_CHARS = 500
        val OK = 200..299
    }
}
