// Pure Kotlin: normaliser, matcher, confidence scorer, rule engine, result text. No Android types, so the
// evaluation harness runs exactly the code the app runs.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
}
tasks.test {
    systemProperty("amrit.repo", rootDir.absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
}
