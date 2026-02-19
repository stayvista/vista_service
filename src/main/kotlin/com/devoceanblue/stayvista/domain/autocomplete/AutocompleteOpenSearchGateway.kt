package com.devoceanblue.stayvista.domain.autocomplete

import com.devoceanblue.stayvista.domain.common.PlaceType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Locale
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class AutocompleteOpenSearchGateway(
    private val objectMapper: ObjectMapper,
    @Value("\${stayvista.autocomplete.opensearch-url:http://127.0.0.1:39200}") private val openSearchUrl: String,
    @Value("\${stayvista.autocomplete.index-name:ac_candidates_stay_v1}") private val indexName: String,
    @Value("\${stayvista.autocomplete.read-alias:ac_read}") private val readAlias: String,
    @Value("\${stayvista.autocomplete.write-alias:ac_write}") private val writeAlias: String,
    @Value("\${stayvista.autocomplete.os-soft-timeout-ms:150}") private val softTimeoutMs: Long,
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    @Volatile
    private var indexReady: Boolean = false

    fun ensureIndexAndAlias() {
        if (indexReady) return
        synchronized(this) {
            if (indexReady) return

            val indexExists = send("HEAD", "/$indexName", null)
            if (indexExists.statusCode() == 404) {
                val createBody = objectMapper.writeValueAsString(
                    mapOf(
                        "settings" to mapOf(
                            "index" to mapOf(
                                "number_of_shards" to 1,
                                "number_of_replicas" to 0,
                            ),
                        ),
                        "mappings" to mapOf(
                            "properties" to mapOf(
                                "type" to mapOf("type" to "keyword"),
                                "canonical_id" to mapOf("type" to "keyword"),
                                "display_name" to mapOf("type" to "text"),
                                "display_name_ko" to mapOf("type" to "text"),
                                "aliases" to mapOf("type" to "text"),
                                "country" to mapOf("type" to "keyword"),
                                "region" to mapOf("type" to "keyword"),
                                "geo" to mapOf("type" to "geo_point"),
                                "is_blocked" to mapOf("type" to "boolean"),
                                "weight" to mapOf("type" to "double"),
                                "ctr_7d" to mapOf("type" to "double"),
                                "popularity_7d" to mapOf("type" to "long"),
                                "updated_at" to mapOf("type" to "date"),
                            ),
                        ),
                    ),
                )
                val createResponse = send("PUT", "/$indexName", createBody)
                if (createResponse.statusCode() >= 300) {
                    throw IllegalStateException("Failed to create autocomplete index: ${createResponse.body()}")
                }
            } else if (indexExists.statusCode() >= 300) {
                throw IllegalStateException("Failed to check autocomplete index: ${indexExists.statusCode()} ${indexExists.body()}")
            }

            val aliasBody = objectMapper.writeValueAsString(
                mapOf(
                    "actions" to listOf(
                        mapOf("add" to mapOf("index" to indexName, "alias" to readAlias)),
                        mapOf("add" to mapOf("index" to indexName, "alias" to writeAlias)),
                    ),
                ),
            )
            val aliasResponse = send("POST", "/_aliases", aliasBody)
            if (aliasResponse.statusCode() >= 300) {
                throw IllegalStateException("Failed to set autocomplete aliases: ${aliasResponse.body()}")
            }

            indexReady = true
        }
    }

    fun search(
        q: String,
        types: Set<PlaceType>,
        size: Int,
        lang: String,
    ): List<AutocompleteCandidate> {
        ensureIndexAndAlias()

        val should = listOf(
            mapOf(
                "match_phrase_prefix" to mapOf(
                    "display_name" to mapOf(
                        "query" to q,
                        "boost" to 5,
                    ),
                ),
            ),
            mapOf(
                "match_phrase_prefix" to mapOf(
                    "display_name_ko" to mapOf(
                        "query" to q,
                        "boost" to 4,
                    ),
                ),
            ),
            mapOf(
                "match_phrase_prefix" to mapOf(
                    "aliases" to mapOf(
                        "query" to q,
                        "boost" to 3,
                    ),
                ),
            ),
        )

        val filters = mutableListOf<Map<String, Any>>(
            mapOf("term" to mapOf("is_blocked" to false)),
        )

        if (types.isNotEmpty()) {
            filters += mapOf("terms" to mapOf("type" to types.map { it.name }))
        }

        val queryBody = objectMapper.writeValueAsString(
            mapOf(
                "size" to (size.coerceAtLeast(1) * 3),
                "timeout" to "${softTimeoutMs.coerceAtLeast(1)}ms",
                "track_total_hits" to false,
                "query" to mapOf(
                    "function_score" to mapOf(
                        "query" to mapOf(
                            "bool" to mapOf(
                                "filter" to filters,
                                "should" to should,
                                "minimum_should_match" to 1,
                            ),
                        ),
                        "score_mode" to "sum",
                        "boost_mode" to "sum",
                        "functions" to listOf(
                            mapOf("field_value_factor" to mapOf("field" to "weight", "factor" to 1.0, "missing" to 1.0)),
                            mapOf("field_value_factor" to mapOf("field" to "ctr_7d", "factor" to 20.0, "missing" to 0.0)),
                            mapOf("field_value_factor" to mapOf("field" to "popularity_7d", "factor" to 0.0002, "missing" to 0.0)),
                            mapOf("filter" to mapOf("term" to mapOf("type" to "CITY")), "weight" to 2.0),
                            mapOf("filter" to mapOf("term" to mapOf("type" to "PROPERTY")), "weight" to 1.5),
                            mapOf("filter" to mapOf("term" to mapOf("type" to "POI")), "weight" to 1.2),
                        ),
                    ),
                ),
                "sort" to listOf(
                    mapOf("_score" to mapOf("order" to "desc")),
                ),
            ),
        )

        val response = send("POST", "/$readAlias/_search", queryBody)
        if (response.statusCode() >= 300) {
            throw IllegalStateException("Autocomplete OpenSearch query failed: ${response.body()}")
        }

        val root = objectMapper.readTree(response.body())
        return root.path("hits").path("hits")
            .mapNotNull { hit ->
                val source = hit.path("_source")
                val type = source.path("type").asText().trim().uppercase(Locale.ROOT)
                val placeType = runCatching { PlaceType.valueOf(type) }.getOrNull() ?: return@mapNotNull null
                val canonicalId = source.path("canonical_id").asText().trim()
                if (canonicalId.isBlank()) {
                    return@mapNotNull null
                }

                val displayKo = source.path("display_name_ko").asText().trim()
                val displayDefault = source.path("display_name").asText().trim()
                val display = if (lang.startsWith("ko") && displayKo.isNotBlank()) {
                    displayKo
                } else {
                    displayDefault.ifBlank { displayKo }
                }
                if (display.isBlank()) {
                    return@mapNotNull null
                }

                val region = source.path("region").asText().trim()
                val country = source.path("country").asText().trim()
                val subtitle = listOf(region, country)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() }

                val geoNode = source.path("geo")
                val lat = geoNode.path("lat").asDouble(Double.NaN)
                val lng = geoNode.path("lon").takeIf { !it.isMissingNode }?.asDouble(Double.NaN)
                    ?: geoNode.path("lng").asDouble(Double.NaN)

                AutocompleteCandidate(
                    type = placeType,
                    canonicalId = canonicalId,
                    display = display,
                    subtitle = subtitle,
                    lat = if (lat.isFinite()) lat else null,
                    lng = if (lng.isFinite()) lng else null,
                    score = hit.path("_score").asDouble(0.0),
                    source = "opensearch",
                )
            }
    }

    fun updateMetrics(metrics: List<AutocompleteMetricRow>): Int {
        if (metrics.isEmpty()) {
            return 0
        }
        ensureIndexAndAlias()

        val lines = buildString {
            metrics.forEach { row ->
                val docId = "${row.type.name.lowercase()}:${row.canonicalId}"
                append(objectMapper.writeValueAsString(mapOf("update" to mapOf("_index" to writeAlias, "_id" to docId))))
                append('\n')
                append(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "doc" to mapOf(
                                "ctr_7d" to row.ctr7d,
                                "popularity_7d" to row.popularity7d,
                                "updated_at" to Instant.now().toString(),
                            ),
                            "doc_as_upsert" to false,
                        ),
                    ),
                )
                append('\n')
            }
        }

        val response = send(
            method = "POST",
            path = "/_bulk",
            body = lines,
            contentType = "application/x-ndjson",
        )
        if (response.statusCode() >= 300) {
            throw IllegalStateException("Autocomplete OpenSearch bulk update failed: ${response.body()}")
        }

        val root = objectMapper.readTree(response.body())
        if (root.path("errors").asBoolean(false)) {
            val failed = root.path("items")
                .mapNotNull { it.path("update") }
                .count { it.path("error").isObject }
            return (metrics.size - failed).coerceAtLeast(0)
        }

        return metrics.size
    }

    private fun send(
        method: String,
        path: String,
        body: String?,
        contentType: String = "application/json",
    ): HttpResponse<String> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$openSearchUrl$path"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", contentType)

        val request = when (method) {
            "GET" -> requestBuilder.GET().build()
            "HEAD" -> requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
            "PUT" -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body.orEmpty())).build()
            "POST" -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body.orEmpty())).build()
            else -> throw IllegalArgumentException("Unsupported method: $method")
        }

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
