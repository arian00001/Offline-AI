#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <algorithm>
#include <mutex>
#include <atomic>
#include <cstddef>
#include <android/log.h>
#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DEVSTYLE-LLM", __VA_ARGS__)
static llama_model *g_model = nullptr;
static std::mutex g_mutex;
static std::atomic<bool> g_cancel{false};

static std::string jstringToString(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_site_devstyle_aiserver_NativeLlm_loadModel(JNIEnv *env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_init();
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    const std::string modelPath = jstringToString(env, path);
    g_model = llama_model_load_from_file(modelPath.c_str(), params);
    if (!g_model) { LOGE("Could not load model: %s", modelPath.c_str()); return JNI_FALSE; }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_site_devstyle_aiserver_NativeLlm_unloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_site_devstyle_aiserver_NativeLlm_cancel(JNIEnv *, jobject) { g_cancel.store(true); }

extern "C" JNIEXPORT jstring JNICALL
Java_site_devstyle_aiserver_NativeLlm_generate(JNIEnv *env, jobject, jstring prompt, jobject callback, jint maxTokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return env->NewStringUTF("[ERROR] No model loaded. Load a GGUF model first.");
    g_cancel.store(false);
    const std::string userPrompt = jstringToString(env, prompt);
    const std::string formatted = "<|im_start|>system\nYou are DEV STYLE, a helpful assistant. Reply in the user's language. Be accurate and concise. /no_think<|im_end|>\n<|im_start|>user\n" + userPrompt + "<|im_end|>\n<|im_start|>assistant\n";
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(formatted.size() + 16);
    int nTokens = llama_tokenize(vocab, formatted.c_str(), (int)formatted.size(), tokens.data(), (int)tokens.size(), true, true);
    if (nTokens < 0) {
        tokens.resize((size_t)(-nTokens));
        nTokens = llama_tokenize(vocab, formatted.c_str(), (int)formatted.size(), tokens.data(), (int)tokens.size(), true, true);
    }
    if (nTokens <= 0) return env->NewStringUTF("[ERROR] Prompt tokenization failed.");
    tokens.resize((size_t)nTokens);
    // Keep enough room for generated tokens and avoid passing a batch larger than n_batch.
    constexpr size_t kMaxPromptTokens = 1792;
    if (tokens.size() > kMaxPromptTokens) {
        tokens.erase(tokens.begin(), tokens.end() - (ptrdiff_t)kMaxPromptTokens);
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 2048;
    // Use most CPU cores while leaving capacity for Android/UI.
    const int cpuCount = (int) std::thread::hardware_concurrency();
    const int inferenceThreads = std::max(2, std::min(6, cpuCount > 2 ? cpuCount - 2 : 2));
    cparams.n_threads = inferenceThreads;
    cparams.n_threads_batch = inferenceThreads;
    llama_context *ctx = llama_init_from_model(g_model, cparams);
    if (!ctx) return env->NewStringUTF("[ERROR] Not enough memory to create context. Try a smaller model or restart the app.");
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.65f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(1234));
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        llama_sampler_free(sampler); llama_free(ctx);
        return env->NewStringUTF("[ERROR] Prompt decode failed.");
    }
    jclass cbClass = callback ? env->GetObjectClass(callback) : nullptr;
    jmethodID onToken = cbClass ? env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V") : nullptr;
    std::string result;
    for (int i = 0; i < maxTokens && !g_cancel.load(); ++i) {
        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        llama_sampler_accept(sampler, token);
        char piece[512];
        int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) {
            std::string chunk(piece, (size_t)n);
            result += chunk;
            if (callback && onToken) {
                jstring jchunk = env->NewStringUTF(chunk.c_str());
                env->CallVoidMethod(callback, onToken, jchunk);
                env->DeleteLocalRef(jchunk);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
        }
        llama_token next = token;
        batch = llama_batch_get_one(&next, 1);
        if (llama_decode(ctx, batch) != 0) break;
    }
    llama_sampler_free(sampler);
    llama_free(ctx);
    return env->NewStringUTF(result.c_str());
}
