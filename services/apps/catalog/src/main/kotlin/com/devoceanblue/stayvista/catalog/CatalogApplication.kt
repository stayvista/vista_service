package com.devoceanblue.stayvista.catalog

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
class CatalogApplication

fun main(args: Array<String>) {
    runApplication<CatalogApplication>(*args)
}

@RestController
class PingController {
    @GetMapping("/internal/ping")
    fun ping(): Map<String, String> = mapOf("service" to "catalog", "status" to "ok")
}
