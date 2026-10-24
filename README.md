# DEV STYLE AI Server — Local Chat + Runtime UI Updates

Android app built in Kotlin with a local llama.cpp/GGUF inference engine. The HTTP server binds to `127.0.0.1:8080` only.

## Features in this source

- Open `http://127.0.0.1:8080/` after starting the local server to see a white/lime glass-style AI chat UI.
- Chat history is stored by the browser in local storage on the same phone. Clearing browser/site data clears that history.
- Chat requests go to `POST /v1/chat/completions`; model inference stays on-device after a GGUF model has been downloaded and loaded.
- Settings → **Check Runtime Update** fetches `runtime/runtime.json` from this GitHub repository. It checks the SHA-256 digest before replacing only the local chat HTML. It does not download an APK or replace native Kotlin/C++ code.
- `/health`, `/v1/models`, `/v1/chat/completions` remain available for Jarvis integration.
- GitHub Actions builds a debug APK and release variant. On a `v*` tag it creates a GitHub Release only if persistent signing secrets are configured.

## Runtime update requirement

The Android app fetches:

`https://raw.githubusercontent.com/arian00001/Offline-AI/main/runtime/runtime.json`

The repo must be publicly readable at that URL for this no-token setup. If the repository is private, the runtime check will fail with an HTTP error; do not put a personal GitHub token inside the APK. Use a public runtime-only repository or a properly secured update endpoint instead.

To publish a web chat update:

1. Edit `app/src/main/assets/chat.html`.
2. Regenerate `runtime/runtime.json` so its `version` is higher and its `sha256` matches the exact HTML string in `html`.
3. Push the updated source to `main`. Runtime-only changes can be applied from Settings without installing a new APK, as long as the app already has the runtime updater.

The workflow checks the manifest/hash; a bad hash is rejected by the app and will not replace the existing UI.

## APK signing and in-place updates

Android only accepts an APK update if the `applicationId` remains `site.devstyle.aiserver`, the new `versionCode` is not lower, and the APK is signed with the same signing certificate as the installed app. See the official Android guidance: https://developer.android.com/google/play/app-updates.

Add these repository **Actions secrets** for a signed release APK:

- `ANDROID_KEYSTORE_BASE64` — base64 of the persistent `.jks`/`.keystore` file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Use the exact keystore/certificate that signed the app already installed on the phone. If that old APK was signed with a different or temporary GitHub Actions debug key, a new key cannot update it in place; Android will require uninstalling the old app. Do not lose the keystore or passwords. Never commit the keystore or passwords to the repository.

Push a tag such as `v1.1.0` to build and publish the signed release APK. Without the signing secrets, Actions still uploads the debug APK and an unsigned release APK for inspection, but it cannot publish a valid signed release update.

## Build validation

GitHub Actions validates the manifest and runtime SHA-256, then runs `assembleDebug assembleRelease`. The build must pass in GitHub Actions before treating the APK as verified. This source ZIP has not been installed and device-tested from this environment.


## Use from another phone on the same Wi-Fi

1. Install and open DEV STYLE AI Server on the server phone.
2. Download a GGUF model once, then load it.
3. Start the API server.
4. In Settings, copy **Same-Wi-Fi Chat URL** (for example `http://192.168.1.20:8080/`).
5. Connect the client phone to the same Wi-Fi/router and open that address in its browser.

The server listens on the local network (`0.0.0.0:8080`) to support this. **This build has no API authentication**; use it only on a trusted private Wi-Fi, never public Wi-Fi or a network you do not control. The server phone must remain powered on with the app/service running. Router guest-network isolation may block device-to-device access.

## Offline operation and speed

Model download and initial APK installation require internet. Once a compatible GGUF model is fully downloaded and loaded, text inference is performed on the server phone using the native llama.cpp engine and can work offline. The second phone uses Wi-Fi only to reach the server; the router does not need internet, but both devices must remain on the same local network. Response speed depends on model size, quantization, prompt length, phone temperature, and available RAM/CPU. This update uses up to six CPU inference threads while reserving cores for Android/UI; it cannot guarantee a particular response time or literally force all hardware to maximum power.


## Advanced features in this update

- Optional **Search web (online)** toggle: when enabled, the API attempts a DuckDuckGo HTML search and passes up to five snippets/URLs to the local model. Search is best-effort; websites may block automated requests and the model can still misunderstand results. Turn it off for fully local/offline chat.
- Streaming chat: generated text appears as tokens arrive, reducing perceived wait time.
- Banglish-aware system instructions: prompts the model to interpret informal romanized Bangla and reply in the same style. Actual quality still depends on model capability; a small model will not understand every dialect/spelling.
- Added optional Qwen2.5-Coder 1.5B for coding and Qwen2.5 1.5B Instruct for study/learning, alongside Qwen3 models.
- Lower default web UI output cap (192 tokens) for quicker short answers. The API still accepts a custom max_tokens value.

Important: web search requires internet and is not available offline. A local model does not automatically browse the web; the application must fetch search results and provide them as context. Search results are external, may be stale or blocked, and should be verified. Do not rely on this best-effort HTML adapter for high-stakes facts.
