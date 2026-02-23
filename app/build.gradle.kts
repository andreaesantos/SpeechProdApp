plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {

    buildFeatures {
        buildConfig = true
        compose = true
    }

    namespace = "dev.andrea.speechprod"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.andrea.speechprod"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "task"
    productFlavors {
        create("wordrepetition") {
            dimension = "task"
            applicationId = "dev.andrea.wordrepetition"
            versionNameSuffix = "-wordrepetition"
            resValue("string", "app_name", "Word Repetition Task")
            resValue("string", "video_assets_dir", "WR_mp4")
        }
        create("nonwordrepetition") {
            dimension = "task"
            applicationId = "dev.andrea.nonwordrepetition"
            versionNameSuffix = "-nonwordrepetition"
            resValue("string", "app_name", "Nonword Repetition Task")
            resValue("string", "video_assets_dir", "NW_mp4")

        }
        create("picturenaming") {
            dimension = "task"
            applicationId = "dev.andrea.picturenaming"
            versionNameSuffix = "-picnaming"
            resValue("string", "app_name", "Picture Naming Task")
            resValue("string", "video_assets_dir", "PN_mp4")

        }
        create("auditorynaming") {
            dimension = "task"
            applicationId = "dev.andrea.auditortynaming"
            versionNameSuffix = "-audnaming"
            resValue("string", "app_name", "Auditory Naming Task")
            resValue("string", "video_assets_dir", "AN_mp4")

        }
        create("conversational") {
            dimension = "task"
            applicationId = "dev.andrea.conversational"
            versionNameSuffix = "-convo"
            resValue("string", "app_name", "Conversational Task")
            resValue("string", "video_assets_dir", "NC_mp4")

        }
        create("lepetitprince") {
            dimension = "task"
            applicationId = "dev.andrea.lepetitprince"
            versionNameSuffix = "lepetitprince"
            resValue("string", "app_name", "Le Petit Prince Conversational Task")
            resValue("string", "video_assets_dir", "Le_Petit_Prince_scenes")

        }
    }

    lint {
        baseline = file("lint-baseline.xml")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation(libs.androidx.media3.common.ktx)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.10.3")
    testImplementation("org.mockito:mockito-core:5.0.0")
    testImplementation("org.mockito:mockito-android:5.0.0")
    testImplementation("io.mockk:mockk:1.13.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

