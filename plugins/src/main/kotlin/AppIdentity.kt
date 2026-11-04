import org.gradle.api.Project

/** The app's identity, from `app.*` in gradle.properties: see there. */
internal data class AppIdentity(
    val name: String,
    val id: String,
    val codePackage: String,
    val locale: String,
)

internal val Project.app: AppIdentity
    get() {
        fun property(key: String) =
            checkNotNull(providers.gradleProperty("app.$key").orNull) { "Set app.$key in gradle.properties" }
        return AppIdentity(property("name"), property("id"), property("package"), property("locale"))
    }
