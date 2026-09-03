# Blind AI Assistant

> **A Voice-First Assistive Android Platform for Blind & Visually Impaired Users**  
> Submission for the **Alibaba Cloud AI Hackathon Pakistan 2026**  
> Package: `com.blindassistant` | Architecture: Kotlin Multiplatform (KMP) + Android Native  

[![Build & Test](https://img.shields.io/badge/Unit%20Tests-93%20Passed%20(100%25)-brightgreen.svg)]()
[![Target OS](https://img.shields.io/badge/Android-8.0%20to%2015%2B%20(API%2026--36)-blue.svg)]()
[![Model](https://img.shields.io/badge/AI%20Model-Gemini%203.6%20Flash-orange.svg)]()
[![Security](https://img.shields.io/badge/Secrets-Zero%20Exposed%20(local.properties)-success.svg)]()

---

## 1. Problem Statement & Impact

### The Problem
Visually impaired individuals face massive digital divide barriers when using modern capacitive touchscreen smartphones:
- **Complex UI Hierarchies**: Deeply nested menus, gesture navigation, and visual icon grids are difficult to navigate using standard screen readers.
- **High Cloud Latency & Offline Fragility**: Traditional voice assistants require continuous high-speed internet and suffer 800–2500ms roundtrip latency for simple device operations (battery check, volume, flashlight, alarms). If the user loses internet connection, the phone becomes completely inaccessible.
- **Fragmented Accessibility Apps**: Multimodal assistive tasks (scene description, currency counting, OCR text reading, document scanning) typically force the user to download and juggle separate, non-cohesive apps.

### The Solution: Blind AI Assistant
A unified, voice-first Android assistant designed specifically for accessibility:
- **0ms Local Intent Engine**: Hardware controls, battery, volume, torch, Wi-Fi, alarms, and timers execute locally and offline with zero cloud roundtrips.
- **Multimodal AI Vision (Gemini 3.6 Flash)**: Real-time Camera2 viewfinder with automated 3A exposure stabilization, anti-blur processing, and multi-modal perception for scene description, money counting, document reading, and object finding.
- **Deep OS Automation via AccessibilityService**: Hands-free WhatsApp messaging/voice note playback, YouTube voice control with automated search correction filtering, and hands-free phone calling.

---

## 2. Technical Architecture & System Design

```
                                ┌────────────────────────┐
                                │     User Voice Input   │
                                │  (SpeechRecognizer /   │
                                │   Floating Mic Window) │
                                └───────────┬────────────┘
                                            │
                                            ▼
                                ┌───────────────────────────┐
                                │     CommandProcessor      │
                                │ (Conversational Filtering │
                                │  & Local Intent Routing)  │
                                └─────┬───────────────┬─────┘
                                      │               │
               [Local Device Action]  │               │ [General AI / Vision Query]
               (0ms Offline Latency)  ▼               ▼
                        ┌────────────────┐    ┌─────────────────────────────────┐
                        │DeviceController│    │            AiClient             │
                        │- Battery, WiFi │    │ (Google Gemini Developer API:   │
                        │- Alarms, Audio │    │  gemini-3.6-flash endpoint)     │
                        │- Contacts/Calls│    └────────────────┬────────────────┘
                        │- Accessibility │                     │
                        └────────────────┘                     ▼
                                              ┌─────────────────────────────────┐
                                              │       CameraVisionManager       │
                                              │ - 3A Auto-Exposure Warmup (5-fr)│
                                              │ - HD 1280x720 / 1080p JPEG      │
                                              │ - Live Floating Viewfinder Popup│
                                              └─────────────────────────────────┘
```

### Key Engineering Subsystems:
1. **Hybrid Local / Cloud Intent Router (`CommandProcessor.kt`)**:
   - Strips polite conversational prefixes (*"can you please"*, *"could you tell me"*, *"hey assistant"*) and suffixes (*"please"*, *"for me"*, *"right now"*) using regex-free streaming string normalization.
   - Evaluates offline intents first (battery, flashlight, volume, Wi-Fi, time/date, alarms, app launching, recorder).
   - Only falls back to **Gemini 3.6 Flash** when complex natural language reasoning or visual perception is strictly required.

2. **Computer Vision & Camera Pipeline (`CameraVisionManager.kt`)**:
   - **3A Convergence Engine**: Mobile CMOS sensors power on with unmetered exposure gain (causing blown-out white glare) and default lens position (causing blur). Our pipeline streams 5 warm-up repeating requests allowing hardware Auto-Exposure (AE), Auto-White-Balance (AWB), and Auto-Focus (AF) to lock before taking the snapshot.
   - **Hardware Noise Reduction & Edge Optimization**: Configures `NOISE_REDUCTION_MODE_HIGH_QUALITY`, `EDGE_MODE_HIGH_QUALITY`, and `JPEG_QUALITY 95`.
   - **Dynamic HD Resolution**: Dynamically discovers sensor stream configuration maps, selecting 1280x720 / 1080p HD instead of low-res 640x480.
   - **Compose Viewfinder HUD**: Floating viewfinder popup with framing brackets (`⌜ ⌝ ⌞ ⌟`) decodes the actual JPEG frame via multiplatform `decodeBase64ToImageBitmap()` in real-time.

3. **WhatsApp Deep Automation Engine (`BlindAccessibilityService.kt`)**:
   - **Notification Parser**: Inspects `Notification.EXTRA_TEXT_LINES` and `Notification.EXTRA_MESSAGES` to extract actual individual message lines instead of speaking generic counter summaries like *"2 new messages from Ali"*.
   - **Voice Note Detection & Auto-Playback**: Detects incoming voice messages (`🎙️ Voice message` / `Audio`), announces them, and automatically opens WhatsApp to trigger the play button when the user says *"play it"* or *"play voice note"*.
   - **Hands-Free Messaging**: Automates the complete send sequence (resolving conversation node, typing text, verifying delivery).

4. **YouTube Voice Navigation & "Did you mean" Noise Filtering**:
   - Filters out non-video UI elements, YouTube Shorts carousels, and search correction banners (*"Did you mean"*, *"Showing results for"*, *"Search instead for"*) across 3 distinct validation layers (`isUiNoiseOrStatusTitle`, `cleanYouTubeVideoTitle`, `isNonVideoCandidate`).
   - Options 1, 2, and 3 presented to the blind user are guaranteed to be playable full-length videos.

---

## 3. Cloud AI & Backend Architecture

### AI Provider: Google Gemini Developer API
The application uses the Google Gemini Developer API directly without middle-tier wrappers:
- **Model**: `gemini-3.6-flash`
- **Endpoint**: `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent`
- **Protocol**: Direct REST API call via Ktor `HttpClient` (OkHttp engine on Android) with Base64 inline JPEG payload for vision queries.
- **Unified Provider**: Both text reasoning (*"ask"*) and multimodal vision analysis (*"askWithVision"*) use this exact same endpoint and model.

### Firebase Integration Scope
- **Firebase Core**: Initialized on Android startup via `FirebaseApp.initializeApp(this)` for Android platform services.
- **Dormant / Excluded Services**: No Firebase Authentication, Cloud Firestore, App Check, Cloud Messaging (FCM), or Cloud Functions are used. All user preferences, personal memories, alarm data, and audio recordings are stored locally and privately on-device without cloud database lock-in.

### Zero-Secret Public Repository Guarantee
This repository contains **zero exposed credentials or API keys**:
- The API key is injected at build time into `BuildConfig.GEMINI_API_KEY`.
- Developers supply their personal key inside `local.properties` (which is strictly git-ignored):
  ```properties
  GEMINI_API_KEY=AIzaSyYourKeyHere
  ```
- `composeApp/google-services.json` is strictly ignored by `.gitignore` and not tracked by Git.
- If built without an API key, the project still compiles 100% cleanly, all 93 unit tests pass, and all offline local commands remain fully operational.

---

## 4. Technical Evaluation Quickstart (For Judges)

### Prerequisites
- **JDK 17** (`openjdk 17`)
- **Android SDK** (API 26 to API 36)

### 1. Run the Complete Test Suite
```bash
./gradlew test
```
**Results:** **93 unit tests execute and pass (100% success rate)** across 5 test suites:
- `CommandProcessorTest`: 48 tests (intent routing, conversational normalization, quick triggers)
- `YouTubeVideoSelectionTest`: 17 tests (voice search, playback control, "Did you mean" rejection)
- `WhatsAppCallFlowTest`: 13 tests (contact disambiguation, message dispatch, call handling)
- `AiClientTest`: 10 tests (payload construction, API key injection, error handling)
- `LiveTranscriptTest`: 5 tests (state emissions, transcript flow)

### 2. Build the Debug APK
```bash
./gradlew assembleDebug
```
- **Output Artifact**: `composeApp/build/outputs/apk/debug/composeApp-debug.apk`
- **Size**: `~20 MB`

### 3. Install on Connected Android Device
```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb shell am start -n com.blindassistant/.MainActivity
```

---

## 5. Quick Voice Commands Reference Cheatsheet

| Category | Quick Commands (1-Word / Conversational) | Action / System Response |
|---|---|---|
| **Daily Essentials** | `time`, `date`, `battery`, `wifi`, `weather` | Instant spoken response (0ms cloud latency) |
| **Hardware Torch** | `torch` *(on)*, `torch off` | Direct hardware camera flash toggle |
| **Volume Control** | `louder`, `quieter`, `mute`, `unmute`, `volume 50%` | Adjusts system audio streams |
| **Surroundings** | `describe`, `look`, `camera`, `what's around me` | Opens HD viewfinder, stabilizes 3A, speaks scene |
| **Actual Photo** | `take photo`, `take picture`, `snapshot` | Snaps photograph and speaks captured objects |
| **Cash / Money** | `money`, `cash`, `count money` | Identifies banknotes, currency denominations, and totals |
| **Text / OCR** | `read text`, `read sign`, `ocr` | Reads medicine bottles, packaging, and street signs |
| **Mail / Documents**| `read document`, `read mail`, `document` | Reads letters, receipts, and printed pages |
| **Dismiss Camera** | `close camera`, `dismiss camera` | Hands-free closing of floating viewfinder popup |
| **Read Message** | `what is the message`, `read message`, `last message` | Speaks out incoming WhatsApp message text |
| **Play Voice Note** | `play it`, `play voice note`, `play message` | Opens WhatsApp and plays received voice message |
| **Send WhatsApp** | `whatsapp [name] saying [message]` | Hands-free messaging via AccessibilityService |
| **YouTube Search** | `youtube [query]` *(e.g. `youtube relaxing music`)* | Opens YouTube, executes search, lists clean videos |
| **Select Video** | `option 1`, `option 2`, `first`, `second` | Plays selected video (filters out "Did you mean") |
| **Media Playback** | `pause`, `play`, `skip ad`, `next video` | Controls YouTube video playback |
| **Phone Calls** | `call [name]`, `call [number]`, `speaker on` | Hands-free telephony and speakerphone control |
| **Alarms & Timers** | `alarm 7 am`, `timer 5 minutes`, `cancel alarm` | Hardware clock management |
| **Lecture Recorder**| `record lecture`, `stop recording`, `play recording` | Internal audio recorder for students & meetings |
| **Emergency SOS** | `sos`, `emergency`, `help` | Sends GPS location broadcast via SMS |
| **Gemini 3.6 Flash** | *"Why is the sky blue?"*, *"What is 50 times 4"* | Cloud generative reasoning without markdown noise |

---

## 6. Live Demonstration Sequence (2–4 Minutes)

Judges can execute this exact demo sequence to evaluate responsiveness, multimodal vision, and deep system automation:

| Step | Voice Command | System Behavior & Technical Execution |
|---|---|---|
| **1. Wake & Speak** | Tap mic or hold: *"Hello"* | Speaks welcome response with animated soundwave visualizer. |
| **2. Instant Offline Routing** | *"Battery"* | Spoken battery percentage at **0ms cloud latency**. |
| **3. Hardware Control** | *"Torch"*, then *"Torch off"* | Camera LED toggles instantly via `CameraManager.setTorchMode()`. |
| **4. Multimodal Vision** | *"Describe"* or *"Take photo"* | Floating viewfinder popup displays live with HUD brackets, performs 5-frame 3A stabilization, and Gemini 3.6 Flash describes the environment. |
| **5. Hands-Free Dismiss** | *"Close camera"* | Viewfinder popup dismisses cleanly. |
| **6. YouTube Voice Control** | *"YouTube coke studio"* | Launches YouTube, parses results with "Did you mean" filter, and announces: *"Option 1: [Song]. Say an option number to play it."* |
| **7. Video Selection** | *"Option 1"* | Automatically navigates to and plays the selected video. |
| **8. WhatsApp Message** | *"Send WhatsApp to [Name] saying I am almost there"* | Accessibility service opens WhatsApp, navigates to contact, types message, and sends. |
| **9. General Intelligence** | *"Explain gravity simply"* | Gemini 3.6 Flash provides conversational explanation. |

---

## 7. Permissions & Accessibility Configuration

To enable the full accessibility automation features:
1. **Accessibility Service**: Open **Android Settings > Accessibility** and enable **Blind AI Assistant Screen Reader & Automation**.
2. **Microphone & Camera**: Required for voice input and assistive camera vision.
3. **Contacts & Phone (Optional)**: Required for hands-free voice dialing by contact name.

---

## 8. License & Acknowledgements

Developed for the **Alibaba Cloud AI Hackathon Pakistan 2026**.  
Licensed under the [Apache 2.0 License](LICENSE).
