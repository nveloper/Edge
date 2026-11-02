// JNI bridge between node.nveloper.edge.llama.LlamaNative and llama.cpp.
//
// Kept deliberately thin: Kotlin owns the policy (slot reuse, context fitting, streaming,
// cancellation); this file only exposes model/context/sampler handles and single steps.
// Every function is called from one dedicated Kotlin thread, so there is no locking here.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <string>
#include <vector>

#include "chat.h"
#include "ggml-backend.h"
#include "llama.h"
#include "mtmd-helper.h"

#define TAG "Edge/llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Loaded model; edge_chat.cpp declares the same struct.
struct EdgeModel {
    llama_model * model = nullptr;
    common_chat_templates_ptr templates;
    std::string device; // backend actually used, e.g. "Vulkan0" or "CPU"
};
using Model = EdgeModel;

namespace {

// Last error logged by llama.cpp/ggml, so a failed load can tell the user why.
std::string g_last_error;
bool g_in_error = false;

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        g_last_error = text;
        g_in_error = true;
    } else if (level == GGML_LOG_LEVEL_CONT && g_in_error) {
        g_last_error += text;
    } else {
        g_in_error = false;
    }
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: break;
    }
    __android_log_write(prio, TAG, text);
}

std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring to_jstring(JNIEnv * env, const std::string & s) {
    // NewStringUTF expects modified UTF-8; go through a byte array to accept any UTF-8.
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), (const jbyte *) s.data());
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = (jstring) env->NewObject(string_class, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    return result;
}

Model * as_model(jlong h) { return reinterpret_cast<Model *>(h); }
llama_context * as_ctx(jlong h) { return reinterpret_cast<llama_context *>(h); }
llama_sampler * as_sampler(jlong h) { return reinterpret_cast<llama_sampler *>(h); }

const char * dev_type_name(enum ggml_backend_dev_type t) {
    switch (t) {
        case GGML_BACKEND_DEVICE_TYPE_CPU:   return "CPU";
        case GGML_BACKEND_DEVICE_TYPE_GPU:   return "GPU";
        case GGML_BACKEND_DEVICE_TYPE_IGPU:  return "IGPU";
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "ACCEL";
        default:                             return "OTHER";
    }
}

} // namespace

// Helpers shared with edge_chat.cpp.
std::string edge_to_string(JNIEnv * env, jstring s) { return to_string(env, s); }
jstring edge_to_jstring(JNIEnv * env, const std::string & s) { return to_jstring(env, s); }
void edge_set_error(const std::string & message) { g_last_error = message; }

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_node_nveloper_edge_llama_LlamaNative_##name

// Loads every libggml-*.so backend found in the app's native library directory.
JNI(void, init)(JNIEnv * env, jobject, jstring native_lib_dir) {
    llama_log_set(log_callback, nullptr);
    mtmd_helper_log_set(log_callback, nullptr);
    std::string dir = to_string(env, native_lib_dir);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    LOGI("backends loaded from %s, %zu device(s)", dir.c_str(), ggml_backend_dev_count());
}

// One line per device: "<backend>\t<name>\t<type>\t<description>\t<free bytes>\t<total bytes>"
JNI(jobjectArray, devices)(JNIEnv * env, jobject) {
    const size_t n = ggml_backend_dev_count();
    jobjectArray out = env->NewObjectArray((jsize) n, env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < n; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        size_t free = 0, total = 0;
        ggml_backend_dev_memory(dev, &free, &total);
        std::string line = std::string(ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev))) + "\t" +
                           ggml_backend_dev_name(dev) + "\t" + dev_type_name(ggml_backend_dev_type(dev)) + "\t" +
                           ggml_backend_dev_description(dev) + "\t" + std::to_string(free) + "\t" + std::to_string(total);
        env->SetObjectArrayElement(out, (jsize) i, to_jstring(env, line));
    }
    return out;
}

// backend: "Vulkan", "OpenCL", or empty for CPU only. Returns 0 on failure.
// Error text llama.cpp logged most recently (empty if none); trailing whitespace trimmed.
JNI(jstring, lastError)(JNIEnv * env, jobject) {
    std::string s = g_last_error;
    while (!s.empty() && (s.back() == '\n' || s.back() == ' ')) s.pop_back();
    return to_jstring(env, s);
}

JNI(jlong, loadModel)(JNIEnv * env, jobject, jstring jpath, jstring jbackend, jint n_gpu_layers, jboolean use_mmap) {
    g_last_error.clear();
    const std::string path = to_string(env, jpath);
    const std::string backend = to_string(env, jbackend);

    std::vector<ggml_backend_dev_t> devices;
    std::string used = "CPU";
    if (!backend.empty()) {
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            auto type = ggml_backend_dev_type(dev);
            if (backend == ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev)) &&
                (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU)) {
                devices.push_back(dev);
                used = ggml_backend_dev_name(dev);
                break;
            }
        }
        if (devices.empty()) {
            LOGE("no %s device available", backend.c_str());
            return 0;
        }
    }
    devices.push_back(nullptr); // NULL-terminated; an empty list keeps everything on the CPU

    llama_model_params params = llama_model_default_params();
    params.devices = devices.data();
    params.n_gpu_layers = backend.empty() ? 0 : n_gpu_layers;
    params.load_mode = use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    llama_model * model = llama_model_load_from_file(path.c_str(), params);
    if (model == nullptr) {
        LOGE("failed to load %s", path.c_str());
        return 0;
    }
    auto * handle = new Model();
    handle->model = model;
    handle->device = used;
    try {
        handle->templates = common_chat_templates_init(model, "");
    } catch (const std::exception & e) {
        LOGW("chat template init failed: %s", e.what());
    }
    LOGI("loaded %s on %s", path.c_str(), used.c_str());
    return reinterpret_cast<jlong>(handle);
}

JNI(void, freeModel)(JNIEnv *, jobject, jlong h) {
    Model * m = as_model(h);
    if (m == nullptr) return;
    m->templates.reset();
    llama_model_free(m->model);
    delete m;
}

// "<description>\t<params>\t<bytes>\t<train ctx>\t<device>\t<has template>"
JNI(jstring, modelInfo)(JNIEnv * env, jobject, jlong h) {
    Model * m = as_model(h);
    char desc[256] = {0};
    llama_model_desc(m->model, desc, sizeof(desc));
    const bool has_template = llama_model_chat_template(m->model, nullptr) != nullptr;
    std::string s = std::string(desc) + "\t" + std::to_string(llama_model_n_params(m->model)) + "\t" +
                    std::to_string(llama_model_size(m->model)) + "\t" + std::to_string(llama_model_n_ctx_train(m->model)) +
                    "\t" + m->device + "\t" + (has_template ? "1" : "0");
    return to_jstring(env, s);
}

// Renders the conversation with the model's own (Jinja) chat template. Returns null on failure.
JNI(jstring, applyTemplate)(JNIEnv * env, jobject, jlong h, jobjectArray roles, jobjectArray contents, jboolean add_assistant) {
    Model * m = as_model(h);
    if (!m->templates) return nullptr;
    common_chat_templates_inputs inputs;
    const jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg msg;
        msg.role = to_string(env, (jstring) env->GetObjectArrayElement(roles, i));
        msg.content = to_string(env, (jstring) env->GetObjectArrayElement(contents, i));
        inputs.messages.push_back(std::move(msg));
    }
    inputs.add_generation_prompt = add_assistant;
    for (bool jinja : {true, false}) {
        inputs.use_jinja = jinja;
        try {
            return to_jstring(env, common_chat_templates_apply(m->templates.get(), inputs).prompt);
        } catch (const std::exception & e) {
            LOGW("chat template (jinja=%d) failed: %s", jinja, e.what());
        }
    }
    return nullptr;
}

JNI(jintArray, tokenize)(JNIEnv * env, jobject, jlong h, jstring jtext, jboolean add_special) {
    const llama_vocab * vocab = llama_model_get_vocab(as_model(h)->model);
    const std::string text = to_string(env, jtext);
    int32_t n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(std::max(n, 0));
    if (n > 0) llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, add_special, true);
    jintArray out = env->NewIntArray((jsize) tokens.size());
    env->SetIntArrayRegion(out, 0, (jsize) tokens.size(), tokens.data());
    return out;
}

// kv_q8: store the KV cache as Q8_0 (flash attention is enabled for it automatically).
JNI(jlong, newContext)(JNIEnv *, jobject, jlong h, jint n_ctx, jint n_batch, jint n_threads, jboolean kv_q8) {
    llama_context_params params = llama_context_default_params();
    params.n_ctx = (uint32_t) n_ctx;
    params.n_batch = (uint32_t) n_batch;
    params.n_ubatch = (uint32_t) std::min(n_batch, 512);
    params.n_seq_max = 1;
    params.n_threads = n_threads;
    params.n_threads_batch = n_threads;
    params.no_perf = true;
    if (kv_q8) {
        params.type_k = GGML_TYPE_Q8_0;
        params.type_v = GGML_TYPE_Q8_0;
    }
    llama_context * ctx = llama_init_from_model(as_model(h)->model, params);
    if (ctx == nullptr) LOGE("failed to create context (n_ctx=%d)", n_ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNI(void, freeContext)(JNIEnv *, jobject, jlong c) {
    if (c != 0) llama_free(as_ctx(c));
}

// Sliding-window size of the model (0 if it has none).
JNI(jint, modelNSwa)(JNIEnv *, jobject, jlong h) { return llama_model_n_swa(as_model(h)->model); }

// Lowest position still held in the cache; with sliding-window layers older positions are gone.
JNI(jint, memoryPosMin)(JNIEnv *, jobject, jlong c) {
    return llama_memory_seq_pos_min(llama_get_memory(as_ctx(c)), 0);
}

// Drops KV entries from position `pos` on, keeping the prefix for reuse.
JNI(jboolean, memoryRemoveFrom)(JNIEnv *, jobject, jlong c, jint pos) {
    llama_memory_t mem = llama_get_memory(as_ctx(c));
    if (pos <= 0) {
        llama_memory_clear(mem, true);
        return JNI_TRUE;
    }
    return llama_memory_seq_rm(mem, 0, pos, -1) ? JNI_TRUE : JNI_FALSE;
}

// Feeds tokens[offset, offset+count) in chunks of n_batch. Returns llama_decode's status (0 = ok).
JNI(jint, decode)(JNIEnv * env, jobject, jlong c, jintArray jtokens, jint offset, jint count, jint n_batch) {
    std::vector<llama_token> tokens(count);
    env->GetIntArrayRegion(jtokens, offset, count, tokens.data());
    for (int i = 0; i < count; i += n_batch) {
        const int n = std::min(n_batch, count - i);
        const int32_t rc = llama_decode(as_ctx(c), llama_batch_get_one(tokens.data() + i, n));
        if (rc != 0) {
            LOGE("llama_decode failed (%d) at %d/%d", rc, i, count);
            return rc;
        }
    }
    return 0;
}

JNI(jboolean, isEog)(JNIEnv *, jobject, jlong h, jint token) {
    return llama_vocab_is_eog(llama_model_get_vocab(as_model(h)->model), token) ? JNI_TRUE : JNI_FALSE;
}

// Raw UTF-8 bytes of a token. A token can hold part of a multi-byte character; Kotlin reassembles.
JNI(jbyteArray, tokenPiece)(JNIEnv * env, jobject, jlong h, jint token) {
    const llama_vocab * vocab = llama_model_get_vocab(as_model(h)->model);
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    std::string piece;
    if (n < 0) {
        piece.resize(-n);
        n = llama_token_to_piece(vocab, token, piece.data(), (int32_t) piece.size(), 0, false);
    } else {
        piece.assign(buf, n);
    }
    jbyteArray out = env->NewByteArray((jsize) piece.size());
    env->SetByteArrayRegion(out, 0, (jsize) piece.size(), (const jbyte *) piece.data());
    return out;
}
