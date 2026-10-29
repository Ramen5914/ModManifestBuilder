package games.ramen

class ArtifactManifestParser(
    private val log: (String) -> Unit
) {

    fun parse(
        operation: String,
        artifactPath: String
    ): ManifestUpdate? {
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

        if (!MINECRAFT_VERSION_PATTERN.matches(minecraftVersion)) {
            return null
        }

        val channel = when {
            ALPHA_PATTERN.containsMatchIn(version) -> "alpha"
            BETA_PATTERN.containsMatchIn(version) -> "beta"
            RELEASE_VERSION_PATTERN.matches(version) -> "release"

            else -> {
                log("Manifest: unsupported version channel: $version")
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
            "neoforge", "forge", "fabric", "quilt"
        )

        val MOD_PATTERN = Regex("""[a-zA-Z0-9_-]{1,128}""")

        val VERSION_PATTERN =
            Regex("""[a-zA-Z0-9._+-]{1,128}""")

        val MINECRAFT_VERSION_PATTERN =
            Regex("""[0-9]+(?:\.[0-9]+){1,3}""")

        val ALPHA_PATTERN =
            Regex("""-alpha(?:[.-].*)?$""", RegexOption.IGNORE_CASE)

        val BETA_PATTERN =
            Regex("""-beta(?:[.-].*)?$""", RegexOption.IGNORE_CASE)

        val RELEASE_VERSION_PATTERN =
            Regex("""[0-9]+\.[0-9]+\.[0-9]+(?:\+[a-zA-Z0-9.-]+)?""")
    }
}