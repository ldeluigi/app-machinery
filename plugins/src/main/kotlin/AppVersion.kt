/**
 * The app's version name and Play `versionCode`, derived from a semantic version such as `1.4.2` or
 * `1.4.3-dev05+3f2a1c9`.
 *
 * `versionCode = major * 1_000_000 + minor * 1_000 + patch`: deterministic and always increasing between releases.
 */
data class AppVersion(
    val name: String,
    val code: Int,
    /** `major.minor.patch` only, as the App Store wants it. */
    val release: String,
) {
    companion object {
        private const val MAJOR_FACTOR = 1_000_000
        private const val MINOR_FACTOR = 1_000
        private val semver = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

        /** Parses [version]; throws if it is not a semantic version or does not fit a Play `versionCode`. */
        fun parse(version: String): AppVersion {
            val match = requireNotNull(semver.matchEntire(version.trim())) { "Not a semantic version: '$version'" }
            val (major, minor, patch) = match.destructured.toList().map(String::toInt)
            require(minor < MINOR_FACTOR && patch < MINOR_FACTOR) {
                "Minor and patch must stay below 1000 to fit the versionCode scheme: '$version'"
            }
            return AppVersion(
                name = version.trim().removePrefix("v"),
                code = major * MAJOR_FACTOR + minor * MINOR_FACTOR + patch,
                release = "$major.$minor.$patch",
            )
        }
    }
}
