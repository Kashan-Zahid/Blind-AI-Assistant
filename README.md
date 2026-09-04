# Blind AI Assistant

> **A Voice-First Assistive Android Platform for Blind & Visually Impaired Users**

**Alibaba Cloud AI Hackathon Pakistan 2026**

**Package:** `com.blindassistant`
**Platform:** Android
**Minimum Android:** 8.0 / API 26
**Architecture:** Kotlin Multiplatform (KMP) + Android Native
**AI:** Google Gemini Developer API
**Interaction:** Voice + Text-to-Speech + AccessibilityService

[![Tests](https://img.shields.io/badge/Tests-93%20Passed%20%7C%20100%25-brightgreen.svg)]()
[![Android](https://img.shields.io/badge/Android-8.0%2B%20%7C%20API%2026--36-blue.svg)]()
[![Architecture](https://img.shields.io/badge/Architecture-KMP%20%2B%20Android%20Native-purple.svg)]()
[![AI](https://img.shields.io/badge/AI-Google%20Gemini-orange.svg)]()
[![Security](https://img.shields.io/badge/Secrets-local.properties-success.svg)]()

---

## 1. Overview

**Blind AI Assistant** is a voice-first Android accessibility platform designed to help blind and visually impaired users interact with smartphones without depending primarily on visual interfaces.

It combines:

* 🎤 Speech recognition
* 🔊 Text-to-Speech
* 🧠 Local command processing
* 🤖 Gemini AI
* 📷 Camera-based vision
* ♿ Android AccessibilityService
* 📱 Device controls
* 💬 WhatsApp automation
* ▶️ YouTube voice navigation
* 🌐 English + Roman Urdu commands

### Problem

Modern smartphones rely heavily on visual interfaces, complex menus, gestures, and separate applications. Basic device operations may also unnecessarily depend on cloud services.

### Solution

Blind AI Assistant provides a unified voice-first workflow:

```text
Voice Input
    ↓
CommandProcessor
    ↓
Local Command ─────→ Android Device
    │
    └───────────────→ Gemini AI
                           ↓
                    Camera / AI Analysis
    ↓
Spoken Response
```

The system uses **local processing whenever possible** and Gemini only when cloud AI is useful or required.

---

# 2. Key Features

### 📱 Device Assistance

* Battery status
* Wi-Fi status
* Flashlight control
* Volume control
* Date & time
* Alarms
* Timers
* Application launching
* Supported media controls

### 📞 Communication

* Voice-based calling
* Contact lookup
* WhatsApp messaging
* Supported WhatsApp UI automation
* Message/notification reading
* Supported voice-message playback

### 📷 Visual Assistance

* Scene description
* Object identification
* Currency recognition
* Text/OCR reading
* Document reading
* Camera-based questions
* Gemini multimodal analysis

### ▶️ YouTube

* Voice search
* Result selection
* Shorts filtering
* Search-result cleanup
* Playback control
* Voice-driven navigation

### 🤖 AI

* General questions
* Natural-language reasoning
* Explanations
* Image understanding
* Scene interpretation
* Text interpretation
* Multimodal assistance

### 🌐 Languages

* English
* Roman Urdu

---

# 3. Technical Architecture

```text
                    ┌─────────────────────┐
                    │   Voice / Mic Input │
                    │  Android Speech API │
                    └──────────┬──────────┘
                               ↓
                    ┌─────────────────────┐
                    │  CommandProcessor   │
                    │                     │
                    │ Intent Detection    │
                    │ Normalization       │
                    │ Language Handling   │
                    │ Local/Cloud Routing │
                    └──────────┬──────────┘
                               │
                 ┌─────────────┴─────────────┐
                 ↓                           ↓
       ┌──────────────────┐        ┌──────────────────┐
       │  Local Device    │        │   Gemini AI      │
       │     Layer        │        │                  │
       │                  │        │ General AI       │
       │ Battery          │        │ Reasoning        │
       │ Wi-Fi            │        │ Vision           │
       │ Flashlight       │        │ Text Analysis    │
       │ Volume           │        │ Multimodal AI    │
       │ Alarms/Timers    │        └────────┬─────────┘
       │ Calling          │                 ↓
       └────────┬─────────┘        ┌──────────────────┐
                │                  │ Camera Vision    │
                │                  │ Camera2 + Images │
                │                  └──────────────────┘
                ↓
       ┌──────────────────────┐
       │ Accessibility Layer  │
       │                      │
       │ WhatsApp             │
       │ YouTube              │
       │ UI Navigation        │
       └──────────────────────┘
```

---

# 4. Core Subsystems

## Command Processing

**`CommandProcessor.kt`**

Handles:

* Intent detection
* Command normalization
* English commands
* Roman Urdu commands
* Conversational prefixes/suffixes
* Quick triggers
* Local/cloud routing
* Gemini fallback

Example:

```text
"hey assistant, can you tell me my battery?"
                         ↓
                      "battery"
```

```text
"please turn on the torch"
                         ↓
                       "torch"
```

---

## Device Control

**`DeviceController.kt`**

Provides local Android operations including:

* Battery
* Wi-Fi
* Flashlight
* Volume
* Date/time
* Alarms
* Timers
* App launching
* Media controls
* Supported phone actions

Basic operations do not require Gemini.

---

## Camera & Vision

**`CameraVisionManager.kt`**

Uses Android Camera2 and supports:

* Automatic exposure
* Auto white balance
* Auto focus
* Camera warm-up
* JPEG capture
* HD streams
* Live/floating viewfinder
* Gemini image analysis

Example:

```text
"Describe around me"
"What is in front of me?"
"Read this text"
"Count this money"
"Find my object"
```

---

## WhatsApp Automation

**`BlindAccessibilityService.kt`**

Uses Android AccessibilityService for supported WhatsApp workflows:

* Open WhatsApp
* Navigate supported conversations
* Contact-based messaging
* Send text messages
* Read available notification/message content
* Supported voice-message workflows

Example:

```text
"Send WhatsApp to Ali saying I am almost there"
```

> WhatsApp automation depends on its current UI/accessibility structure and may be affected by future app updates.

---

## YouTube Automation

The accessibility layer supports voice-driven YouTube interaction.

It filters elements such as:

* Shorts
* `"Did you mean"` results
* `"Showing results for"` messages
* Other irrelevant/non-video UI

Selection:

```text
"Option 1"
"Option 2"
"First"
"Second"
```

Playback:

```text
"Play"
"Pause"
"Next video"
"Skip ad"
```

> YouTube UI changes can affect automation compatibility.

---

# 5. Gemini AI Integration

Blind AI Assistant uses the **Google Gemini Developer API** for cloud-based AI functionality.

Gemini is used for:

* General questions
* Reasoning
* Image understanding
* Scene description
* Text interpretation
* Multimodal assistance

### Local vs Cloud

```text
Battery / Torch / Volume / Time
            ↓
       Local Processing
            ↓
       No Gemini needed
```

```text
"Explain quantum computing"
            ↓
          Gemini
            ↓
       Spoken Answer
```

---

# 6. Security & API Configuration

API credentials are stored locally and are not committed to Git.

Create:

```text
local.properties
```

Add:

```properties
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
```

If the Android SDK requires manual configuration:

```properties
sdk.dir=/home/your-username/Android/Sdk
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
```

`local.properties` is excluded from Git.

Never commit:

```text
local.properties
.env
.env.*
*.env
*.jks
*.keystore
```

Never hardcode API keys into Kotlin, XML, or Gradle source files.

---

# 7. Quickstart

## Requirements

Install:

* Android Studio
* JDK 17
* Android SDK
* Android SDK Platform 36
* Android SDK Build-Tools
* Android SDK Platform-Tools
* Git

For testing:

* Android 8.0+ device/emulator
* Internet connection for Gemini features

### Clone

```bash
git clone https://github.com/Kashan-Zahid/Blind-AI-Assistant.git
cd Blind-AI-Assistant
```

### Android SDK

Android Studio normally detects the SDK automatically.

For a common Linux SDK location:

```bash
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
```

Verify:

```bash
cat local.properties
```

Expected:

```properties
sdk.dir=/home/your-username/Android/Sdk
```

Check the SDK:

```bash
ls "$HOME/Android/Sdk"
```

Check ADB:

```bash
adb version
```

### Configure Gemini

Edit:

```text
local.properties
```

and add:

```properties
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
```

---

# 8. Build, Test & Install

## Test

```bash
./gradlew test
```

Current validation:

```text
93 tests
93 passed
0 failed
100% success
```

Coverage areas include:

* Command processing
* Intent routing
* Normalization
* English/Roman Urdu commands
* YouTube filtering
* Video selection
* WhatsApp workflows
* Calling
* AI client
* API error handling
* Transcript state

## Build

```bash
chmod +x gradlew
./gradlew assembleDebug
```

APK output:

```text
composeApp/build/outputs/apk/debug/
```

Find it with:

```bash
find composeApp/build/outputs -name "*.apk"
```

## Install

Enable USB debugging and connect the Android device:

```bash
adb devices
```

Install:

```bash
adb install -r composeApp/build/outputs/apk/debug/*.apk
```

Package:

```text
com.blindassistant
```

---

# 9. Permissions & Accessibility

Depending on features used, Android may request:

* 🎤 Microphone
* 📷 Camera
* 👤 Contacts
* 📞 Phone
* 🔔 Notifications
* ♿ AccessibilityService

Enable accessibility through:

```text
Settings
  ↓
Accessibility
  ↓
Installed Apps / Downloaded Apps
  ↓
Blind AI Assistant
  ↓
Enable
```

Menu names vary between Android manufacturers.

AccessibilityService enables supported:

* WhatsApp automation
* YouTube automation
* UI interaction
* Voice-driven navigation

---

# 10. Voice Commands

| Category    | Examples                           | Function               |
| ----------- | ---------------------------------- | ---------------------- |
| Essentials  | `time`, `date`, `battery`, `wifi`  | Device information     |
| Flashlight  | `torch`, `torch off`               | Flashlight             |
| Volume      | `louder`, `quieter`, `mute`        | Audio                  |
| Camera      | `describe`, `look`, `camera`       | Visual assistance      |
| Photo       | `take photo`, `snapshot`           | Image capture          |
| Money       | `money`, `cash`, `count money`     | Currency recognition   |
| Text        | `read text`, `read sign`, `ocr`    | Text reading           |
| Documents   | `read document`                    | Document assistance    |
| WhatsApp    | `whatsapp [name] saying [message]` | Messaging              |
| Messages    | `read message`, `last message`     | Message reading        |
| Voice Notes | `play it`, `play voice note`       | Voice-message playback |
| YouTube     | `youtube [query]`                  | Voice search           |
| Selection   | `option 1`, `first`                | Select result          |
| Playback    | `play`, `pause`, `next video`      | Media                  |
| Calling     | `call [name]`, `call [number]`     | Phone call             |
| Alarms      | `alarm 7 am`                       | Create alarm           |
| Timers      | `timer 5 minutes`                  | Create timer           |
| AI          | `explain gravity simply`           | Gemini                 |

For the complete command list:

```text
CommandsList.txt
```

---

# 11. Demo Flow

A quick demonstration:

```text
1. "Hello"
2. "Battery"
3. "Torch"
4. "Torch off"
5. "Describe"
6. "Take photo"
7. "Close camera"
8. "YouTube Coke Studio"
9. "Option 1"
10. "Pause"
11. "Send WhatsApp to [Name] saying I am almost there"
12. "Explain gravity simply"
```

This demonstrates:

**Voice → Local Commands → Camera Vision → Accessibility Automation → Gemini AI**

---

# 12. Project Structure

```text
Blind-AI-Assistant/
│
├── composeApp/
│   └── src/
│       ├── androidMain/
│       │   ├── kotlin/com/blindassistant/
│       │   │   ├── MainActivity.kt
│       │   │   ├── AiClient.kt
│       │   │   ├── CommandProcessor.kt
│       │   │   ├── DeviceController.kt
│       │   │   ├── ContactsAndCallManager.kt
│       │   │   ├── BlindAccessibilityService.kt
│       │   │   ├── CameraVisionManager.kt
│       │   │   └── ...
│       │   └── res/
│       │
│       └── commonMain/
│
├── gradle/
├── CommandsList.txt
├── README.md
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
└── gradlew.bat
```

---

# 13. Troubleshooting

### SDK location not found

Create `local.properties`:

```bash
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
```

Then:

```bash
./gradlew assembleDebug
```

If that path does not exist, find the SDK location from Android Studio:

```text
Settings / Preferences
→ Languages & Frameworks
→ Android SDK
```

### Gradle permission denied

```bash
chmod +x gradlew
```

### Wrong Java version

```bash
java -version
./gradlew --version
```

Use **JDK 17**.

### Gemini not responding

Check:

```text
local.properties
```

for:

```properties
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
```

Also verify internet access and API-key validity.

### Accessibility automation not working

Check that:

1. The service is enabled.
2. Required permissions are granted.
3. The target application is installed.
4. The target app has not changed its UI/accessibility structure.

---

# 14. Limitations & Roadmap

## Limitations

Some functionality depends on:

* Android version
* Device hardware
* Manufacturer-specific behavior
* Third-party application UI
* Accessibility nodes
* Internet connectivity
* Gemini API availability
* Camera capabilities
* Android security restrictions

## Roadmap

Planned/improvable areas include:

* Expanded offline AI
* Offline vision
* Additional languages
* More robust accessibility automation
* Improved object recognition
* Enhanced document understanding
* Faster vision processing
* More Android integrations
* Expanded automated testing
* Better device compatibility

---

# 15. Hackathon & Project Goal

Developed for the **Alibaba Cloud AI Hackathon Pakistan 2026**.

The project combines:

```text
Android
+
Kotlin Multiplatform
+
AccessibilityService
+
Camera2
+
Speech Recognition
+
Text-to-Speech
+
Gemini AI
```

to address a practical accessibility problem.

> **Technology should be usable without requiring vision.**

Blind AI Assistant aims to make everyday smartphone interaction more accessible by allowing users to perform essential device operations, communicate, control media, access visual information, and interact with AI through spoken commands.

---

# 16. Contributing

Before submitting changes:

1. Create a feature branch.
2. Keep changes focused.
3. Never commit API keys or private configuration.
4. Run tests.
5. Test Android-specific features on a physical device when possible.
6. Update `CommandsList.txt` when commands change.
7. Update the README when functionality or setup changes.

```bash
git checkout -b feature/new-command
./gradlew test
./gradlew assembleDebug
```

---

# License

Developed for the **Alibaba Cloud AI Hackathon Pakistan 2026**.

Licensed under the **MIT License**.

See `LICENSE` for the complete license text.
