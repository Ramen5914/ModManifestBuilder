package games.ramen

import com.reposilite.maven.MavenFacade
import com.reposilite.maven.api.DeployEvent
import com.reposilite.plugin.api.Facade
import com.reposilite.plugin.api.Plugin
import com.reposilite.plugin.api.ReposiliteDisposeEvent
import com.reposilite.plugin.api.ReposiliteInitializeEvent
import com.reposilite.plugin.api.ReposilitePlugin
import com.reposilite.plugin.api.ReposiliteStartedEvent
import com.reposilite.plugin.event
import com.reposilite.web.api.ReposiliteRoute
import com.reposilite.web.api.ReposiliteRoutes
import com.reposilite.web.api.RoutingSetupEvent
import com.reposilite.web.routing.RouteMethod
import io.javalin.http.HandlerType
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.ZoneId

@Plugin(name = "mod-manifest-builder")
class ModManifestBuilderPlugin : ReposilitePlugin() {

    override fun initialize(): Facade? {
        val parser = ArtifactManifestParser { message ->
            logger.info(message)
        }

        val scanner = RepositoryManifestScanner(parser)

        val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "mod-manifest-reconciliation").apply {
                isDaemon = true
            }
        }

        val webhook = ManifestWebhook { message ->
            logger.info(message)
        }

        event { _: ReposiliteStartedEvent ->
            val mavenFacade = extensions().facade<MavenFacade>()

            val zone = ZoneId.of(
                System.getenv("MOD_MANIFEST_TIMEZONE")
                    ?: "America/Los_Angeles"
            )

            fun runFullUpdate() {
                try {
                    val repository = mavenFacade.getRepositories()
                        .firstOrNull { it.name == "releases" }
                        ?: error("Repository 'releases' was not found")

                    logger.info("Manifest: starting full repository scan")

                    val builds = scanner.scan(repository)

                    logger.info(
                        "Manifest: full scan completed: " +
                                "${builds.size} builds across " +
                                "${builds.map { it.mod }.distinct().size} mods"
                    )

                    webhook.enqueueReconciliation(builds)
                } catch (error: Exception) {
                    logger.info(
                        "Manifest: full update failed:\n" +
                                error.stackTraceToString()
                    )
                }
            }

            fun scheduleNextMidnight() {
                if (scheduler.isShutdown) {
                    return
                }

                val now = ZonedDateTime.now(zone)

                val nextMidnight = now.toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(zone)

                val delayMillis = Duration.between(
                    now.toInstant(),
                    nextMidnight.toInstant()
                ).toMillis()

                logger.info(
                    "Manifest: next daily update scheduled for $nextMidnight"
                )

                try {
                    scheduler.schedule(
                        {
                            try {
                                runFullUpdate()
                            } finally {
                                scheduleNextMidnight()
                            }
                        },
                        delayMillis,
                        TimeUnit.MILLISECONDS
                    )
                } catch (error: java.util.concurrent.RejectedExecutionException) {
                    // Shutdown can occur between the check and scheduling.
                    if (!scheduler.isShutdown) {
                        throw error
                    }
                }
            }

            logger.info(
                "Manifest: startup update scheduled in 30 seconds"
            )

            scheduler.schedule(
                {
                    try {
                        runFullUpdate()
                    } finally {
                        scheduleNextMidnight()
                    }
                },
                30L,
                TimeUnit.SECONDS
            )
        }

        event { _: ReposiliteInitializeEvent ->
            logger.info("ModManifestBuilder plugin loaded")
        }

        event { _: ReposiliteDisposeEvent ->
            scheduler.shutdownNow()
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
                val update = parser.parse(
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

                                val update = parser.parse(
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
}