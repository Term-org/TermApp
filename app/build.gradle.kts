plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

data class TermFlavor(
    val name: String,
    val appId: String,
    val label: String,
    val iconColor: String
)

val termFlavors = listOf(
    TermFlavor("termone", "app.term.one", "TermOne", "#0F172A"),
    TermFlavor("termtwo", "app.term.two", "TermTwo", "#14532D"),
    TermFlavor("termthree", "app.term.three", "TermThree", "#7C2D12"),
    TermFlavor("termfour", "app.term.four", "TermFour", "#4C1D95")
)

android {
    namespace = "app.term.one"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // Must stay at 28: Android 10+ blocks exec() from the app data directory
        // for apps targeting API 29 or higher (W^X), which would break bash and apt.
        targetSdk = 28
        versionCode = 1.1
        versionName = "0.1.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    flavorDimensions += "app"

    productFlavors {
        termFlavors.forEach { flavor ->
            create(flavor.name) {
                dimension = "app"
                applicationId = flavor.appId
                resValue("string", "app_name", flavor.label)
                resValue("string", "ime_name", "${flavor.label} Clipboard")
                resValue("color", "ic_launcher_background", flavor.iconColor)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}