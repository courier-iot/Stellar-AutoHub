package com.stellar.autohub.editor

import com.stellar.autohub.upload.RawFile

/** Starter Android project: Compose app + Stellar look + the GitHub build/release workflow. */
object Templates {

    fun repoNameFor(appName: String) = appName.trim().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')

    fun packageFor(appName: String) = "com.stellar." + appName.lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { "app" }

    fun validPackage(p: String) = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(p)

    fun files(appName: String, pkg: String, repoName: String): List<RawFile> {
        val path = pkg.replace('.', '/')
        val apkName = repoName.ifBlank { "app" }
        val esc = appName.replace("\\", "\\\\").replace("\"", "\\\"")
        val xmlName = appName.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'")
        fun f(p: String, t: String) = RawFile(p, t.trimIndent().trimStart('\n').toByteArray())
        return listOf(
            f("settings.gradle.kts", """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "$apkName"
                include(":app")
            """),
            f("build.gradle.kts", """
                plugins {
                    id("com.android.application") version "8.7.3" apply false
                    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
                    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
                }
            """),
            f("gradle.properties", """
                org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
                android.useAndroidX=true
                android.nonTransitiveRClass=true
                kotlin.code.style=official
            """),
            f(".gitignore", """
                .gradle/
                build/
                app/build/
                local.properties
                .idea/
                *.iml
                *.jks
                *.keystore
                .DS_Store
            """),
            f("app/build.gradle.kts", """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                    id("org.jetbrains.kotlin.plugin.compose")
                }

                android {
                    namespace = "$pkg"
                    compileSdk = 35

                    defaultConfig {
                        applicationId = "$pkg"
                        minSdk = 26
                        targetSdk = 35
                        versionCode = 1
                        versionName = "1.0.0"
                    }

                    signingConfigs {
                        create("release") {
                            val storePath = System.getenv("SIGNING_STORE_FILE")
                            if (!storePath.isNullOrBlank()) {
                                storeFile = file(storePath)
                                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                            }
                        }
                    }

                    buildTypes {
                        release {
                            isMinifyEnabled = false
                            val hasKey = !System.getenv("SIGNING_STORE_FILE").isNullOrBlank()
                            signingConfig = if (hasKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
                        }
                    }
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                    kotlinOptions { jvmTarget = "17" }
                    buildFeatures { compose = true }
                }

                dependencies {
                    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
                    implementation("androidx.compose.ui:ui")
                    implementation("androidx.compose.material3:material3")
                    implementation("androidx.core:core-ktx:1.15.0")
                    implementation("androidx.activity:activity-compose:1.9.3")
                }
            """),
            f("app/src/main/AndroidManifest.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application
                        android:icon="@mipmap/ic_launcher"
                        android:roundIcon="@mipmap/ic_launcher"
                        android:label="@string/app_name"
                        android:theme="@style/Theme.App">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """),
            f("app/src/main/java/$path/MainActivity.kt", """
                package $pkg

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import androidx.activity.compose.setContent
                import androidx.compose.foundation.background
                import androidx.compose.foundation.layout.*
                import androidx.compose.material3.*
                import androidx.compose.runtime.*
                import androidx.compose.ui.Alignment
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.graphics.Brush
                import androidx.compose.ui.graphics.Color
                import androidx.compose.ui.text.font.FontWeight
                import androidx.compose.ui.unit.dp
                import androidx.compose.ui.unit.sp

                private val Space = Color(0xFF070A14)
                private val Cyan = Color(0xFF34E7FF)
                private val TextColor = Color(0xFFE9ECFF)

                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContent {
                            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Space, onBackground = TextColor)) {
                                var taps by remember { mutableIntStateOf(0) }
                                Box(
                                    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A0F24), Space, Color(0xFF0C0718)))),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("$esc", color = TextColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(8.dp))
                                        Text("Built with Stellar AutoHub", color = TextColor.copy(alpha = 0.7f))
                                        Spacer(Modifier.height(24.dp))
                                        Button(onClick = { taps++ }) { Text(if (taps == 0) "Launch" else "Launched ${'$'}taps×") }
                                    }
                                }
                            }
                        }
                    }
                }
            """),
            f("app/src/main/res/values/strings.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">$xmlName</string>
                </resources>
            """),
            f("app/src/main/res/values/themes.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <style name="Theme.App" parent="android:Theme.Material.NoActionBar">
                        <item name="android:windowBackground">@color/space</item>
                        <item name="android:statusBarColor">@color/space</item>
                        <item name="android:navigationBarColor">@color/space</item>
                    </style>
                    <color name="space">#FF070A14</color>
                    <color name="icon_bg">#FF0B1022</color>
                </resources>
            """),
            f("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                    <background android:drawable="@color/icon_bg" />
                    <foreground android:drawable="@drawable/ic_launcher_foreground" />
                    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
                </adaptive-icon>
            """),
            f("app/src/main/res/drawable/ic_launcher_foreground.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <vector xmlns:android="http://schemas.android.com/apk/res/android"
                    android:width="108dp" android:height="108dp"
                    android:viewportWidth="108" android:viewportHeight="108">
                    <path android:pathData="M54,30 A24,24 0 1,1 53.99,30 Z" android:strokeWidth="3"
                        android:strokeColor="#FF7C4DFF" android:fillColor="#00000000" />
                    <path android:fillColor="#FF34E7FF" android:pathData="M54,38 L60,50 L72,54 L60,58 L54,70 L48,58 L36,54 L48,50 Z" />
                </vector>
            """),
            f(".github/workflows/build.yml", workflow(apkName)),
            f("README.md", """
                # $appName

                Android app made with **Stellar AutoHub**.

                - Every push builds an APK (any branch). Pushes to `main` also publish a release tagged `vX.Y.Z` from `versionName`.
                - Signing uses the repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
                  Without them the build uses a temporary debug key.
            """),
        )
    }

    /** Build on every branch (plus manual runs); publish releases only from main. */
    fun workflow(apkName: String): String = """
        name: Build APK

        on:
          push:
            branches: ['**']
          workflow_dispatch:

        permissions:
          contents: write

        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - name: Checkout
                uses: actions/checkout@v5

              - name: Set up JDK 17
                uses: actions/setup-java@v5
                with:
                  distribution: temurin
                  java-version: '17'

              - name: Set up Gradle
                uses: gradle/actions/setup-gradle@v4
                with:
                  gradle-version: '8.11.1'

              - name: Prepare signing key
                id: sign
                env:
                  KEYSTORE_BASE64: ${'$'}{{ secrets.KEYSTORE_BASE64 }}
                  KEYSTORE_PASSWORD: ${'$'}{{ secrets.KEYSTORE_PASSWORD }}
                  KEY_ALIAS: ${'$'}{{ secrets.KEY_ALIAS }}
                  KEY_PASSWORD: ${'$'}{{ secrets.KEY_PASSWORD }}
                run: |
                  if [ -n "${'$'}KEYSTORE_BASE64" ] && [ -n "${'$'}KEYSTORE_PASSWORD" ] && [ -n "${'$'}KEY_ALIAS" ] && [ -n "${'$'}KEY_PASSWORD" ]; then
                    echo "${'$'}KEYSTORE_BASE64" | base64 -d > "${'$'}RUNNER_TEMP/release.jks"
                    echo "store=${'$'}RUNNER_TEMP/release.jks" >> "${'$'}GITHUB_OUTPUT"
                  else
                    echo "::warning::Signing secrets not set, so this build uses a temporary debug key. Apply a key in Stellar AutoHub > Secrets."
                    echo "store=" >> "${'$'}GITHUB_OUTPUT"
                  fi

              - name: Build release APK
                env:
                  SIGNING_STORE_FILE: ${'$'}{{ steps.sign.outputs.store }}
                  SIGNING_STORE_PASSWORD: ${'$'}{{ secrets.KEYSTORE_PASSWORD }}
                  SIGNING_KEY_ALIAS: ${'$'}{{ secrets.KEY_ALIAS }}
                  SIGNING_KEY_PASSWORD: ${'$'}{{ secrets.KEY_PASSWORD }}
                run: gradle assembleRelease --stacktrace

              - name: Read version
                id: ver
                run: |
                  VER=${'$'}(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
                  if [ -z "${'$'}VER" ]; then echo "::error::versionName not found in app/build.gradle.kts"; exit 1; fi
                  echo "version=${'$'}VER" >> "${'$'}GITHUB_OUTPUT"
                  cp app/build/outputs/apk/release/app-release.apk "$apkName-v${'$'}VER.apk"

              - name: Upload artifact
                uses: actions/upload-artifact@v4
                with:
                  name: $apkName-v${'$'}{{ steps.ver.outputs.version }}
                  path: $apkName-v${'$'}{{ steps.ver.outputs.version }}.apk

              - name: Publish release
                if: github.ref == 'refs/heads/main'
                env:
                  GH_TOKEN: ${'$'}{{ github.token }}
                  VER: ${'$'}{{ steps.ver.outputs.version }}
                run: |
                  TAG="v${'$'}VER"
                  if gh release view "${'$'}TAG" >/dev/null 2>&1; then
                    echo "::warning::Release ${'$'}TAG already exists. Bump versionName to publish a new release."
                  else
                    gh release create "${'$'}TAG" "$apkName-${'$'}TAG.apk" --title "${'$'}TAG" --notes "" --target "${'$'}GITHUB_SHA"
                  fi
    """.trimIndent() + "\n"

    // ---------------- Electron (Windows desktop) ----------------

    fun npmName(appName: String) = appName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "app" }

    fun electronFiles(appName: String, appId: String, repoName: String): List<RawFile> {
        val esc = appName.replace("\\", "\\\\").replace("\"", "\\\"")
        val html = appName.replace("&", "&amp;").replace("<", "&lt;")
        fun f(p: String, t: String) = RawFile(p, t.trimIndent().trimStart('\n').toByteArray())
        return listOf(
            f("package.json", """
                {
                  "name": "${npmName(appName)}",
                  "productName": "$esc",
                  "version": "1.0.0",
                  "description": "$esc, made with Stellar AutoHub",
                  "author": "Stellar",
                  "main": "main.js",
                  "scripts": {
                    "start": "electron .",
                    "dist": "electron-builder --win --publish never"
                  },
                  "build": {
                    "appId": "$appId",
                    "productName": "$esc",
                    "directories": { "output": "dist", "buildResources": "build" },
                    "win": { "target": ["nsis", "portable"] },
                    "nsis": { "oneClick": false, "allowToChangeInstallationDirectory": true }
                  },
                  "devDependencies": {
                    "electron": "^33.2.1",
                    "electron-builder": "^25.1.8"
                  }
                }
            """),
            f("main.js", """
                // Main process: creates the window. Runs in Node.js.
                const { app, BrowserWindow, Menu } = require('electron');
                const path = require('path');

                function createWindow() {
                  const win = new BrowserWindow({
                    width: 1100,
                    height: 720,
                    backgroundColor: '#070A14',
                    title: '$esc',
                    webPreferences: {
                      preload: path.join(__dirname, 'preload.js'),
                      contextIsolation: true,
                      nodeIntegration: false,
                    },
                  });
                  Menu.setApplicationMenu(null);
                  win.loadFile('index.html');
                }

                app.whenReady().then(() => {
                  createWindow();
                  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
                });

                app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
            """),
            f("preload.js", """
                // Safely exposes a few values to the page (no full Node access in the window).
                const { contextBridge } = require('electron');

                contextBridge.exposeInMainWorld('stellar', {
                  versions: { electron: process.versions.electron, node: process.versions.node, chrome: process.versions.chrome },
                });
            """),
            f("index.html", """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8" />
                  <meta http-equiv="Content-Security-Policy" content="default-src 'self'; style-src 'self' 'unsafe-inline'" />
                  <title>$html</title>
                  <link rel="stylesheet" href="styles.css" />
                </head>
                <body>
                  <main>
                    <h1>$html</h1>
                    <p>Built with Stellar AutoHub</p>
                    <button id="launch">Launch</button>
                    <p id="info" class="dim"></p>
                  </main>
                  <script src="renderer.js"></script>
                </body>
                </html>
            """),
            f("renderer.js", """
                // Runs in the window. Talk to Node through window.stellar (see preload.js).
                let taps = 0;
                document.getElementById('launch').addEventListener('click', (e) => {
                  taps++;
                  e.target.textContent = 'Launched ' + taps + '×';
                });
                const v = window.stellar.versions;
                document.getElementById('info').textContent = 'Electron ' + v.electron + ' · Node ' + v.node;
            """),
            f("styles.css", """
                html, body { margin: 0; height: 100%; }
                body {
                  background: linear-gradient(#0A0F24, #070A14, #0C0718);
                  color: #E9ECFF;
                  font-family: 'Segoe UI', system-ui, sans-serif;
                  display: flex; align-items: center; justify-content: center;
                }
                main { text-align: center; }
                h1 { font-size: 34px; margin: 0 0 8px; }
                .dim, p { color: #A3ACD6; }
                button {
                  margin-top: 20px; padding: 12px 28px; border-radius: 24px; border: none;
                  background: #34E7FF; color: #00151A; font-size: 16px; font-weight: 600; cursor: pointer;
                }
                button:hover { filter: brightness(1.1); }
            """),
            f(".gitignore", """
                node_modules/
                dist/
                out/
                *.log
                .DS_Store
            """),
            f(".github/workflows/build.yml", electronWorkflow(repoName)),
            f("README.md", """
                # $appName

                Windows desktop app (Electron) made with **Stellar AutoHub**.

                - Every push builds the app on GitHub's Windows machines. Pushes to `main` also publish a release tagged `vX.Y.Z`
                  from the `version` in `package.json`, with an installer (`Setup`) and a portable `.exe`.
                - Run it locally: `npm install` then `npm start`.
                - Windows SmartScreen may warn the first time because the app isn't code-signed. Click **More info → Run anyway**.
            """),
        )
    }

    /** Windows build for Electron (electron-builder or Electron Forge). Releases only from main, tagged vX.Y.Z. */
    fun electronWorkflow(name: String): String = """
        name: Build Windows app

        on:
          push:
            branches: ['**']
          workflow_dispatch:

        permissions:
          contents: write

        jobs:
          build:
            runs-on: windows-latest
            defaults:
              run:
                shell: bash
            steps:
              - name: Checkout
                uses: actions/checkout@v5

              - name: Set up Node.js
                uses: actions/setup-node@v4
                with:
                  node-version: '20'

              - name: Install dependencies
                run: |
                  if [ -f package-lock.json ]; then npm ci; else npm install; fi

              - name: Build Windows app
                env:
                  CSC_IDENTITY_AUTO_DISCOVERY: 'false'
                run: |
                  if grep -q '"@electron-forge/cli"' package.json; then
                    npx electron-forge make --platform win32
                  else
                    npx --yes electron-builder --win --publish never
                  fi

              - name: Read version
                id: ver
                run: echo "version=${'$'}(node -p "require('./package.json').version")" >> "${'$'}GITHUB_OUTPUT"

              - name: Collect files
                run: |
                  shopt -s nullglob globstar
                  mkdir -p release
                  for f in dist/*.exe dist/*.msi dist/*.zip out/make/**/*.exe out/make/**/*.msi out/make/**/*.zip; do cp "${'$'}f" release/; done
                  ls -la release
                  if [ -z "${'$'}(ls -A release)" ]; then
                    echo "::error::No .exe, .msi or .zip found in dist/ or out/make/. Check the 'Build Windows app' step output."
                    exit 1
                  fi

              - name: Upload artifact
                uses: actions/upload-artifact@v4
                with:
                  name: $name-v${'$'}{{ steps.ver.outputs.version }}
                  path: release/*

              - name: Publish release
                if: github.ref == 'refs/heads/main'
                env:
                  GH_TOKEN: ${'$'}{{ github.token }}
                  VER: ${'$'}{{ steps.ver.outputs.version }}
                run: |
                  TAG="v${'$'}VER"
                  if gh release view "${'$'}TAG" >/dev/null 2>&1; then
                    echo "::warning::Release ${'$'}TAG already exists. Bump version in package.json to publish a new release."
                  else
                    gh release create "${'$'}TAG" release/* --title "${'$'}TAG" --notes "" --target "${'$'}GITHUB_SHA"
                  fi
    """.trimIndent() + "\n"

    /** A matching build workflow for a project kind, or null if AutoHub has no template for it. */
    fun workflowFor(kind: com.stellar.autohub.upload.ProjectKind, repoName: String): String? = when (kind) {
        com.stellar.autohub.upload.ProjectKind.ANDROID -> workflow(repoName)
        com.stellar.autohub.upload.ProjectKind.ELECTRON -> electronWorkflow(repoName)
        else -> null
    }
}
