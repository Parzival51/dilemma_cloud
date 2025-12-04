package com.example

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    @Test
    fun testPing() = testApplication {
        application { module() }
        val res = client.get("/ping")
        assertEquals(HttpStatusCode.OK, res.status)
        // İstersen body de doğrula:
        // assertEquals("pong", res.bodyAsText())
    }
}

