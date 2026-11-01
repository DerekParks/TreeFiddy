# TreeFiddy 🌳💵

On-device political-spam filter for Android — *ain't givin' 'em no tree fiddy.*

A distilled MobileBERT (int8 LiteRT, ~28 MB) classifies incoming texts entirely on
your phone. Confident political spam is marked read and, if you opt in, answered
with `STOP`. The app has **no INTERNET permission** — it can't send your messages
anywhere, and you can check that yourself (see [Privacy](#privacy)).

## Install

Grab `TreeFiddy-<version>.apk` from [Releases](../../releases) and sideload it
(arm64 devices, Android 8.0+). Then:

1. **Notification access** — Settings → Notifications → Device & app notifications →
   Special app access → Notification access → enable **TreeFiddy**.
2. **SMS permission** — the app reads the full message body from the SMS provider,
   because notification previews truncate long texts (the "ask" is usually at the end).
   Android treats READ_SMS as a *restricted* permission for sideloaded apps: if the
   grant is greyed out, open App info → ⋮ → **Allow restricted settings** first.

Works with Google Messages and Samsung Messages: TreeFiddy acts through the
notification's own "Mark as read" and inline-reply actions, so it never needs to
be your default SMS app.

## What it does

- **Classifies** each incoming text; long texts are scored as head + tail windows.
- **Marks read** messages scoring at or above the action threshold (slider, 0.50–1.00).
- **Replies STOP** (off by default) above the same threshold, then leaves that sender
  alone for 30 minutes so the "you've been unsubscribed" confirmation isn't re-STOPped.
- **Never acts on**
  - verification-code texts without a link (OTPs, bank codes);
  - anyone you've texted back — a two-way conversation is never spam.
- **Watches its own permissions** and notifies you if Android revokes SMS access.

The control panel has a kill switch, the threshold slider, counters, a recent-events
log, and a **Test a message** box that needs no permissions at all.

## Privacy

- The installed permission list is exactly `READ_SMS` and `POST_NOTIFICATIONS`.
  `INTERNET` is stripped in the manifest even if a library requests it
  (`tools:node="remove"`). Verify on a built APK:
  ```bash
  aapt2 dump permissions TreeFiddy-1.0.apk
  ```
- The event log and counters live in app-private storage; `allowBackup` is off.
- The model was trained on a private SMS corpus plus synthetic examples. No training
  data ships in this repo or the APK — only the int8 weights and the standard
  `bert-base-uncased` WordPiece vocab. A membership-inference check against the
  deployed model scored AUC 0.506 (chance is 0.5).

## Model

| | |
|---|---|
| Architecture | MobileBERT student distilled from a larger teacher, int8-quantized |
| Runtime | LiteRT 2.1.5 on CPU (XNNPACK) |
| Input | `input_ids`, `attention_mask` — int64 `[1,128]` |
| Output | float32 `[1,2]` logits `[ham, spam]` |
| Tokenizer | WordPiece, uncased, pure Kotlin, byte-identical to HuggingFace |
| Held-out F1 | 0.975 |

## Build

Needs JDK 17 and the Android SDK.

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # your SDK path
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds also accept `adb shell cmd notification post …` as a fake messaging app,
for exercising the classify path without real texts.

`./gradlew assembleRelease` signs when these are set in `~/.gradle/gradle.properties`
(otherwise it produces an unsigned APK):

```properties
treefiddy.keystore=/path/to/release.jks
treefiddy.storePassword=…
treefiddy.keyAlias=…
treefiddy.keyPassword=…
```

### Tokenizer parity test

```bash
kotlinc app/src/main/java/com/treefiddy/sms/WordpieceTokenizer.kt \
        tokenizer_test/TokenizerParityTest.kt -include-runtime -d toktest.jar
java -jar toktest.jar   # 7/7 vs HuggingFace reference ids
```

## Layout

```
app/src/main/
  assets/mobilebert_spam_int8.tflite   model (stored uncompressed for mmap)
  assets/vocab.txt                     WordPiece vocab (30522 tokens)
  java/com/treefiddy/sms/
    SpamSmsListener.kt                 notification listener: classify -> mark read / STOP
    SpamClassifier.kt                  tokenize -> LiteRT -> softmax
    WordpieceTokenizer.kt              HF-parity BERT WordPiece
    MainActivity.kt                    control panel
    MsgStore.kt, SmsAccess.kt          SMS/MMS provider reads, READ_SMS health
    AccessWatchdog.kt                  revoked-permission notification
    EventLog.kt, Settings.kt, Stats.kt
tokenizer_test/                        off-device tokenizer parity check
```

## License

[Apache-2.0](LICENSE) — code and model weights.
