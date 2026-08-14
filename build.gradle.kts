plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "com.rohittp.reng"
    version = providers.gradleProperty("VERSION_NAME").get()
}
