package com.devoceanblue.stayvista.ticket

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
class TicketApplication

fun main(args: Array<String>) {
    runApplication<TicketApplication>(*args)
}

@RestController
class PingController {
    @GetMapping("/internal/ping")
    fun ping(): Map<String, String> = mapOf("service" to "ticket", "status" to "ok")
}
