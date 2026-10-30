package games.ramen

import com.reposilite.maven.Repository
import com.reposilite.storage.api.DocumentInfo
import com.reposilite.storage.api.DirectoryInfo
import com.reposilite.storage.api.FileType
import com.reposilite.storage.api.Location

class RepositoryManifestScanner(
    private val parser: ArtifactManifestParser
) {

    fun scan(repository: Repository): List<ManifestUpdate> {
        val root = Location.of("games/ramen")
        val builds = mutableListOf<ManifestUpdate>()

        fun visit(location: Location) {
            val details = repository.getFileDetails(location).fold(
                { it },
                { error ->
                    throw IllegalStateException(
                        "Cannot inspect ${repository.name}/$location: $error"
                    )
                }
            )

            when (details.type) {
                FileType.FILE -> {
                    parser.parse(
                        operation = "upsert",
                        artifactPath = location.toString()
                    )?.let { update ->
                        val sizeBytes =
                            (details as? DocumentInfo)?.contentLength
                                ?.takeIf { it >= 0L }

                        val uploadedAt = repository.getLastModifiedTime(
                            location
                        ).fold(
                            { it.toInstant().toString() },
                            { error ->
                                throw IllegalStateException(
                                    "Cannot inspect last modified time for " +
                                            "${repository.name}/$location: $error"
                                )
                            }
                        )

                        builds.add(
                            update.copy(
                                uploadedAt = uploadedAt,
                                sizeBytes = sizeBytes
                            )
                        )
                    }
                }

                FileType.DIRECTORY -> {
                    val directory = details as? DirectoryInfo
                        ?: throw IllegalStateException(
                            "Directory listing unavailable for " +
                                    "${repository.name}/$location"
                        )

                    for (child in directory.files) {
                        val name = child.name

                        // Child names must be single path segments.
                        check(
                            name.isNotEmpty() &&
                                    name != "." &&
                                    name != ".." &&
                                    '/' !in name &&
                                    '\\' !in name
                        ) {
                            "Invalid child name '$name' at $location"
                        }

                        visit(location.resolve(name))
                    }
                }
            }
        }

        // If the root is missing or unreadable, visit throws.
        // Do not interpret that as a successful empty repository.
        visit(root)

        return builds.distinctBy {
            listOf(
                it.mod,
                it.minecraftVersion,
                it.loader,
                it.channel,
                it.version
            )
        }
    }
}