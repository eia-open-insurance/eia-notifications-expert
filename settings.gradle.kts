rootProject.name = "eia-notifications-expert"

includeBuild("../eia-platform") {
    dependencySubstitution {
        substitute(module("com.eia:libs")).using(project(":libs"))
    }
}
