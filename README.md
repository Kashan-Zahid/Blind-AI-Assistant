# Blind AI Assistant

> **A Voice-First Assistive Android Platform for Blind & Visually Impaired Users**  
> Built for the **Alibaba Cloud AI Hackathon Pakistan 2026**

---

## 1. Problem Statement & Target Users

### The Problem
Visually impaired individuals face steep accessibility hurdles when navigating modern touchscreen smartphones:
- Complex nested menus and icon grids are difficult to navigate using conventional screen readers.
- Standard cloud-only voice assistants require constant high-speed internet and suffer from noticeable roundtrip latency even for simple local device actions (like checking battery, toggling a flashlight, or setting a timer).
- Multimodal tasks (like reading paper documents, recognizing currency, or locating lost objects) typically require separate fragmented apps.

### Target Users
- Individuals who are completely blind or have severe low vision.
- Elderly users or those with visual motor impairments who need a 100% hands-free, voice-directed smartphone experience.

---

## 2. Core Features & Capabilities

- **Voice-First Accessibility**:
  - Full hands-free voice interface with high-contrast UI and spoken feedback.
  - Push-to-talk (hold) and toggle-to-talk (tap) with conversational noise filtering.
  - Floating microphone overlay to control any app across the entire Android system.
- **Offline-First Local Command Router**:
  - Instant 0ms cloud latency for hardware operations: Battery level, Wi-Fi status, Bluetooth, Flashlight toggle, Volume adjustments, Time/Date, and Display Brightness.
  - Alarms, countdown timers, and scheduled reminders without internet reliance.
- **Google Gemini 3.6 Flash AI Integration**:
  - General conversational reasoning, educational queries, and complex question answering powered by `gemini-3.6-flash`.
- **Assistive Camera Vision & Viewfinder**:
  - Automatic floating camera viewfinder popup with HUD framing brackets (`⌜ ⌝ ⌞ ⌟`).
  - Real-time photo capture and multi-modal AI analysis for:
    - **Surroundings & Scene Description**: Comprehensive overview of obstacles, objects, and environment.
    - **Text & OCR Reading**: Verbatim reading of physical letters, notices, and signs.
    - **Currency Counter**: Detection and total tally of banknotes, bills, and coins.
    - **Color Detector**: Identifies garment and object colors for clothing matching.
    - **Document & Mail Reader**: Reads receipts, invoices, and mail top-to-bottom.
    - **Object Finder**: Locates specific items (keys, glasses, doors) with clock-face directional cues.
    - **Hands-Free Dismissal**: Say *"close camera"* or tap Dismiss.
- **Deep Android Accessibility Automation**:
  - **WhatsApp Automation**: Send messages and initiate voice/video calls completely hands-free via `BlindAccessibilityService`.
  - **YouTube Voice Navigation**: Voice search, video playback control, and numbered option selection (*"option 2"*, *"play video 1"*).
  - **Hands-Free Calling & Contacts**: Dial by contact name or telephone number, automatic caller announcement, and voice call handling.
- **Lecture & Meeting Voice Recorder**:
  - Hands-free recording, management, and playback for visually impaired students and professionals.
- **Emergency SOS & Location**:
  - Quick emergency location broadcast via SMS and instant spoken coordinates.

---

## 3. Architecture & Technology Stack

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
                                   ▼               ▼
                       ┌────────────────┐    ┌─────────────────────────────────┐
                       │DeviceController│    │            AiClient             │
                       │- Battery, WiFi │    │ (Google Gemini Developer API:   │
                       │- Alarms, Audio │    │  gemini-3.6-flash endpoint)     │
                       │- Contacts/Calls│    └────────────────┬────────────────┘
                       │- Accessibility │                     │
                       └────────────────┘                     ▼
                                             ┌─────────────────────────────────┐
                                             │       CameraVisionManager       │
                                             │ (Camera2 Frame Capture + Popup) │
                                             └─────────────────────────────────┘
```

- **Framework**: Kotlin Multiplatform (KMP) & Jetpack Compose Multiplatform (Compose Material3).
- **Audio Engine**: Android `SpeechRecognizer` with customized silence-duration windows + Android `TextToSpeech` with speech queue management.
- **OS Automation**: Custom Android `AccessibilityService` (`BlindAccessibilityService`) providing window content inspection, node traversal, and click/scroll gesture synthesis.
- **Camera Pipeline**: Android Camera2 API (`ImageReader`) with Base64 JPEG frame delivery and Compose high-resolution bitmap rendering.
- **Cloud AI Provider**: Google Gemini Developer API (`gemini-3.6-flash`).

---

## 4. Google Gemini Configuration & Security

The application connects directly to the Google Gemini Developer API:
- **Provider**: Google Gemini Developer API
- **Model**: `gemini-3.6-flash`
- **Endpoint**: `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent`
- **Request Header**: `x-goog-api-key: <API_KEY>`

### Zero-Secret Public Repository Guarantee
No production API keys, secrets, or private credentials are stored in this Git repository:
- The API key is injected at compile-time via `BuildConfig.GEMINI_API_KEY`.
- Developers configure their personal key in `local.properties` (which is git-ignored).
- If no key is provided, the project still compiles cleanly and local device commands remain 100% functional offline.

To configure your Gemini API key locally:
1. Open or create `local.properties` in the project root.
2. Add your key:
   ```properties
   GEMINI_API_KEY=AIzaSyYourActualKeyHere
   ```

---

## 5. Building and Running

### Prerequisites
- **Android Studio** Ladybug (2024.2+) or later
- **JDK 17**
- **Android SDK**: Compile SDK 36, Minimum SDK 26 (Android 8.0 through Android 15+)
- Physical Android device with microphone and camera (recommended for full hardware testing)

### Build Steps

1. **Clone Repository**:
   ```bash
   git clone https://github.com/Kashan-Zahid/Blind-Ai-Assistnat.git
   cd Blind-Ai-Assistnat
   ```

2. **Configure API Key**:
   Add `GEMINI_API_KEY=your_key` to `local.properties`.

3. **Run Unit Tests**:
   ```bash
   ./gradlew test
   ```
   *(92 unit tests covering command routing, conversational normalization, YouTube selection, WhatsApp flows, and AI client handling).*

4. **Assemble Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   Output APK: `composeApp/build/outputs/apk/debug/composeApp-debug.apk`

5. **Install on Connected Android Device**:
   ```bash
   adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
   adb shell am start -n com.example.blindaassistant/.MainActivity
   ```

---

## 6. Hackathon Live Demo Guide (2–4 Minutes)

Follow this sequence for an impactful live demonstration:

| Step | Action / Spoken Voice Command | Expected Live Response | Highlight |
|---|---|---|---|
| **1. Wake & Speak** | Tap center button or hold to speak: *"Hello"* | Assistant replies: *"Hello! How can I help you today?"* with animated wave visualizer. | Seamless accessibility gesture & speech recognition. |
| **2. Local Command** | Speak: *"Battery"* or *"Check battery"* | Instant spoken response (e.g., *"Battery is at 84 percent"*). | **0ms cloud latency** local intent routing. |
| **3. Device Control** | Speak: *"Flashlight on"*, then *"Flashlight off"* | Hardware torch activates and deactivates immediately with spoken confirmation. | Direct hardware control. |
| **4. Cloud AI (Gemini)** | Speak: *"Why is the sky blue?"* or *"Explain gravity simply"* | Gemini 3.6 Flash responds with concise, conversational explanation. | Cloud reasoning without markdown clutter. |
| **5. Camera Vision** | Speak: *"Describe"* or *"Look around me"* | Small viewfinder popup appears with HUD brackets, captures camera frame, displays actual photo, and speaks scene details. | Multimodal assistive vision. |
| **6. Dismiss Camera** | Speak: *"Close camera"* | Camera popup closes smoothly with voice confirmation. | Hands-free dismiss control. |
| **7. YouTube Navigation** | Speak: *"Search YouTube for relaxing piano"* | Launches YouTube, searches, reads results, and allows picking: *"Option 1"*. | Deep OS automation via `AccessibilityService`. |
| **8. WhatsApp Automation** | Speak: *"Send WhatsApp to [Contact] saying I am running late"* | Automatically opens chat, enters text, and awaits confirmation. | Real-world daily independence. |
| **9. Lecture Recording** | Speak: *"Record lecture"*, then *"Stop recording"* | Begins recording audio file to internal storage and stops with confirmation. | Practical utility for students/professionals. |

---

## 7. Known Limitations & Permissions

- **Accessibility Service**: Deep app automation (WhatsApp, YouTube interaction) requires enabling the *Blind AI Assistant Screen Reader & Automation* service in Android Settings > Accessibility.
- **Telephony & SMS**: Direct cellular phone calls and SMS SOS require an active SIM card.
- **Android Version**: Optimized for Android 10 (API 29) through Android 15 (API 35/36).

---

## 8. License

Developed for the **Alibaba Cloud AI Hackathon Pakistan 2026**.  
Licensed under the [Apache 2.0 License](LICENSE).
