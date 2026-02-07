package com.devoceanblue.stayvista.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}

@RestController
class PingController {
    @GetMapping("/internal/ping")
    fun ping(): Map<String, String> = mapOf("service" to "gateway", "status" to "ok")
}
