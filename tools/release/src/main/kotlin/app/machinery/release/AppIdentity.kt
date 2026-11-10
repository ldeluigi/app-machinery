package app.machinery.release

import java.io.File
import java.util.Properties

/**
 * The app's name and id: `app.name` and `app.id` in its gradle.properties, where the machinery's plugins read them too.
 * Read as Gradle reads that file (ISO-8859-1, with the escapes of Java properties files), so that the release and the
 * stores see what the build sees, and a rename needs nothing but that file.
 */
data class AppIdentity(
    val name: String,
    val id: String,
) {
    companion object {
        /** The identity in [file], the app's gradle.properties: by default the one in the working directory. */
        fun of(file: File = File("gradle.properties")): AppIdentity {
            check(file.isFile) { "No ${file.path}: run this in the app's folder" }
            val properties = Properties().apply { file.inputStream().use(::load) }

            fun property(key: String): String =
                checkNotNull(properties.getProperty("app.$key")?.takeIf { it.isNotBlank() }) {
                    "Set app.$key in ${file.path}"
                }
            return AppIdentity(property("name"), property("id"))
        }
    }
}
