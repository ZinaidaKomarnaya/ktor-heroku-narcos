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
            if(getVar("status") == "working") {
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
            setVar("status", "working")
            call.respondRedirect("/", true);
        }

        get("stop") {
            setVar("status", "stopped")
            call.respondRedirect("/", true);
        }

        get("status") {
            if(getVar("status") == "working") {
                call.respond("working")
            } else {
                call.respond("stopped")
            }
        }
    }
}

fun setVar(key:String, value:String) {
    dataSource.connection.use { connection ->
        connection.createStatement().run {
//            executeUpdate("DROP TABLE IF EXISTS keyvalue")
            executeUpdate("CREATE TABLE IF NOT EXISTS keyvalue (keyf text, varf text)")
            executeUpdate("INSERT INTO keyvalue VALUES ('${key}','${value}')")
        }
    }
}

fun getVar(key:String):String? {
    try {
        dataSource.connection.use { connection ->
            val rs = connection.createStatement().run {
                executeQuery("SELECT * FROM keyvalue WHERE keyf='${key}'")
            }
            while (rs.next()) {
                return rs.getString("varf")
            }
        }
    } catch (e:Throwable) {

    }
    return null;
}

fun main(args: Array<String>) {
    var port:Int = 5000
    try{
        port = Integer.valueOf(System.getenv("PORT"))
    } catch(e:Exception) {

    }
    embeddedServer(Netty, port, reloadPackages = listOf("heroku"), module = Application::module).start()
}


