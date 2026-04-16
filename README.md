# WhatsApp Notifier

An Android app that listens for WhatsApp notifications, filters them by group
and sender, and uses an LLM (any OpenAI-compatible chat-completions endpoint)
to classify whether each message matches a user-defined criteria. When a
message matches, the app posts a high-priority alert notification.

Typical use case: monitor a busy WhatsApp group for messages on a specific
topic (e.g. *"someone is looking for a flatmate"*) without reading every
message yourself.

---

## How it works

```
WhatsApp notification
        │
        ▼
WhatsAppNotificationListener   ── parses the notification (title / text /
        │                         bigText / subText / EXTRA_MESSAGES)
        ▼
MessageMatcher                 ── group-name filter + allowed-sender filter
        │
        ▼
LlmMatcher                     ── sends (match prompt + message) to an LLM,
        │                         parses {"matches": bool, "reason": string}
        ▼
AlertNotifier                  ── posts a local notification + logs to
                                  MatchHistory (shown on the main screen)
```

Only messages that pass all three stages (group, sender, LLM) trigger an
alert.

---

## Project layout

```
.
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/notifier/whatsapp/
│       │   │   ├── AlertNotifier.kt              # builds + posts alert notifications
│       │   │   ├── AppConfig.kt                  # SharedPreferences + BuildConfig defaults
│       │   │   ├── LlmMatcher.kt                 # OpenAI-compatible chat-completions client
│       │   │   ├── MainActivity.kt               # UI: permission, config, recent matches
│       │   │   ├── MatchHistory.kt               # last 20 matches, persisted
│       │   │   ├── MessageMatcher.kt             # group + sender filters
│       │   │   ├── WhatsAppNotificationListener.kt
│       │   │   └── WhatsAppNotificationParser.kt # extracts sender/body/group from Notification
│       │   └── res/
│       └── test/
│           ├── java/com/notifier/whatsapp/
│           │   ├── LlmMatcherIntegrationTest.kt  # opt-in, hits real LLM
│           │   └── MessageMatcherTest.kt         # data-driven from test_cases.json
│           └── resources/test_cases.json
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/
├── gradlew
├── .env                                          # runtime defaults baked into BuildConfig
└── local.properties                              # sdk.dir (user-local, not checked in)
```

---

## Prerequisites

- **JDK 17+** (the project compiles Kotlin/Java to target 17). Android
  Studio's bundled JBR works fine — on macOS the default location is
  `/Applications/Android Studio.app/Contents/jbr/Contents/Home`.
- **Android SDK** with platform 35 and build-tools 34. Install via Android
  Studio's SDK Manager, or let the first Gradle build auto-install them.
- **`local.properties`** at the repo root pointing at the SDK, e.g.:
  ```properties
  sdk.dir=/Users/<you>/Library/Android/sdk
  ```

---

## Configuration

Defaults are read from `.env` at build time and baked into `BuildConfig`.
The user can override any of them at runtime via the Configuration card in
the app — overrides are persisted in SharedPreferences.

`.env` keys:

| Key                | Meaning                                                                  |
|--------------------|--------------------------------------------------------------------------|
| `TARGET_GROUP`     | Exact WhatsApp group name to monitor. Blank = match any group.           |
| `ALLOWED_SENDERS`  | Comma-separated sender names, or `all` to accept any sender.             |
| `LLM_API_KEY`      | Bearer token for the LLM endpoint.                                       |
| `LLM_API_BASE_URL` | OpenAI-compatible base URL, e.g. `https://api.openai.com/v1`.            |
| `LLM_MODEL`        | Model id, e.g. `gpt-4o-mini`.                                            |
| `MATCH_PROMPT`     | Plain-English criteria; the LLM answers with `{"matches": ..., "reason": ...}`. |

If `LLM_API_KEY` is blank or left as the placeholder `your-api-key-here`,
the LLM stage is bypassed and every message that passes the group + sender
filters is treated as a match. This is useful for wiring-up / debugging.

---

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release-unsigned.apk
```

## Install & run (emulator or device)

```bash
adb devices                                                # confirm target
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.notifier.whatsapp/.MainActivity
```

Inside the app:

1. Tap **Grant** to open Settings → *Notification access* and enable
   **WhatsApp Notifier**. Without this permission the listener can't see any
   notifications.
2. Review the Configuration card, tweak if needed, tap **Save**.
3. Trigger a test message in WhatsApp. Matches appear under **Recent
   Matches** and as a high-priority system notification.

---

## Testing

The matcher is covered by **data-driven** unit tests. Each case in
`app/src/test/resources/test_cases.json` specifies an `.env`-style config,
a fake WhatsApp message, and the expected filter outcomes:

```json
{
  "name": "allowed sender list: non-matching sender is filtered out",
  "config": {
    "TARGET_GROUP": "My Apartment Group",
    "ALLOWED_SENDERS": "Alice, Carol",
    "MATCH_PROMPT": "accommodation inquiries"
  },
  "message": {
    "title": "My Apartment Group",
    "text": "Bob: anyone up for hiking?",
    "bigText": "anyone up for hiking?",
    "subText": "My Apartment Group",
    "sender": "Bob",
    "messageBody": "anyone up for hiking?",
    "isGroupMessage": true
  },
  "expected": {
    "group_match": true,
    "sender_match": false
  }
}
```

**Run the offline suite** (group + sender filters — fast, no network):

```bash
./gradlew testDebugUnitTest
```

**Run the LLM integration test as well** (hits the real chat-completions
endpoint for every case that declares `llm_match`):

```bash
RUN_LLM_TESTS=1 \
LLM_API_KEY=sk-... \
LLM_API_BASE_URL=https://api.openai.com/v1 \
LLM_MODEL=gpt-4o-mini \
./gradlew testDebugUnitTest --rerun-tasks
```

Add a new case by appending an object to `test_cases.json` — no code
changes needed.

HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`.

---

## Required permissions

Declared in `AndroidManifest.xml`:

- `INTERNET` — LLM API calls.
- `POST_NOTIFICATIONS` — posting alerts (required on Android 13+).
- `BIND_NOTIFICATION_LISTENER_SERVICE` — granted by the user via Settings,
  not at install time. This is the only way Android exposes other apps'
  notifications.

---

## Troubleshooting

| Symptom                                           | Likely cause / fix                                                                             |
|---------------------------------------------------|------------------------------------------------------------------------------------------------|
| Status dot stays red / "Not granted"              | Notification access not enabled — tap **Grant** and toggle **WhatsApp Notifier** on.           |
| No alerts despite messages in WhatsApp            | `TARGET_GROUP` doesn't match the WhatsApp group name exactly (case-insensitive). Leave blank to wildcard. |
| Every message triggers an alert                   | `LLM_API_KEY` is blank / placeholder, so the LLM stage is bypassed (pass-through).             |
| `LLM API error 401/403`                           | Key invalid or model not available on the endpoint. Check `LLM_API_BASE_URL` + `LLM_MODEL`.    |
| `SDK XML version 4` warning during build          | Build-tools / SDK version mismatch — harmless; Gradle auto-installs the required components.  |
| `Unresolved reference: dependencyResolution`      | You're on Gradle < 9. `settings.gradle.kts` uses `dependencyResolutionManagement` (correct).   |

---

## License

No license specified. Treat as "all rights reserved" until one is added.
