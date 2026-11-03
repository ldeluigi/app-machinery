import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Writes the app's identity (`app.name` and `app.id` in `gradle.properties`) as Kotlin constants for the shared code,
 * so that the name players see is written in one place for the screens, the Android launcher and the iPhone home
 * screen alike.
 */
@CacheableTask
abstract class GenerateAppInfo : DefaultTask() {
    /** The name the players see. */
    @get:Input
    abstract val appName: Property<String>

    /** The store id: the Android application id and the iOS bundle id. */
    @get:Input
    abstract val appId: Property<String>

    /** The package of the generated `AppInfo` object. */
    @get:Input
    abstract val packageName: Property<String>

    /** The generated Kotlin source directory. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDir.get().asFile.resolve(packageName.get().replace('.', '/'))
        directory.mkdirs()
        directory.resolve("AppInfo.kt").writeText(
            """
            |package ${packageName.get()}
            |
            |/** The app's identity, from `app.name` and `app.id` in gradle.properties. */
            |object AppInfo {
            |    /** The name the players see. */
            |    const val NAME = "${appName.get().escaped()}"
            |
            |    /** The store id: the Android application id and the iOS bundle id. */
            |    const val ID = "${appId.get().escaped()}"
            |}
            |
            """.trimMargin(),
        )
    }

    private fun String.escaped(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}
