package games.ramen

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

data class ManifestUpdate(
    val operation: String,
    val mod: String,
    val minecraftVersion: String,
    val loader: String,
    val channel: String,
    val version: String
)

class ManifestWebhook(
    private val log: (String) -> Unit
) : AutoCloseable {

    private val endpoint = URI.create(
        requireNotNull(System.getenv("MOD_MANIFEST_WEBHOOK_URL")) {
            "MOD_MANIFEST_WEBHOOK_URL is not configured"
        }
    )

    private val secret = requireNotNull(
        System.getenv("MOD_MANIFEST_WEBHOOK_SECRET")
    ) {
        "MOD_MANIFEST_WEBHOOK_SECRET is not configured"
    }.also {
        require(it.isNotBlank()) {
            "MOD_MANIFEST_WEBHOOK_SECRET must not be blank"
        }
    }

    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    // One worker keeps notifications ordered and keeps network calls
    // off Reposilite's request-handling thread.
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mod-manifest-webhook").apply {
            isDaemon = true
        }
    }

    fun enqueue(update: ManifestUpdate) {
        worker.submit {
            try {
                deliver(update)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                log("Manifest webhook failed: ${error.message}")
            }
        }
    }

    private fun deliver(update: ManifestUpdate) {
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $secret")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    update.toJson()
                )
            )
            .build()

        for (attempt in 1..3) {
            try {
                val response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )

                val status = response.statusCode()

                if (status in 200..299) {
                    log(
                        "Manifest ${update.operation} succeeded: " +
                                "${update.mod}/${update.minecraftVersion}/" +
                                "${update.loader}/${update.version}"
                    )
                    return
                }

                log(
                    "Manifest webhook returned HTTP $status: " +
                            response.body().take(500)
                )

                // Retry temporary failures, not invalid payloads
                // or authentication/configuration failures.
                if (
                    status != 408 &&
                    status != 429 &&
                    status !in 500..599
                ) {
                    return
                }
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                log(
                    "Manifest webhook attempt $attempt failed " +
                            "for $endpoint:\n${error.stackTraceToString()}"
                )
            }

            if (attempt < 3) {
                Thread.sleep(1000L * attempt)
            }
        }

        log(
            "Manifest webhook delivery exhausted retries: " +
                    "${update.mod}/${update.version}"
        )
    }

    override fun close() {
        worker.shutdown()
    }
}

// Small JSON encoder for this fixed, string-only payload.
// No extra JSON dependency required.
private fun jsonString(value: String): String = buildString {
    append('"')

    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 0x20) {
                    append("\\u")
                    append(
                        character.code.toString(16).padStart(4, '0')
                    )
                } else {
                    append(character)
                }
            }
        }
    }

    append('"')
}

private fun ManifestUpdate.toJson(): String =
    listOf(
        "operation" to operation,
        "mod" to mod,
        "minecraftVersion" to minecraftVersion,
        "loader" to loader,
        "channel" to channel,
        "version" to version
    ).joinToString(
        separator = ",",
        prefix = "{",
        postfix = "}"
    ) { (key, value) ->
        "${jsonString(key)}:${jsonString(value)}"
    }