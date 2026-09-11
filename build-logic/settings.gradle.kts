dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files(repositoryCatalog()))
        }
    }
}

rootProject.name = "build-logic"

/**
 * The version catalog, found by walking up rather than by counting directories.
 *
 * It belongs to the repository, not to this build, and this build does not sit at
 * a fixed depth below it. In a consumer's install it is `<root>/build-logic`, one
 * level down. In the framework's own repository it is `<root>/core/build-logic`,
 * two levels down, because `core/` mirrors what ships. A single `../gradle` names
 * one of those layouts and silently misses the other — which is how the framework
 * came to carry two copies of its own convention plugins, one of them the copy
 * that was actually built.
 *
 * Absent, this fails here and says so. build-logic resolves every plugin version
 * from the catalog, so a build configured without one produces convention plugins
 * that compile against nothing.
 */
fun repositoryCatalog(): File =
    generateSequence(settingsDir.parentFile) { it.parentFile }
        .map { File(it, "gradle/libs.versions.toml") }
        .firstOrNull { it.isFile }
        ?: error(
            "no gradle/libs.versions.toml in any directory above $settingsDir. " +
                "build-logic resolves every plugin version from the repository's " +
                "version catalog and cannot be configured without it.",
        )
