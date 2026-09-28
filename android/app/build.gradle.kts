plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "si.jakobkreft.aftergleam"
    compileSdk = 37

    defaultConfig {
        applicationId = "si.jakobkreft.aftergleam"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"
    }

    /**
     * Release signing, read from the environment or from ~/.gradle/gradle.properties.
     *
     * Never from the repository. With nothing configured the release build is simply left
     * unsigned, which is what F-Droid wants: it builds from source and signs with its own key.
     *
     *   AFTERGLEAM_KEYSTORE           or  aftergleam.keystore
     *   AFTERGLEAM_KEYSTORE_PASSWORD  or  aftergleam.keystorePassword
     *   AFTERGLEAM_KEY_ALIAS          or  aftergleam.keyAlias
     *   AFTERGLEAM_KEY_PASSWORD       or  aftergleam.keyPassword
     */
    fun secret(env: String, prop: String): String? =
        System.getenv(env) ?: providers.gradleProperty(prop).orNull

    // F-Droid's build server deletes this block, and the `signingConfig =` line below, before
    // it builds. Its clean-up only recognises that line when the value has no spaces in it, so
    // it must stay exactly `signingConfigs.findByName("release")`. A longer expression was left
    // in place and then failed on the missing config, which broke the 1.0.0 build there.
    signingConfigs {
        secret("AFTERGLEAM_KEYSTORE", "aftergleam.keystore")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = secret("AFTERGLEAM_KEYSTORE_PASSWORD", "aftergleam.keystorePassword")
                keyAlias = secret("AFTERGLEAM_KEY_ALIAS", "aftergleam.keyAlias")
                keyPassword = secret("AFTERGLEAM_KEY_PASSWORD", "aftergleam.keyPassword")
                // v1 is not needed at minSdk 26, and its per-entry signatures would change the
                // archive that F-Droid compares against its own build of the same commit.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Reproducible builds: AGP otherwise bakes the git hash into META-INF, so no
            // two checkouts of the same commit produce identical bytes. In AGP 9 this is
            // a BuildType property, not an `android {}` one.
            vcsInfo { include = false }
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 ships Kotlin; kotlinOptions {} no longer exists.
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    // Keep the dependency blob out of the APK too; it also varies between builds.
    dependenciesInfo { includeInApk = false }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging { showStandardStreams = true }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // A virtual clock, so pacing can be tested without waiting three real seconds a few times.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Robolectric for anything touching framework classes. Plain android.jar stubs return
    // nulls or throw "not mocked", so org.json and SQLite cannot be tested without it.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Icons come from the material (not material3) package.
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
}
