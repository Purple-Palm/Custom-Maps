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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // Mapbox artifacts are served publicly; no download token is needed.
        // Every com.mapbox group resolves only from here, so lookups never hit other repositories.
        exclusiveContent {
            forRepository {
                maven("https://api.mapbox.com/downloads/v2/releases/maven") { name = "Mapbox" }
            }
            filter { includeGroupByRegex("com\\.mapbox(\\..+)?") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "CustomMaps"
