plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 has built-in Kotlin; declaring KGP here pins the Kotlin version it uses
    // (LiteRT-LM is compiled against Kotlin 2.4 metadata, so we need a recent compiler).
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
