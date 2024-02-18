import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

fun HttpClient.get(uri: String): String {
    val request = HttpRequest.newBuilder(URI(uri)).GET().build()
    val response = this.send(request, HttpResponse.BodyHandlers.ofString())
    return response.body()
}

fun HttpClient.download(uri: String, path: Path) {
    val request = HttpRequest.newBuilder(URI(uri)).GET().build()
    val response = this.send(request, HttpResponse.BodyHandlers.ofInputStream())

    Files.createDirectories(path.parent)
    FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { outputChannel ->
        response.body().use { inputStream ->
            Channels.newChannel(inputStream).use { inputChannel ->
                outputChannel.transferFrom(inputChannel, 0, Long.MAX_VALUE)
            }
        }
    }
}

inline fun <reified T> Gson.fromJson(json: String): T {
    val t = object : TypeToken<T>() {}.type
    return this.fromJson(json, t) as T
}

fun sha1(input: ByteArray): String {
    return MessageDigest.getInstance("SHA-1")
        .digest(input)
        .joinToString(separator = "", transform = { "%02x".format(it) })
}

fun main() {
    val gson = Gson()
    val httpClient = HttpClient.newHttpClient()
    val out = File("out").apply { mkdir() }

    val index = gson.fromJson<JsonArray>(httpClient.get("https://betacraft.uk/server-archive/server_index.json"))
    val manifest = gson.fromJson<JsonObject>(httpClient.get("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"))

    val artifacts = HashMap<String, JsonObject>()

    val serverIndexes = HashMap<String, JsonObject>()

    //populate server indexes
    index.asJsonArray.map { it.asJsonObject }
        .reversed() // it'll fetch in reverse, so we should get the 'latest' possible server for our client (contains snapshots!!)
        .forEach { serverInfo ->
        serverInfo.asJsonObject["compatible_clients"].asJsonArray.map { it.asString }.forEach { clientVersion ->
            // ehhhhhh i mean it works..
            serverIndexes[clientVersion.split("-")[0]] = serverInfo
        }
    }

    manifest["versions"].asJsonArray.map { it.asJsonObject }.forEach { version ->
        val id = version["id"].asString
        val url = version["url"].asString

        val versionInfo = gson.fromJson<JsonObject>(httpClient.get(url))
        val downloads = versionInfo["downloads"].asJsonObject

        var changed = false

        // fill missing servers infos
        if (!downloads.has("server")) {
            var fixedId = id

            if(id == "1.0") fixedId = "1.0.0" // hardcoded fixes maybe someone could find a better way ?,
            if(id == "a1.2.2a") fixedId = "a1.2.2"
            if(id == "a1.2.2b") fixedId = "a1.2.2"
            if(id == "b1.3b") fixedId = "b1.3"

            val serverInfo = serverIndexes[fixedId]

            if (serverInfo != null) {
                val formats = serverInfo["available_formats"].asJsonArray
                val jarFormat = formats.map { it as JsonObject }.single { it["format"].asString == "jar" }

                downloads.add("server", JsonObject().apply {
                    addProperty("sha1", jarFormat["sha1"].asString.lowercase())
                    addProperty("size", jarFormat["size"].asInt)
                    addProperty("url", jarFormat["url"].asString)
                })

                changed = true
            }
        }

        // Replace lwjgl2 with babric fork
        versionInfo["libraries"]?.asJsonArray?.let { libraries ->
            libraries.map { it.asJsonObject }.forEach libraries@{ library ->
                val (groupId, name, version) = library["name"].asString.split(":")

                if (groupId == "org.lwjgl.lwjgl" && version.startsWith("2.")) {
                    library["rules"]?.asJsonArray?.let { rules ->
                        rules.map { it.asJsonObject }.forEach { rule ->
                            val action = rule["action"].asString
                            val os = rule["os"]?.asJsonObject

                            // Remove macos specific workarounds
                            if (action == "allow" && os != null && os["name"].asString == "osx") {
                                libraries.remove(library)
                                return@libraries
                            }
                        }
                    }

                    library.remove("downloads")
                    library.remove("rules")

                    val version = "2.9.4-babric.1"
                    val mavenUrl = "https://maven.glass-launcher.net/babric/"

                    fun getArtifact(classifier: String? = null): JsonObject {
                        val path = "${groupId.replace('.', '/')}/$name/$version/$name-$version${if (classifier != null) "-$classifier" else ""}.jar"
                        val url = mavenUrl + path

                        artifacts[url]?.let { return it }

                        synchronized(artifacts) {
                            artifacts[url]?.let { return it }

                            val downloadedPath = Path.of("build", "tmp", path)

                            if (!Files.exists(downloadedPath)) {
                                println("Downloading $url")
                                httpClient.download(url, downloadedPath)
                            }

                            return JsonObject().apply {
                                addProperty("path", path)
                                addProperty("url", url)
                                addProperty("size", Files.size(downloadedPath))
                                addProperty("sha1", sha1(Files.readAllBytes(downloadedPath)))

                                artifacts[url] = this
                            }
                        }
                    }

                    library.addProperty("name", "${groupId}:${name}:${version}")
                    library.addProperty("url", mavenUrl)

                    library.add("downloads", JsonObject().apply {
                        if (library.has("natives")) {
                            add("classifiers", JsonObject().apply {
                                library["natives"].asJsonObject.entrySet().forEach {
                                    val classifier = it.value.asString
                                    add(classifier, getArtifact(classifier))
                                }
                            })
                        } else {
                            add("artifact", getArtifact())
                        }
                    })

                    changed = true
                }
            }
        }

        if (changed) {
            val bytes = gson.toJson(versionInfo).toByteArray()
            version.addProperty("url", "https://babric.github.io/manifest-polyfill/$id.json")
            version.addProperty("sha1", sha1(bytes))
            File(out, "$id.json").writeBytes(bytes)
        }
    }

    File(out, "version_manifest_v2.json").writeText(gson.toJson(manifest))
}