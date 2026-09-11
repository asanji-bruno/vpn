# 📱 BDNET Tunnel — Native Android App Guide

This directory contains the **Native Android (Kotlin)** source code for the **BDNET Tunnel Mobile App**.

---

## ⚡ Key Highlights & Architecture

- **Ultra-Lightweight Footprint**: Compiled APK is **under ~5MB** (uses standard Android SDK + OkHttp).
- **Default Hardcoded Ngrok Endpoint**: Default server URL is `https://battery-twirl-designate.ngrok-free.dev`.
- **Zero Heavy Framework Overhead**: Built natively in Kotlin — no React Native / Flutter runtime bloat.
- **6 Tunneling Protocols Integrated**:
  1. **Method 1**: DNS Tunneling (UDP Port 53 Base32)
  2. **Method 2**: WebSocket Relay (`wss://.../ws`)
  3. **Method 3**: SSH over WebSocket Bridge (`wss://.../ssh-relay`)
  4. **Method 4**: SNI Payload Injector (TLS Bug Host)
  5. **Method 5**: VLESS over WebSocket (`wss://.../vless`)
  6. **Method 6**: HTTP CONNECT Proxy
- **Foreground Service**: Runs reliably in background with active notification so Android battery optimizer won't kill it.
- **Live Monospace Log Terminal**: Real-time packet and event stream output directly on screen.

---

## 🚀 How to Build & Compile

### Option 1: Android Studio (Recommended & Easiest)
1. Open **Android Studio**.
2. Click **Open** and select the folder: `c:\Users\ACER\Desktop\tunneling\android_app`
3. Let Gradle sync automatically (takes 30-60 seconds).
4. Go to **Build** -> **Build Bundle(s) / APK(s)** -> **Build APK(s)**.
5. The compiled lightweight `.apk` will be generated in:
   `android_app/app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Project IDX / Firebase Studio / Cloud IDE
1. Zip or upload the `android_app` directory to your web workspace.
2. Run `./gradlew assembleDebug` in the terminal.
3. Download the generated `.apk` file to your phone!

---

## 🛠️ Project Structure

```
android_app/
├── build.gradle.kts
├── settings.gradle.kts
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/bdnet/tunnel/
        │   ├── MainActivity.kt           (UI logic, dropdowns, log bus)
        │   ├── TunnelService.kt          (Foreground background engine service)
        │   ├── model/Config.kt           (Ngrok default URL & parameters)
        │   ├── tunnel/
        │   │   ├── WsTunnelClient.kt     (WebSocket Relay client)
        │   │   ├── VlessClient.kt        (VLESS client)
        │   │   ├── DnsTunnelClient.kt    (DNS client)
        │   │   ├── SniInjectClient.kt    (SNI Injector)
        │   │   ├── SshWsClient.kt        (SSH over WS client)
        │   │   └── HttpConnectClient.kt  (HTTP Proxy client)
        │   └── util/Logger.kt            (Live log buffer)
        └── res/
            ├── layout/activity_main.xml  (Dark UI Layout)
            ├── values/colors.xml         (Color palette)
            ├── values/strings.xml
            └── values/themes.xml
```
