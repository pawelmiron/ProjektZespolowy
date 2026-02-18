package pl.projektzespolowy

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.staticResources
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.queryParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.max

private const val BINANCE_KLINES_URL = "https://api.binance.com/api/v3/klines"
private const val DEFAULT_INTERVAL = "1m"
private const val DEFAULT_LIMIT = 1000

@Serializable
data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long
)

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json()
        }
        install(CORS) {
            anyHost()
            allowHeader("Content-Type")
        }

        routing {
            staticResources("/", "static")

            get("/api/candles") {
                val hours = call.request.queryParameters["hours"]?.toIntOrNull()?.coerceIn(1, 240) ?: 6
                val interval = call.request.queryParameters["interval"] ?: DEFAULT_INTERVAL
                val symbol = call.request.queryParameters["symbol"] ?: "BTCUSDT"

                val now = System.currentTimeMillis()
                val startTime = now - hours * 60L * 60L * 1000L

                runCatching {
                    fetchCandles(symbol, interval, startTime)
                }.onSuccess { candles ->
                    call.respond(candles)
                }.onFailure { error ->
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to (error.message ?: "Failed to fetch candles")))
                }
            }

            get("/api/latest") {
                val fromOpenTime = max(
                    0L,
                    call.request.queryParameters["fromOpenTime"]?.toLongOrNull() ?: 0L
                )
                val interval = call.request.queryParameters["interval"] ?: DEFAULT_INTERVAL
                val symbol = call.request.queryParameters["symbol"] ?: "BTCUSDT"

                runCatching {
                    fetchCandles(symbol, interval, fromOpenTime)
                }.onSuccess { candles ->
                    val filtered = candles.filter { it.openTime >= fromOpenTime }
                    call.respond(filtered)
                }.onFailure { error ->
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to (error.message ?: "Failed to fetch latest candles")))
                }
            }

            get("/health") {
                call.respondText("OK", ContentType.Text.Plain)
            }
        }
    }.start(wait = true)
}

private fun fetchCandles(symbol: String, interval: String, startTime: Long): List<Candle> {
    val uri = URI(
        "$BINANCE_KLINES_URL?symbol=$symbol&interval=$interval&startTime=$startTime&limit=$DEFAULT_LIMIT"
    )
    val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 10_000
    }

    return try {
        val status = connection.responseCode
        if (status !in 200..299) {
            val responseBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IllegalStateException("Binance API returned HTTP $status. $responseBody")
        }

        val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
        parseKlinesJson(responseBody)
    } finally {
        connection.disconnect()
    }
}

private fun parseKlinesJson(json: String): List<Candle> {
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json)
    val rootArray = parsed.jsonArray

    return rootArray.mapNotNull { rowElement ->
        val row = rowElement.jsonArray
        if (row.size < 7) {
            null
        } else {
            Candle(
                openTime = row[0].toString().trim('"').toLong(),
                open = row[1].toString().trim('"').toDouble(),
                high = row[2].toString().trim('"').toDouble(),
                low = row[3].toString().trim('"').toDouble(),
                close = row[4].toString().trim('"').toDouble(),
                volume = row[5].toString().trim('"').toDouble(),
                closeTime = row[6].toString().trim('"').toLong()
            )
        }
    }
}
