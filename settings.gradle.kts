pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HandHack"
include(":app")

/**
 * The project lives under a folder whose name contains non-ASCII characters
 * (`OneDrive\Документы`). Gradle passes task classpaths to worker JVMs through a
 * generated argument file, and that path gets mangled by the encoding used there,
 * which breaks unit-test execution. Keeping the *build* output on an ASCII-only
 * path removes the problem entirely without moving the sources.
 *
 * Override with the HANDHACK_BUILD_DIR environment variable if you want the
 * build output somewhere else.
 */
val asciiBuildRoot: File = System.getenv("HANDHACK_BUILD_DIR")
    ?.let(::File)
    ?: File(System.getProperty("user.home"), ".handhack-build")

val buildProjectName: String = rootProject.name

gradle.beforeProject {
    val relative = if (path == ":") "" else path.removePrefix(":").replace(':', '/')
    layout.buildDirectory.set(File(asciiBuildRoot, "$buildProjectName/${relative.ifEmpty { "root" }}"))
}
