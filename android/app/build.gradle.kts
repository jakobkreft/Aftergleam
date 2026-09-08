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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Reproducible builds: AGP otherwise bakes the git hash into META-INF, so no
            // two checkouts of the same commit produce identical bytes. In AGP 9 this is
            // a BuildType property, not an `android {}` one.
            vcsInfo { include = false }
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 ships Kotlin; kotlinOptions {} no longer exists.
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    // Keep the dependency blob out of the APK too; it also varies between builds.
    dependenciesInfo { includeInApk = false }

    testOptions {
        unitTests.all {
            it.testLogging { showStandardStreams = true }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
}
