// Czysty Kotlin/JVM: brak zależności od Androida wymusza kompilator (SPEC 2, reguła `domain/`).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Manifest rozkładu (SPEC 3.5); ta sama biblioteka co w karoo-ext.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
