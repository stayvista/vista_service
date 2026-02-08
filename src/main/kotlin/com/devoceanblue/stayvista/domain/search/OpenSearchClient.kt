package com.devoceanblue.stayvista.domain.search

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class OpenSearchClient(
    private val objectMapper: ObjectMapper,
    @Value("\${stayvista.search.opensearch-url:http://127.0.0.1:39200}") private val openSearchUrl: String,
    @Value("\${stayvista.search.index-name:properties_v1}") private val indexName: String,
    @Value("\${stayvista.search.alias-name:properties}") private val aliasName: String,
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    @Volatile
    private var indexReady: Boolean = false

    fun ensureIndexAndAlias() {
        if (indexReady) {
            return
        }
        synchronized(this) {
            if (indexReady) {
                return
            }

            val headResponse = send("HEAD", "/$indexName", null)
            if (headResponse.statusCode() == 404) {
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
                                "property_id" to mapOf("type" to "long"),
                                "name" to mapOf("type" to "text"),
                                "city" to mapOf("type" to "keyword"),
                                "country" to mapOf("type" to "keyword"),
                                "status" to mapOf("type" to "keyword"),
                                "location" to mapOf("type" to "geo_point"),
                                "price_min" to mapOf("type" to "long"),
                                "rating" to mapOf("type" to "double"),
                                "thumbnail_url" to mapOf("type" to "keyword"),
                                "room_types" to mapOf(
                                    "type" to "nested",
                                    "properties" to mapOf(
                                        "room_type_id" to mapOf("type" to "long"),
                                        "name" to mapOf("type" to "text"),
                                        "max_guests" to mapOf("type" to "integer"),
                                        "base_price" to mapOf("type" to "long"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
                val createResponse = send("PUT", "/$indexName", createBody)
                if (createResponse.statusCode() >= 300) {
                    throw IllegalStateException("Failed to create index: ${createResponse.body()}")
                }
            } else if (headResponse.statusCode() >= 300) {
                throw IllegalStateException("Failed to check index: ${headResponse.statusCode()} ${headResponse.body()}")
            }

            val aliasBody = objectMapper.writeValueAsString(
                mapOf(
                    "actions" to listOf(
                        mapOf("add" to mapOf("index" to indexName, "alias" to aliasName)),
                    ),
                ),
            )
            val aliasResponse = send("POST", "/_aliases", aliasBody)
            if (aliasResponse.statusCode() >= 300) {
                throw IllegalStateException("Failed to set alias: ${aliasResponse.body()}")
            }

            indexReady = true
        }
    }

    fun upsertProperty(propertyId: Long, document: Map<String, Any?>) {
        ensureIndexAndAlias()
        val response = send(
            "PUT",
            "/$aliasName/_doc/$propertyId",
            objectMapper.writeValueAsString(document),
        )
        if (response.statusCode() >= 300) {
            throw IllegalStateException("Failed to index property $propertyId: ${response.body()}")
        }
    }

    fun search(request: SearchRequest): SearchData {
        ensureIndexAndAlias()
        val limit = request.limit.coerceIn(1, 50)
        val searchAfter = decodeSearchAfter(request.cursor)

        val must = mutableListOf<Map<String, Any>>()
        if (!request.q.isNullOrBlank()) {
            must += mapOf("match" to mapOf("name" to request.q.trim()))
        }

        val filters = mutableListOf<Map<String, Any>>()
        if (!request.city.isNullOrBlank()) {
            filters += mapOf("term" to mapOf("city" to request.city))
        }
        if (request.min_price != null || request.max_price != null) {
            val range = mutableMapOf<String, Any>()
            request.min_price?.let { range["gte"] = it }
            request.max_price?.let { range["lte"] = it }
            filters += mapOf("range" to mapOf("price_min" to range))
        }
        if (request.min_rating != null) {
            filters += mapOf(
                "range" to mapOf(
                    "rating" to mapOf("gte" to request.min_rating),
                ),
            )
        }

        val bool = mutableMapOf<String, Any>(
            "must" to if (must.isEmpty()) listOf(mapOf("match_all" to emptyMap<String, Any>())) else must,
        )
        if (filters.isNotEmpty()) {
            bool["filter"] = filters
        }

        val sort = when (request.sort) {
            "price_asc" -> listOf(
                mapOf("price_min" to mapOf("order" to "asc")),
                mapOf("property_id" to mapOf("order" to "asc")),
            )

            "price_desc" -> listOf(
                mapOf("price_min" to mapOf("order" to "desc")),
                mapOf("property_id" to mapOf("order" to "desc")),
            )

            "rating_desc" -> listOf(
                mapOf("rating" to mapOf("order" to "desc")),
                mapOf("property_id" to mapOf("order" to "asc")),
            )

            else -> listOf(mapOf("property_id" to mapOf("order" to "asc")))
        }

        val queryBody = objectMapper.writeValueAsString(
            mutableMapOf<String, Any>(
                "size" to (limit + 1),
                "query" to mapOf("bool" to bool),
                "sort" to sort,
            ).apply {
                if (searchAfter != null) {
                    this["search_after"] = searchAfter
                }
            },
        )

        val response = send("POST", "/$aliasName/_search", queryBody)
        if (response.statusCode() >= 300) {
            throw IllegalStateException("OpenSearch query failed: ${response.body()}")
        }

        val tree = objectMapper.readTree(response.body())
        val hits = tree.path("hits").path("hits")
        val parsed = mutableListOf<ScoredHit>()
        hits.forEach { hit ->
            val source = hit.path("_source")
            val sortValues = hit.path("sort").toSortValues()
            parsed += ScoredHit(
                item = SearchItem(
                    property_id = source.path("property_id").asLong(),
                    name = source.path("name").asText(""),
                    city = source.path("city").nullableText(),
                    price_min = source.path("price_min").asLong(0),
                    rating = source.path("rating").asDouble(0.0),
                    thumbnail_url = source.path("thumbnail_url").nullableText(),
                ),
                sortValues = sortValues,
            )
        }

        val hasNext = parsed.size > limit
        val items = if (hasNext) parsed.dropLast(1) else parsed
        val nextCursor = if (hasNext) encodeSearchAfter(items.last().sortValues) else null
        return SearchData(
            items = items.map { it.item },
            next_cursor = nextCursor,
        )
    }

    private fun decodeSearchAfter(cursor: String?): List<Any>? {
        if (cursor.isNullOrBlank()) {
            return null
        }
        return try {
            val raw = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(raw, List::class.java) as List<Any>
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeSearchAfter(sortValues: List<Any>): String {
        val raw = objectMapper.writeValueAsString(sortValues)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    private fun JsonNode.toSortValues(): List<Any> {
        if (!isArray) {
            return emptyList()
        }
        val values = mutableListOf<Any>()
        forEach { node ->
            values += when {
                node.isLong || node.isInt -> node.asLong()
                node.isFloatingPointNumber -> node.asDouble()
                node.isBoolean -> node.asBoolean()
                node.isTextual -> node.asText()
                else -> node.toString()
            }
        }
        return values
    }

    private fun JsonNode.nullableText(): String? {
        return if (isMissingNode || isNull) null else asText()
    }

    private data class ScoredHit(
        val item: SearchItem,
        val sortValues: List<Any>,
    )

    private fun send(method: String, path: String, body: String?): HttpResponse<String> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$openSearchUrl$path"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")

        val request = when (method) {
            "GET" -> requestBuilder.GET().build()
            "HEAD" -> requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
            "PUT" -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body ?: "")).build()
            "POST" -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body ?: "")).build()
            else -> throw IllegalArgumentException("Unsupported method: $method")
        }

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
