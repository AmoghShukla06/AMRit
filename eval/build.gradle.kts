plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(libs.serialization.json)
    implementation(libs.sqlite.jdbc)
}
application { mainClass.set("org.amrit.eval.EvalKt") }
tasks.named<JavaExec>("run") { workingDir = rootDir }
