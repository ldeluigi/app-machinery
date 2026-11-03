import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * Generates files with a Java program, like an app's own data packer, cached like any build step: it runs again only
 * when its [sources] or the program change, and a build cache can bring its [outputDir] back.
 *
 * ```
 * val pack = tasks.register<GenerateFiles>("packData") {
 *     classpath = packer            // a configuration holding the program
 *     mainClass = "my.app.PackerKt"
 *     sources.from(layout.projectDirectory.dir("data"))
 *     outputDir = layout.buildDirectory.dir("generated/data")
 *     argumentProviders += CommandLineArgumentProvider { listOf(outputDir.get().asFile.path) }
 * }
 * ```
 */
@CacheableTask
abstract class GenerateFiles : JavaExec() {
    /** The files the program reads. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Where the program writes. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}
