package games.ramen

import com.reposilite.maven.api.DeployEvent
import com.reposilite.plugin.api.Facade
import com.reposilite.plugin.api.Plugin
import com.reposilite.plugin.api.ReposiliteDisposeEvent
import com.reposilite.plugin.api.ReposiliteInitializeEvent
import com.reposilite.plugin.api.ReposilitePlugin
import com.reposilite.plugin.event
import com.reposilite.web.api.ReposiliteRoute
import com.reposilite.web.api.ReposiliteRoutes
import com.reposilite.web.api.RoutingSetupEvent
import com.reposilite.web.routing.RouteMethod
import io.javalin.http.HandlerType

@Plugin(name = "mod-manifest-builder")
class ModManifestBuilderPlugin : ReposilitePlugin() {

    override fun initialize(): Facade? {
        val webhook = ManifestWebhook { message ->
            logger.info(message)
        }

        event { _: ReposiliteInitializeEvent ->
            logger.info("ModManifestBuilder plugin loaded")
        }

        event { _: ReposiliteDisposeEvent ->
            webhook.close()
        }

        event { event: DeployEvent ->
            val repository = event.repository.name
            val artifactPath = event.gav.toString()

            logger.info(
                "Manifest: received deploy " +
                        "repository=$repository path=$artifactPath"
            )

            if (repository != "releases") {
                logger.info(
                    "Manifest: skipped repository '$repository'"
                )
            } else {
                val update = parseArtifactPath(
                    operation = "upsert",
                    artifactPath = artifactPath
                )

                if (update == null) {
                    logger.info(
                        "Manifest: skipped unmatched artifact '$artifactPath'"
                    )
                } else {
                    logger.info(
                        "Manifest: queueing update " +
                                "mod=${update.mod} " +
                                "minecraftVersion=${update.minecraftVersion} " +
                                "loader=${update.loader} " +
                                "channel=${update.channel} " +
                                "version=${update.version}"
                    )

                    webhook.enqueue(update)
                }
            }
        }

        event { event: RoutingSetupEvent ->
            event.registerRoutes(object : ReposiliteRoutes() {
                override val routes: Set<ReposiliteRoute<Any>> = routes(
                    ReposiliteRoute<Any>("/*", RouteMethod.AFTER) {
                        if (
                            ctx.method() == HandlerType.DELETE &&
                            ctx.status().code in 200..299
                        ) {
                            val requestPath = ctx.path()
                            val repositoryPrefix = "/releases/"

                            if (requestPath.startsWith(repositoryPrefix)) {
                                val artifactPath =
                                    requestPath.removePrefix(repositoryPrefix)

                                val update = parseArtifactPath(
                                    operation = "remove",
                                    artifactPath = artifactPath
                                )

                                if (update != null) {
                                    webhook.enqueue(update)
                                } else if (
                                    artifactPath.startsWith("games/ramen/") &&
                                    !artifactPath.substringAfterLast("/")
                                        .contains(".")
                                ) {
                                    logger.info(
                                        "Manifest: directory deletion is not " +
                                                "handled automatically: $requestPath"
                                    )
                                }
                            }
                        }
                    }
                )
            })
        }

        return null
    }

    private fun parseArtifactPath(
        operation: String,
        artifactPath: String
    ): ManifestUpdate? {
        // Expected:
        // games/ramen/{mod}/{version}/
        // {mod}-{version}-{loader}-{minecraftVersion}.jar

        val parts = artifactPath.trim('/').split('/')

        if (parts.size != 5) return null
        if (parts[0] != "games" || parts[1] != "ramen") return null

        val mod = parts[2]
        val version = parts[3]
        val filename = parts[4]

        if (!MOD_PATTERN.matches(mod)) return null
        if (!VERSION_PATTERN.matches(version)) return null

        val filenamePrefix = "$mod-$version-"

        if (!filename.startsWith(filenamePrefix)) return null
        if (!filename.endsWith(".jar")) return null

        val classifier = filename
            .removePrefix(filenamePrefix)
            .removeSuffix(".jar")

        val separator = classifier.indexOf('-')

        if (separator <= 0) return null

        val loader = classifier.substring(0, separator)
        val minecraftVersion = classifier.substring(separator + 1)

        if (loader !in SUPPORTED_LOADERS) return null

        // Only accept numeric Minecraft versions here.
        // This also excludes suffixes such as "-sources".
        if (!MINECRAFT_VERSION_PATTERN.matches(minecraftVersion)) {
            return null
        }

        val channel = when {
            version.contains(
                Regex("""-alpha(?:[.-].*)?$""", RegexOption.IGNORE_CASE)
            ) -> "alpha"

            version.contains(
                Regex("""-beta(?:[.-].*)?$""", RegexOption.IGNORE_CASE)
            ) -> "beta"

            RELEASE_VERSION_PATTERN.matches(version) -> "release"

            else -> {
                logger.info(
                    "Manifest: unsupported version channel: $version"
                )
                return null
            }
        }

        return ManifestUpdate(
            operation = operation,
            mod = mod,
            minecraftVersion = minecraftVersion,
            loader = loader,
            channel = channel,
            version = version
        )
    }

    private companion object {
        val SUPPORTED_LOADERS = setOf(
            "neoforge",
            "forge",
            "fabric",
            "quilt"
        )

        val MOD_PATTERN = Regex("""[a-zA-Z0-9_-]{1,128}""")

        val VERSION_PATTERN =
            Regex("""[a-zA-Z0-9._+-]{1,128}""")

        val MINECRAFT_VERSION_PATTERN =
            Regex("""[0-9]+(?:\.[0-9]+){1,3}""")

        val RELEASE_VERSION_PATTERN =
            Regex("""[0-9]+\.[0-9]+\.[0-9]+(?:\+[a-zA-Z0-9.-]+)?""")
    }
}