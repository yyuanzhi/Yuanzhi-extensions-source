import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Meow Fansub"
    qname = "MeowblsFactory"
    versionCode = 1
    versionId = 1
    contentWarning = ContentWarning.SAFE
    sourceNames = listOf("Meow Fansub")
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
}
