# DEV STYLE AI Server

An Android/Kotlin local LLM server prototype for a single Android phone. It provides a white + lime minimal UI, model downloads, a localhost OpenAI-style chat API, streaming SSE, live request telemetry, a persistent on-device error log in Settings, and a GitHub Actions APK workflow.

## Important device expectations

- Target device: Android arm64, 8 GB RAM recommended for the Qwen3 4B Q4_K_M model.
- Qwen3 4B Q4_K_M is approximately 2.5 GB on disk; runtime memory is larger than the file size. If it fails to load or Android kills the app, use Qwen3 1.7B or 0.6B and close other apps.
- The native engine is pinned to llama.cpp `b10931` (new enough for Qwen3 while matching the JNI API used here). First build fetches llama.cpp source through CMake FetchContent, so the build requires internet. Model inference after downloading the GGUF file is local and does not need internet.
- API intentionally binds to `127.0.0.1:8080` only. That is appropriate for Jarvis running on the same phone; another phone cannot reach this endpoint. Do not expose an unauthenticated LLM API to the public internet.

## Build APK with GitHub Actions

1. Create a GitHub repository and upload this project contents (the files inside this folder, not the parent folder).
2. Push to `main` or `master`, or open Actions and manually run **DEV STYLE AI Server APK**.
3. Download the `dev-style-ai-server-debug-apk` artifact from the successful workflow run.
4. Install the APK on your arm64 Android phone.

This workflow builds a **debug APK** for testing. A release APK needs a signing keystore and separate release configuration.

## First launch

1. Open **Models**.
2. Download Qwen3 4B Q4_K_M (about 2.5 GB), or use the smaller 1.7B/0.6B model for a first test.
3. Wait for Android's download notification to report completion.
4. Tap **Load model** on the downloaded model card.
5. Return to Home and tap **Start API**.
6. Use the API playground to verify inference.

## Local API

Base URL: `http://127.0.0.1:8080/v1`

- `GET /health`
- `GET /v1/models`
- `POST /v1/chat/completions`

Non-streaming example:

```json
{
  "model": "local-assistant",
  "messages": [{"role": "user", "content": "Hello, Jarvis"}],
  "stream": false,
  "max_tokens": 256
}
```

Streaming: send the same request with `"stream": true`; the endpoint emits Server-Sent Events (`data: {...}\n\n`) and finishes with `data: [DONE]`.

For Jarvis on the **same phone**, use the base URL above. If Jarvis uses a separate Android process, localhost is still shared by apps on the same device. If you later need another phone to connect, the server must be explicitly changed to bind to the LAN interface and must add authentication/firewall controls first.

## Model sources

The model links point to the official Qwen Hugging Face repositories. Model downloads require internet and may take a long time. Model files are stored in the app's private external-files directory under `models/`.

## Known constraints of this starter implementation

- CPU-first inference for broad compatibility; GPU/Vulkan acceleration is not configured.
- The current prompt wrapper uses a Qwen-style chat format and asks Qwen3 not to emit a separate thinking trace by default for faster assistant responses.
- The model is loaded into the native engine once; each request creates a fresh inference context, which saves history between requests only if Jarvis sends previous messages in the request.
- Settings includes an Errors screen. Runtime exceptions and inference/API failures are saved locally (up to a bounded log size); they are not uploaded. You can copy or clear the log.
- The GitHub Actions workflow captures the build output as an artifact even when the build fails. It supports private repositories as long as Actions are enabled.
- Native compilation and runtime behavior still must be verified by a successful GitHub Actions run and on the target phone; no APK is claimed to have been built or device-tested from this environment.
