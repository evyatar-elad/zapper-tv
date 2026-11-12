plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "il.zapper.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "il.zapper.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    // חתימה קבועה (כדי שגרסה חדשה תותקן מעל הקודמת): המפתח מגיע מסודות של GitHub,
    // לא נשמר בריפו. בלי משתני הסביבה (בנייה מקומית) - חתימת debug רגילה.
    val ksFile = System.getenv("KEYSTORE_FILE")
    val ksPass = System.getenv("KEYSTORE_PASS")
    signingConfigs {
        if (ksFile != null && ksPass != null) {
            create("shared") {
                storeFile = file(ksFile)
                storePassword = ksPass
                keyAlias = "zapper"
                keyPassword = ksPass
            }
        }
    }
    val signing = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
        }
        debug {
            signingConfig = signing
        }
    }

    // רשימת הערוצים נמצאת ב-lineup/channels.json ונארזת גם בתוך האפליקציה (גיבוי)
    sourceSets["main"].assets.srcDirs("src/main/assets", "../lineup")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
