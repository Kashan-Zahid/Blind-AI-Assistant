# Blind AI Assistant

> **A Voice-First Assistive Android Platform for Blind & Visually Impaired Users**
> Submission for the **Alibaba Cloud AI Hackathon Pakistan 2026**
> Package: `com.blindassistant` | Architecture: Kotlin Multiplatform (KMP) + Android Native

[![Build & Test](https://img.shields.io/badge/Unit%20Tests-93%20Passed%20\(100%25\)-brightgreen.svg)]()
[![Target OS](https://img.shields.io/badge/Android-8.0%20to%2015%2B%20\(API%2026--36\)-blue.svg)]()
[![Model](https://img.shields.io/badge/AI%20Model-Gemini%203.6%20Flash-orange.svg)]()
[![Security](https://img.shields.io/badge/Secrets-Zero%20Exposed%20\(local.properties\)-success.svg)]()

---

## 1. Problem Statement & Impact

### The Problem

Visually impaired users can face significant accessibility barriers when interacting with modern touchscreen smartphones:

* **Complex UI hierarchies:** Deep menus, gesture-based navigation, and visual interfaces can be difficult to operate without sight.
* **Cloud dependency:** Basic device operations should not require an internet connection or a remote AI service.
* **Fragmented accessibility tools:** Tasks such as scene description, text reading, communication, and media control are often spread across multiple applications.
* **Voice interaction limitations:** General-purpose assistants are not always designed around a voice-first workflow for users who cannot rely on visual feedback.

### The Solution

**Blind AI Assistant** is a voice-first Android accessibility platform that combines local device automation, Android AccessibilityService capabilities, camera-based assistance, and Gemini-powered AI.

The system follows a **local-first architecture**:

* Device operations are processed locally whenever possible.
* Cloud AI is used for tasks requiring general reasoning or multimodal perception.
* Voice input and spoken responses provide the primary interaction method.
* AccessibilityService enables hands-free interaction with supported applications.

### Core Capabilities

* Offline device commands
* Battery and Wi-Fi information
* Flashlight and volume control
* Alarms and timers
* Voice-based phone calling
* WhatsApp automation
* YouTube voice search and playback control
* Camera-based scene description
* Currency recognition
* Text and document reading
* Object identification
* Gemini-powered general questions
* English and Roman Urdu voice commands
* Accessibility-focused voice interaction

---

## 2. Technical Architecture & System Design

```text
                         ┌─────────────────────────┐
                         │      User Voice Input   │
                         │  SpeechRecognizer / Mic │
                         └────────────┬────────────┘
                                      │
                                      ▼
                         ┌─────────────────────────┐
                         │    CommandProcessor     │
                         │                         │
                         │ Intent Detection        │
                         │ Command Normalization   │
                         │ Local / Cloud Routing   │
                         └────────────┬────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    │                                   │
                    ▼                                   ▼
          ┌────────────────────┐             ┌─────────────────────┐
          │ Local Device Layer │             │     Gemini AI       │
          │                    │             │                     │
          │ Battery            │             │ General Questions  │
          │ Wi-Fi              │             │ Vision Analysis    │
          │ Flashlight         │             │ Natural Language   │
          │ Volume             │             └──────────┬──────────┘
          │ Alarms / Timers    │                        │
          │ Calling            │                        ▼
          └─────────┬──────────┘             ┌─────────────────────┐
                    │                        │ CameraVisionManager │
                    │                        │                     │
                    │                        │ Camera2             │
                    │                        │ Image Capture       │
                    │                        │ Vision Processing   │
                    │                        └─────────────────────┘
                    │
                    ▼
          ┌──────────────────────┐
          │ Accessibility Layer  │
          │                      │
          │ WhatsApp Automation  │
          │ YouTube Automation   │
          │ UI Interaction       │
          └──────────────────────┘
```

---

## 3. Key Engineering Subsystems

### 3.1 Command Processing

**File:** `CommandProcessor.kt`

The command processor provides the main routing layer between voice input and application functionality.

It handles:

* Local device commands
* Natural-language normalization
* Conversational prefixes and suffixes
* English commands
* Roman Urdu commands
* Quick voice triggers
* Cloud AI fallback

Examples:

```text
"hey assistant, can you tell me my battery?"
                    ↓
"battery"

"please turn on the torch"
                    ↓
"torch"

"could you make the volume louder?"
                    ↓
"louder"
```

Supported local commands are processed without requiring Gemini.

---

### 3.2 Device Control

**File:** `DeviceController.kt`

The device control layer provides direct interaction with Android system functionality.

Supported areas include:

* Battery information
* Wi-Fi state
* Flashlight
* Volume
* Date and time
* Alarms
* Timers
* Application launching
* Media-related controls

Simple device operations remain independent from network availability.

---

### 3.3 Computer Vision & Camera Pipeline

**File:** `CameraVisionManager.kt`

The camera subsystem provides visual assistance through Android Camera2 APIs.

The pipeline supports:

* Camera2 integration
* Automatic exposure
* Automatic white balance
* Automatic focus
* Camera warm-up frames
* JPEG capture
* HD stream configuration
* Live floating viewfinder
* Gemini multimodal analysis

The camera is stabilized before image capture to improve the quality of visual analysis.

Example commands:

```text
"describe around me"
"what is in front of me?"
"read this text"
"count this money"
"find my object"
```

---

### 3.4 WhatsApp Accessibility Automation

**File:** `BlindAccessibilityService.kt`

The application uses Android `AccessibilityService` to provide hands-free interaction with supported WhatsApp workflows.

Capabilities include:

* Opening WhatsApp
* Navigating supported conversation interfaces
* Reading available notification message content
* Sending text messages
* Detecting incoming voice messages
* Triggering supported voice-message playback
* Voice-driven interaction

Example:

```text
"send WhatsApp to Ali saying I am almost there"
```

The accessibility service performs the supported interaction sequence without requiring the user to visually navigate the interface.

---

### 3.5 YouTube Voice Navigation

The YouTube integration is designed around voice-first search and selection.

The system filters irrelevant search-result elements such as:

* YouTube Shorts
* Search correction banners
* `"Did you mean"` results
* `"Showing results for"` messages
* Non-video UI elements

The user can select results using:

```text
"option 1"
"option 2"
"first"
"second"
```

Playback commands include:

```text
"play"
"pause"
"next video"
"skip ad"
```

---

## 4. Cloud AI Integration

### Google Gemini Developer API

Blind AI Assistant uses the Google Gemini Developer API for functionality requiring cloud-based generative AI.

The integration supports:

* General questions
* Natural-language reasoning
* Image understanding
* Scene description
* Text interpretation
* Multimodal assistance

The Android application communicates with the API using Ktor.

### API Configuration

The Gemini API key is not stored directly in the source code.

Create or edit:

```text
local.properties
```

and add:

```properties
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
```

`local.properties` is excluded from Git through `.gitignore`.

The key is injected into the application through Gradle `BuildConfig`.

### Running Without an API Key

The project can still compile without a Gemini API key.

Local device commands do not require cloud AI.

---

## 5. Security

The project uses a repository-safe configuration for sensitive credentials.

The following files and patterns are excluded from version control:

```text
local.properties
.env
.env.*
*.env
*.keystore
*.jks
google-services.json
composeApp/google-services.json
```

The public repository does not contain production API credentials.

Developers should:

1. Create their own Gemini API key.
2. Store it only in `local.properties`.
3. Never commit API keys.
4. Never place credentials directly in source code.
5. Rotate credentials immediately if they are accidentally exposed.

---

## 6. Technical Evaluation Quickstart

### Prerequisites

* JDK 17
* Android SDK
* Android API 26 or higher
* A physical Android device or emulator
* Internet connection for Gemini-powered functionality

### Clone the Repository

```bash
git clone https://github.com/Kashan-Zahid/Blind-AI-Assistant.git
cd Blind-AI-Assistant
```

### Configure Gemini

Create:

```text
local.properties
```

Add:

```properties
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
```

Do not commit this file.

### Run Tests

```bash
./gradlew test
```

Current project validation:

```text
93 tests
93 passed
0 failed
100% success
```

The test suite covers command processing, YouTube selection, WhatsApp/call workflows, AI client behavior, and live transcript functionality.

### Build the Debug APK

```bash
./gradlew assembleDebug
```

APK output:

```text
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### Install on Android

Connect an Android device with USB debugging enabled:

```bash
adb devices
```

Then:

```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

Launch:

```bash
adb shell am start -n com.blindassistant/.MainActivity
```

---

## 7. Quick Voice Commands Reference

The following commands provide a quick overview of the assistant's voice interface.

| Category             | Example Commands                      | Function                           |
| -------------------- | ------------------------------------- | ---------------------------------- |
| **Daily Essentials** | `time`, `date`, `battery`, `wifi`     | Provides device information        |
| **Flashlight**       | `torch`, `torch off`                  | Controls the flashlight            |
| **Volume**           | `louder`, `quieter`, `mute`, `unmute` | Controls audio volume              |
| **Camera**           | `describe`, `look`, `camera`          | Starts visual assistance           |
| **Photo**            | `take photo`, `snapshot`              | Captures an image                  |
| **Money**            | `money`, `cash`, `count money`        | Identifies currency                |
| **Text**             | `read text`, `read sign`, `ocr`       | Reads visible text                 |
| **Documents**        | `read document`, `document`           | Reads documents                    |
| **WhatsApp**         | `whatsapp [name] saying [message]`    | Sends WhatsApp messages            |
| **Messages**         | `read message`, `last message`        | Reads available messages           |
| **Voice Notes**      | `play it`, `play voice note`          | Plays a received voice message     |
| **YouTube**          | `youtube [query]`                     | Performs YouTube voice search      |
| **Video Selection**  | `option 1`, `option 2`, `first`       | Selects a search result            |
| **Playback**         | `play`, `pause`, `next video`         | Controls media                     |
| **Calling**          | `call [name]`, `call [number]`        | Makes a phone call                 |
| **Alarms**           | `alarm 7 am`                          | Creates an alarm                   |
| **Timers**           | `timer 5 minutes`                     | Creates a timer                    |
| **AI Questions**     | `explain gravity simply`              | Sends a general question to Gemini |

### Complete Voice Command Reference

For the complete list of supported commands, examples, and command categories, see:

**[📄 CommandsList.txt — Complete Voice Command Reference](CommandsList.txt)**

The file contains the detailed command reference for users, testers, and hackathon judges.

---

## 8. Live Demonstration Sequence

A short demonstration can be performed using the following sequence.

| Step   | Voice Command                                      | Expected Behavior                          |
| ------ | -------------------------------------------------- | ------------------------------------------ |
| **1**  | `Hello`                                            | Assistant responds using voice             |
| **2**  | `Battery`                                          | Reads the current battery level locally    |
| **3**  | `Torch`                                            | Turns on the flashlight                    |
| **4**  | `Torch off`                                        | Turns off the flashlight                   |
| **5**  | `Describe`                                         | Opens the camera assistance interface      |
| **6**  | `Take photo`                                       | Captures and analyzes an image             |
| **7**  | `Close camera`                                     | Closes the camera interface                |
| **8**  | `YouTube coke studio`                              | Opens YouTube and processes search results |
| **9**  | `Option 1`                                         | Selects the first available result         |
| **10** | `Pause`                                            | Controls playback                          |
| **11** | `Send WhatsApp to [Name] saying I am almost there` | Performs supported WhatsApp automation     |
| **12** | `Explain gravity simply`                           | Sends a general question to Gemini         |

---

## 9. Accessibility Configuration

Some features require Android accessibility permissions.

### Enable AccessibilityService

Open:

```text
Android Settings
    ↓
Accessibility
    ↓
Installed Apps / Downloaded Apps
    ↓
Blind AI Assistant
    ↓
Enable Accessibility Service
```

The accessibility service enables supported automation features such as:

* WhatsApp interaction
* YouTube interaction
* Voice-driven UI navigation
* Hands-free actions

### Permissions

Depending on the functionality being used, Android may request:

* Microphone
* Camera
* Contacts
* Phone
* Notifications
* AccessibilityService access

Permissions are used only for features that require them.

---

## 10. Project Structure

```text
Blind-AI-Assistant/
│
├── composeApp/
│   └── src/
│       ├── androidMain/
│       │   ├── kotlin/
│       │   │   └── com/blindassistant/
│       │   │       ├── MainActivity.kt
│       │   │       ├── AiClient.kt
│       │   │       ├── CommandProcessor.kt
│       │   │       ├── DeviceController.kt
│       │   │       ├── ContactsAndCallManager.kt
│       │   │       ├── BlindAccessibilityService.kt
│       │   │       ├── CameraVisionManager.kt
│       │   │       └── ...
│       │   │
│       │   └── res/
│       │
│       └── commonMain/
│
├── gradle/
│
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

## 11. Testing

The project includes unit tests covering the core application logic.

Current validation:

```text
93 tests
93 passed
0 failed
100% success
```

Test areas include:

* Command processing
* Intent routing
* Conversational normalization
* YouTube result filtering
* Video selection
* WhatsApp workflows
* Calling workflows
* AI client behavior
* API error handling
* Live transcript state

Run the complete suite:

```bash
./gradlew test
```

---

## 12. Limitations

Blind AI Assistant is an active Android accessibility project, and some functionality depends on Android versions, device hardware, installed applications, and external service behavior.

Examples:

* Accessibility automation can be affected by changes to third-party application interfaces.
* YouTube behavior is controlled by the YouTube application.
* WhatsApp automation depends on available accessibility nodes and notification behavior.
* Gemini-powered functionality requires an internet connection and valid API credentials.
* Camera capabilities vary between Android devices and camera hardware.
* Android platform security restrictions can limit certain system operations.

The application therefore separates local functionality from cloud-dependent functionality wherever practical.

---

## 13. Roadmap

Future development may include:

* Expanded offline capabilities
* Additional language support
* More robust accessibility automation
* Improved object recognition
* Enhanced document understanding
* Faster vision processing
* Additional Android system integrations
* Expanded automated testing
* Improved device compatibility

---

## 14. Contributing

Contributions are welcome.

Before submitting a pull request:

1. Create a feature branch.
2. Keep changes focused.
3. Do not commit API keys or private configuration files.
4. Run the test suite.
5. Test Android-specific changes on a physical device when possible.
6. Update `CommandsList.txt` when adding or changing voice commands.
7. Update the README when functionality changes.

Example:

```bash
git checkout -b feature/new-command
```

Run tests:

```bash
./gradlew test
```

Build:

```bash
./gradlew assembleDebug
```

---

## 15. License & Acknowledgements

Developed for the **Alibaba Cloud AI Hackathon Pakistan 2026**.

Licensed under the **Apache License 2.0**.

See [`LICENSE`](LICENSE) for the complete license text.

---

## 16. Project Goal

Blind AI Assistant is built around a simple principle:

> **Technology should be usable without requiring vision.**

The project aims to provide a practical voice-first interface that allows blind and visually impaired users to interact with essential smartphone functions, communication tools, media, camera assistance, and AI through spoken commands.

The long-term goal is to make everyday Android interaction more accessible, direct, and less dependent on visual interfaces.
