package com.devoceanblue.stayvista.search

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
class SearchApplication

fun main(args: Array<String>) {
    runApplication<SearchApplication>(*args)
}

@RestController
class PingController {
    @GetMapping("/internal/ping")
    fun ping(): Map<String, String> = mapOf("service" to "search", "status" to "ok")
}
