package org.jetbrains.ktor.heroku

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import freemarker.cache.ClassTemplateLoader
import org.jetbrains.ktor.application.Application
import org.jetbrains.ktor.application.call
import org.jetbrains.ktor.application.install
import org.jetbrains.ktor.content.serveClasspathResources
import org.jetbrains.ktor.features.ConditionalHeaders
import org.jetbrains.ktor.features.DefaultHeaders
import org.jetbrains.ktor.features.PartialContentSupport
import org.jetbrains.ktor.features.StatusPages
import org.jetbrains.ktor.freemarker.FreeMarker
import org.jetbrains.ktor.freemarker.FreeMarkerContent
import org.jetbrains.ktor.host.embeddedServer
import org.jetbrains.ktor.http.ContentType
import org.jetbrains.ktor.http.HttpStatusCode
import org.jetbrains.ktor.http.withCharset
import org.jetbrains.ktor.netty.Netty
import org.jetbrains.ktor.response.header
import org.jetbrains.ktor.response.respondRedirect
import org.jetbrains.ktor.routing.Routing
import org.jetbrains.ktor.routing.get
import java.util.*

val hikariConfig = HikariConfig().apply {
    jdbcUrl = System.getenv("JDBC_DATABASE_URL")
}

val dataSource = if (hikariConfig.jdbcUrl != null)
    HikariDataSource(hikariConfig)
else
    HikariDataSource()

val html_utf8 = ContentType.Text.Html.withCharset(Charsets.UTF_8)

var counter = 0;

fun Application.module() {
    install(DefaultHeaders)
    install(ConditionalHeaders)
    install(PartialContentSupport)

    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(environment.classLoader, "templates")
    }

    install(StatusPages) {
        exception<Exception> { exception ->
            call.respond(FreeMarkerContent("error.ftl", exception, "", html_utf8))
        }
    }

    install(Routing) {
        serveClasspathResources("public")

        get("/") {
            val model = HashMap<String, Any>()
            if (getVar(1) == "working") {
                model.put("image", "working.jpg")
                model.put("button", "stop.png")
                model.put("href", "stop")
            } else {
                model.put("image", "stopped.jpg")
                model.put("button", "start.png")
                model.put("href", "start")
            }
            val etag = model.toString().hashCode().toString()
            call.response.header("Content-Type", "text/html; charset=UTF-8")
            call.response.status(HttpStatusCode.OK)
            call.respond(FreeMarkerContent("index.ftl", model, etag, html_utf8))
        }

        get("start") {
            setVar(1, "working")
            call.respondRedirect("/");
        }

        get("stop") {
            setVar(1, "stopped")
            call.respondRedirect("/");
        }

        get("status") {
            if (getVar(1) == "working") {
                call.respond("working")
            } else {
                call.respond("stopped")
            }
        }

        get("init") {
            val model = HashMap<String, Any>()
            dataSource.connection.use { connection ->
                connection.createStatement().run {
                    executeUpdate("CREATE TABLE IF NOT EXISTS keyvalue (keyf integer, valuef text)")
                    executeUpdate("INSERT INTO keyvalue VALUES (1,'working')")
                }
            }
            call.respond("inited")
        }
    }
}

fun setVar(key: Int, value: String) {
    dataSource.connection.use { connection ->
        connection.createStatement().run {
            executeUpdate("UPDATE keyvalue SET valuef='${value}' WHERE keyf=${key}")
        }
    }
}

fun getVar(key: Int): String? {
    dataSource.connection.use { connection ->
        val rs = connection.createStatement().run {
            executeQuery("SELECT * FROM keyvalue WHERE keyf = ${key}")
        }
        while (rs.next()) {
            return rs.getString("valuef")
        }
    }
    throw Exception("can't find")
}

fun main(args: Array<String>) {
    var port: Int = 5000
    try {
        port = Integer.valueOf(System.getenv("PORT"))
    } catch(e: Exception) {

    }
    embeddedServer(Netty, port, reloadPackages = listOf("heroku"), module = Application::module).start()
}


