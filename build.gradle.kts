plugins {
    alias(libs.plugins.android.application) apply false
    // only pins the kotlin compiler version AGP's built-in kotlin uses (same as editotsu); not applied
    alias(libs.plugins.kotlin.serialization) apply false
}
