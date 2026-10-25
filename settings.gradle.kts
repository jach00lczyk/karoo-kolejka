pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext zbudowany lokalnie ze źródeł (tag 1.1.9): `./gradlew :lib:publishToMavenLocal`
        // w klonie hammerheadnav/karoo-ext. Pozwala budować bez tokena GitHub Packages.
        mavenLocal {
            content { includeGroup("io.hammerhead") }
        }
        // GitHub Packages zawsze wymaga uwierzytelnienia: gpr.user / gpr.key w ~/.gradle/gradle.properties
        // albo USERNAME / TOKEN w CI. Nigdy w repo.
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            content { includeGroup("io.hammerhead") }
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("USERNAME")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("TOKEN")).orNull
            }
        }
    }
}

rootProject.name = "karoo-kolejka"
include(":domain", ":app")
