import javax.inject.Inject
import org.gradle.process.ExecOperations

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

// Builds the Rust workspace in src/main/assets with cargo-ndk and collects the termcli binary
// of every ABI into bin-<abi>/termcli.
abstract class BuildCliTask @Inject constructor(
    private val operations: ExecOperations
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:Internal
    abstract val cargoTarget: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val target = cargoTarget.get().asFile
        operations.exec {
            workingDir = workspace.get().asFile
            environment("CARGO_TARGET_DIR", target.absolutePath)
            commandLine(
                "cargo", "ndk",
                "-t", "arm64-v8a",
                "-t", "x86_64",
                "-P", "26",
                "build", "--release"
            )
        }

        val out = outputDir.get().asFile
        out.deleteRecursively()
        val triples = listOf("aarch64-linux-android" to "arm64-v8a", "x86_64-linux-android" to "x86_64")
        for ((triple, abi) in triples) {
            val binary = File(target, "$triple/release/termcli")
            check(binary.isFile) { "cargo did not produce $binary" }
            val folder = File(out, "bin-$abi")
            folder.mkdirs()
            binary.copyTo(File(folder, "termcli"), overwrite = true)
        }
    }
}

android {
    namespace = "app.term.one"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // Must stay at 28: Android 10+ blocks exec() from the app data directory
        // for apps targeting API 29 or higher (W^X), which would break bash and apt.
        targetSdk = 28
        versionCode = 4
        versionName = "0.1.4"

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

val buildCli = tasks.register<BuildCliTask>("buildCli") {
    sources.from(fileTree("src/main/assets") { include("**/*.rs", "**/Cargo.toml") })
    workspace.set(layout.projectDirectory.dir("src/main/assets"))
    cargoTarget.set(layout.buildDirectory.dir("cli/target"))
    outputDir.set(layout.buildDirectory.dir("generated/cli-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildCli, BuildCliTask::outputDir)
    }
}

// The Rust sources live in src/main/assets next to the other assets. They are only needed to
// build termcli, so they are removed from the merged assets and never end up in the APK.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    doLast {
        outputs.files.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "rs" || it.name == "Cargo.toml" || it.name == "Cargo.lock") }
                .forEach { it.delete() }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.preference.ktx)
}