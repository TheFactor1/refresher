plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thefactor1.taskskiller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thefactor1.taskskiller"
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "0.7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Screenshots (Roborazzi) only with -Pshots: ./gradlew testDebugUnitTest -Pshots
        // PNGs land in app/build/outputs/roborazzi/.
        unitTests.all {
            it.maxHeapSize = "3g"
            if (project.hasProperty("shots")) it.systemProperty("roborazzi.test.record", "true")
            else it.systemProperty("refresher.noShots", "true")
        }
    }

    lint {
        // The app deliberately uses privileged / restricted APIs behind runtime guards.
        disable += setOf("QueryAllPackagesPermission", "BatteryLife", "ProtectedPermissions")
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")

    // Shizuku: lets the app run privileged shell-level calls after a one-time
    // ADB handshake, with no root required.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // dadb: speaks the ADB wire protocol directly, so the app can reach the
    // box's own network-debugging port and start Shizuku without a computer.
    implementation("dev.mobile:dadb:2.0.0")

    // JVM tests: the status logic, and every screen rendered at TV size (Robolectric)
    // with screenshots (Roborazzi) and margin/alignment/focus checks.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
}
