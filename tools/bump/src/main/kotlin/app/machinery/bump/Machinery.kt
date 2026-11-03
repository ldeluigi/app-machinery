package app.machinery.bump

import java.io.File

/** One tool version kept in a plain file of the repository. */
data class Bump(
    val name: String,
    val from: String,
    val to: String,
) {
    val changed: Boolean get() = from != to
}

/** The latest versions available upstream. `null` means "could not be determined": leave the current one. */
data class Latest(
    val jdkLts: Int?,
    val androidApi: Int?,
)

/**
 * Applies [latest] to the version files under [root] that it has: `.java-version` (the machinery's and every app's),
 * and the Android SDK levels in `gradle/libs.versions.toml` (the machinery's). Versions only ever move forward.
 */
class Machinery(
    private val root: File,
) {
    /** Bumps the files and returns what changed (and what did not). */
    fun apply(latest: Latest): List<Bump> =
        listOfNotNull(
            bumpFile(".java-version", "JDK LTS", latest.jdkLts?.toString()),
            bumpCatalog("android-compileSdk", "compileSdk", latest.androidApi),
            bumpCatalog("android-targetSdk", "targetSdk", latest.androidApi),
        )

    private fun bumpFile(
        path: String,
        name: String,
        latest: String?,
    ): Bump? {
        val file = File(root, path).takeIf { it.exists() } ?: return null
        val current = file.readText().trim()
        val next = latest?.takeIf { isNewer(it, current) } ?: current
        if (next != current) file.writeText("$next\n")
        return Bump(name, current, next)
    }

    private fun bumpCatalog(
        key: String,
        name: String,
        latest: Int?,
    ): Bump? {
        val file = File(root, CATALOG)
        val text = if (file.exists()) file.readText() else ""
        return catalogVersion(text, key)?.let { current ->
            val next = latest?.takeIf { it > current.toInt() }?.toString() ?: current
            if (next != current) file.writeText(setCatalogVersion(text, key, next))
            Bump(name, current, next)
        }
    }

    companion object {
        private const val CATALOG = "gradle/libs.versions.toml"

        /** Whether version [candidate] is newer than [current] (numeric comparison, `v` prefixes ignored). */
        fun isNewer(
            candidate: String,
            current: String,
        ): Boolean {
            fun parts(version: String) = version.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            val diff =
                (0 until maxOf(a.size, b.size)).map { (a.getOrElse(it) { 0 }) - (b.getOrElse(it) { 0 }) }.firstOrNull {
                    it !=
                        0
                }
            return (diff ?: 0) > 0
        }

        /** The value of `key = "value"` in a TOML version catalog. */
        fun catalogVersion(
            toml: String,
            key: String,
        ): String? = Regex("""(?m)^${Regex.escape(key)}\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)

        /** [toml] with `key` set to [value]. */
        fun setCatalogVersion(
            toml: String,
            key: String,
            value: String,
        ): String =
            Regex("""(?m)^(${Regex.escape(key)}\s*=\s*)"[^"]+"""").replace(toml) { "${it.groupValues[1]}\"$value\"" }

        /** The highest stable Android API level in Google's SDK repository index. */
        fun latestAndroidApi(repositoryXml: String): Int? =
            Regex("""path="platforms;android-(\d+)(?:\.\d+)?"""")
                .findAll(repositoryXml)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull()

        /** `most_recent_lts` from the Adoptium API. */
        fun latestJdkLts(availableReleasesJson: String): Int? =
            Regex(""""most_recent_lts"\s*:\s*(\d+)""")
                .find(availableReleasesJson)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
    }
}
