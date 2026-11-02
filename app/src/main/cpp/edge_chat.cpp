// OpenAI-style chat on top of llama.cpp's common library, the same pieces llama-server uses:
// Jinja templates with tools / JSON schema / reasoning, grammar-constrained sampling
// (common_sampler), incremental parsing of the output into content, reasoning_content and
// tool_calls (common_chat_parse + compute_diffs), plus logprobs, multimodal input (mtmd) and
// embeddings. Kotlin keeps the policy (slots, context fitting, stop strings, streaming).

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <random>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "sampling.h"

#define TAG "Edge/chat"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_node_nveloper_edge_llama_LlamaNative_##name

// Shared with edge_llama.cpp.
struct EdgeModel {
    llama_model * model = nullptr;
    common_chat_templates_ptr templates;
    std::string device;
};
std::string edge_to_string(JNIEnv * env, jstring s);
jstring edge_to_jstring(JNIEnv * env, const std::string & s);
void edge_set_error(const std::string & message);

namespace {

// One request's chat state: the rendered prompt, what the sampler must enforce, and the parser
// that turns generated text into OpenAI message deltas.
struct Chat {
    common_chat_params params;
    common_chat_parser_params parser;
    bool tools = false;
    std::string generated;
    common_chat_msg msg;
    std::vector<std::string> tool_ids;
};

EdgeModel * as_model(jlong h) { return reinterpret_cast<EdgeModel *>(h); }
Chat * as_chat(jlong h) { return reinterpret_cast<Chat *>(h); }
llama_context * as_ctx(jlong h) { return reinterpret_cast<llama_context *>(h); }

std::string random_id() {
    static const char chars[] = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static std::mt19937 rng{std::random_device{}()};
    std::uniform_int_distribution<int> pick(0, sizeof(chars) - 2);
    std::string id = "call_";
    for (int i = 0; i < 24; i++) id += chars[pick(rng)];
    return id;
}

common_json diffs_json(const std::vector<common_chat_msg_diff> & diffs) {
    auto out = common_json::array();
    for (const auto & d : diffs) {
        if (!d.reasoning_content_delta.empty()) out.push_back({{"reasoning_content", d.reasoning_content_delta}});
        if (!d.content_delta.empty()) out.push_back({{"content", d.content_delta}});
        if (d.tool_call_index != std::string::npos) {
            out.push_back({{"tool_call", common_json{
                {"index", (int) d.tool_call_index},
                {"id", d.tool_call_delta.id},
                {"name", d.tool_call_delta.name},
                {"arguments", d.tool_call_delta.arguments},
            }}});
        }
    }
    return out;
}

} // namespace

// Builds the prompt for an OpenAI chat request (see LlamaChat.kt for the JSON shape).
// Returns 0 and sets the last error on invalid input.
JNI(jlong, chatPrepare)(JNIEnv * env, jobject, jlong h, jstring jrequest) {
    EdgeModel * m = as_model(h);
    if (!m->templates) {
        edge_set_error("model has no usable chat template");
        return 0;
    }
    try {
        const auto body = common_json::parse(edge_to_string(env, jrequest));
        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(body.at("messages"));
        if (body.contains("tools")) inputs.tools = common_chat_tools_parse_oaicompat(body.at("tools"));
        inputs.tool_choice = common_chat_tool_choice_parse_oaicompat(body.value("tool_choice", std::string("auto")));
        if (body.contains("parallel_tool_calls")) inputs.parallel_tool_calls = body.at("parallel_tool_calls").get<bool>();
        if (body.contains("json_schema")) inputs.json_schema = body.at("json_schema").dump();
        inputs.use_jinja = true;
        const bool continuation = body.value("continue_final", false);
        inputs.add_generation_prompt = !continuation;
        inputs.continue_final_message = continuation ? COMMON_CHAT_CONTINUATION_AUTO : COMMON_CHAT_CONTINUATION_NONE;
        inputs.reasoning_format = common_reasoning_format_from_name(body.value("reasoning_format", std::string("deepseek")));
        inputs.enable_thinking = body.value("enable_thinking", true);
        if (body.contains("chat_template_kwargs")) {
            for (const auto & [key, val] : body.at("chat_template_kwargs").items()) inputs.chat_template_kwargs[key] = val.dump();
        }
        const auto thinking = inputs.chat_template_kwargs.find("enable_thinking");
        if (thinking != inputs.chat_template_kwargs.end()) {
            if (thinking->second == "true") inputs.enable_thinking = true;
            if (thinking->second == "false") inputs.enable_thinking = false;
        }
        const std::string effort = body.value("reasoning_effort", std::string());
        if (effort == "none") {
            inputs.enable_thinking = false;
        } else if (!effort.empty()) {
            inputs.chat_template_kwargs["reasoning_effort"] = common_json(effort).dump();
        }

        auto * chat = new Chat();
        chat->params = common_chat_templates_apply(m->templates.get(), inputs);
        chat->tools = !inputs.tools.empty() && inputs.tool_choice != COMMON_CHAT_TOOL_CHOICE_NONE;
        chat->parser = common_chat_parser_params(chat->params);
        chat->parser.reasoning_format = inputs.reasoning_format;
        chat->parser.parse_tool_calls = chat->tools;
        chat->parser.is_continuation = continuation;
        if (!chat->params.parser.empty()) chat->parser.parser.load(chat->params.parser);
        if (continuation) chat->msg = common_chat_parse("", true, chat->parser); // don't echo the prefill
        return reinterpret_cast<jlong>(chat);
    } catch (const std::exception & e) {
        edge_set_error(e.what());
        LOGW("chat request rejected: %s", e.what());
        return 0;
    }
}

JNI(void, chatFree)(JNIEnv *, jobject, jlong c) { delete as_chat(c); }

JNI(jstring, chatPrompt)(JNIEnv * env, jobject, jlong c) { return edge_to_jstring(env, as_chat(c)->params.prompt); }

// Stop strings the template itself needs (e.g. end-of-turn markers that are not EOG tokens).
JNI(jobjectArray, chatStops)(JNIEnv * env, jobject, jlong c) {
    const auto & stops = as_chat(c)->params.additional_stops;
    jobjectArray out = env->NewObjectArray((jsize) stops.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < stops.size(); i++) env->SetObjectArrayElement(out, (jsize) i, edge_to_jstring(env, stops[i]));
    return out;
}

// Appends generated text and returns the new message deltas as a JSON array of
// {"content"} / {"reasoning_content"} / {"tool_call": {index, id, name, arguments}}.
JNI(jstring, chatUpdate)(JNIEnv * env, jobject, jlong c, jstring jtext, jboolean partial) {
    Chat * chat = as_chat(c);
    chat->generated += edge_to_string(env, jtext);
    try {
        auto next = common_chat_parse(chat->generated, partial, chat->parser);
        if (next.empty()) return edge_to_jstring(env, "[]");
        next.set_tool_call_ids(chat->tool_ids, random_id);
        auto diffs = common_chat_msg_diff::compute_diffs(chat->msg, next);
        chat->msg = next;
        return edge_to_jstring(env, diffs_json(diffs).dump());
    } catch (const std::exception & e) {
        if (partial) return edge_to_jstring(env, "[]"); // incomplete output, try again with more text
        // Unparseable final output: hand back whatever is left as plain content.
        LOGW("chat output did not parse: %s", e.what());
        std::string rest = chat->generated.size() > chat->msg.content.size() ? chat->generated.substr(chat->msg.content.size()) : "";
        auto out = common_json::array();
        if (!rest.empty()) out.push_back({{"content", rest}});
        return edge_to_jstring(env, out.dump());
    }
}

// Sampler for one request: temperature/top-k/top-p/min-p, frequency/presence penalties,
// logit bias, and the grammar the chat template asked for (tool calls / JSON schema).
JNI(jlong, chatSampler)(JNIEnv * env, jobject, jlong h, jlong c, jfloat temp, jint top_k, jfloat top_p, jfloat min_p,
                        jfloat freq, jfloat presence, jint seed, jintArray bias_ids, jfloatArray bias_values) {
    EdgeModel * m = as_model(h);
    const llama_vocab * vocab = llama_model_get_vocab(m->model);
    common_params_sampling sp;
    sp.temp = temp;
    sp.top_k = top_k;
    sp.top_p = top_p;
    sp.min_p = min_p;
    sp.penalty_freq = freq;
    sp.penalty_present = presence;
    sp.seed = (uint32_t) seed;
    sp.no_perf = true;
    const jsize n_bias = env->GetArrayLength(bias_ids);
    if (n_bias > 0) {
        std::vector<jint> ids(n_bias);
        std::vector<jfloat> values(n_bias);
        env->GetIntArrayRegion(bias_ids, 0, n_bias, ids.data());
        env->GetFloatArrayRegion(bias_values, 0, n_bias, values.data());
        for (jsize i = 0; i < n_bias; i++) sp.logit_bias.push_back({ids[i], values[i]});
    }
    if (Chat * chat = as_chat(c)) {
        const auto & p = chat->params;
        for (const auto & t : p.preserved_tokens) {
            auto ids = common_tokenize(vocab, t, false, true);
            if (ids.size() == 1) sp.preserved_tokens.insert(ids[0]);
        }
        if (!p.grammar.empty()) {
            sp.grammar = common_grammar(chat->tools ? COMMON_GRAMMAR_TYPE_TOOL_CALLS : COMMON_GRAMMAR_TYPE_OUTPUT_FORMAT, p.grammar);
            sp.grammar_lazy = p.grammar_lazy;
            for (const auto & trigger : p.grammar_triggers) {
                // A trigger word that is a single preserved token is matched as that token.
                if (trigger.type == COMMON_GRAMMAR_TRIGGER_TYPE_WORD) {
                    auto ids = common_tokenize(vocab, trigger.value, false, true);
                    if (ids.size() == 1 && sp.preserved_tokens.count(ids[0])) {
                        sp.grammar_triggers.push_back({COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN, trigger.value, ids[0]});
                        continue;
                    }
                }
                sp.grammar_triggers.push_back(trigger);
            }
        }
        sp.generation_prompt = p.generation_prompt;
    }
    common_sampler * smpl = common_sampler_init(m->model, sp);
    if (smpl == nullptr) edge_set_error("invalid sampling parameters or grammar");
    return reinterpret_cast<jlong>(smpl);
}

JNI(void, samplerFree)(JNIEnv *, jobject, jlong s) {
    if (s != 0) common_sampler_free(reinterpret_cast<common_sampler *>(s));
}

// Samples from the last decoded position and records the token in the sampler and grammar.
JNI(jint, samplerSample)(JNIEnv *, jobject, jlong s, jlong c) {
    auto * smpl = reinterpret_cast<common_sampler *>(s);
    const llama_token token = common_sampler_sample(smpl, as_ctx(c), -1);
    common_sampler_accept(smpl, token, true);
    return token;
}

// log-softmax of the raw logits at the last position: [logprob(token), id1, lp1, id2, lp2, ...]
// for the n_top most likely tokens (ids are exact in a float below 2^24).
JNI(jfloatArray, tokenLogprobs)(JNIEnv * env, jobject, jlong c, jlong h, jint token, jint n_top) {
    llama_context * ctx = as_ctx(c);
    const int n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(as_model(h)->model));
    const float * logits = llama_get_logits_ith(ctx, -1);
    float max_logit = -INFINITY;
    for (int i = 0; i < n_vocab; i++) max_logit = std::max(max_logit, logits[i]);
    double sum = 0;
    for (int i = 0; i < n_vocab; i++) sum += std::exp((double) logits[i] - max_logit);
    const float log_z = max_logit + (float) std::log(sum);
    std::vector<int> top(n_vocab);
    for (int i = 0; i < n_vocab; i++) top[i] = i;
    const int k = std::min<int>(std::max(n_top, 0), n_vocab);
    std::partial_sort(top.begin(), top.begin() + k, top.end(), [&](int a, int b) { return logits[a] > logits[b]; });
    std::vector<float> out = {logits[token] - log_z};
    for (int i = 0; i < k; i++) {
        out.push_back((float) top[i]);
        out.push_back(logits[top[i]] - log_z);
    }
    jfloatArray arr = env->NewFloatArray((jsize) out.size());
    env->SetFloatArrayRegion(arr, 0, (jsize) out.size(), out.data());
    return arr;
}

//
// Multimodal (image/audio) input through mtmd and an mmproj file.
//

JNI(jstring, mediaMarker)(JNIEnv * env, jobject) { return edge_to_jstring(env, mtmd_default_marker()); }

JNI(jlong, mtmdLoad)(JNIEnv * env, jobject, jlong h, jstring jpath, jboolean use_gpu, jint n_threads) {
    auto params = mtmd_context_params_default();
    params.use_gpu = use_gpu;
    params.n_threads = n_threads;
    params.print_timings = false;
    params.warmup = false;
    const std::string path = edge_to_string(env, jpath);
    mtmd_context * ctx = mtmd_init_from_file(path.c_str(), as_model(h)->model, params);
    if (ctx == nullptr) LOGE("failed to load mmproj %s", path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

JNI(void, mtmdFree)(JNIEnv *, jobject, jlong m) {
    if (m != 0) mtmd_free(reinterpret_cast<mtmd_context *>(m));
}

JNI(jboolean, mtmdSupportsAudio)(JNIEnv *, jobject, jlong m) {
    return mtmd_support_audio(reinterpret_cast<mtmd_context *>(m)) ? JNI_TRUE : JNI_FALSE;
}

// Splits a prompt with media markers into text/image chunks. Returns 0 on failure.
JNI(jlong, mtmdTokenize)(JNIEnv * env, jobject, jlong m, jstring jprompt, jobjectArray media) {
    auto * mctx = reinterpret_cast<mtmd_context *>(m);
    const std::string prompt = edge_to_string(env, jprompt);
    std::vector<mtmd_bitmap *> bitmaps;
    const jsize n = env->GetArrayLength(media);
    bool ok = true;
    for (jsize i = 0; i < n && ok; i++) {
        auto bytes = (jbyteArray) env->GetObjectArrayElement(media, i);
        const jsize len = env->GetArrayLength(bytes);
        std::vector<unsigned char> buf(len);
        env->GetByteArrayRegion(bytes, 0, len, (jbyte *) buf.data());
        auto wrapped = mtmd_helper_bitmap_init_from_buf(mctx, buf.data(), buf.size(), false, mtmd_helper_init_opt_default());
        if (wrapped.bitmap == nullptr) {
            edge_set_error("could not decode media item " + std::to_string(i + 1) + " (supported: jpg, png, bmp, gif, wav, mp3, flac)");
            ok = false;
        } else {
            bitmaps.push_back(wrapped.bitmap);
        }
    }
    mtmd_input_chunks * chunks = nullptr;
    if (ok) {
        chunks = mtmd_input_chunks_init();
        mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
        const int32_t rc = mtmd_tokenize(mctx, chunks, &text, (const mtmd_bitmap **) bitmaps.data(), bitmaps.size());
        if (rc != 0) {
            edge_set_error(rc == 1 ? "number of media items does not match the prompt" : "media preprocessing failed");
            mtmd_input_chunks_free(chunks);
            chunks = nullptr;
        }
    }
    for (auto * b : bitmaps) mtmd_bitmap_free(b);
    return reinterpret_cast<jlong>(chunks);
}

JNI(void, chunksFree)(JNIEnv *, jobject, jlong ch) {
    if (ch != 0) mtmd_input_chunks_free(reinterpret_cast<mtmd_input_chunks *>(ch));
}

// Text tokens before the first media chunk: the part of the prompt that can reuse a cached KV prefix.
JNI(jintArray, chunksPrefixTokens)(JNIEnv * env, jobject, jlong ch) {
    auto * chunks = reinterpret_cast<mtmd_input_chunks *>(ch);
    std::vector<llama_token> tokens;
    for (size_t i = 0; i < mtmd_input_chunks_size(chunks); i++) {
        const auto * chunk = mtmd_input_chunks_get(chunks, i);
        if (mtmd_input_chunk_get_type(chunk) != MTMD_INPUT_CHUNK_TYPE_TEXT) break;
        size_t n = 0;
        const llama_token * t = mtmd_input_chunk_get_tokens_text(chunk, &n);
        tokens.insert(tokens.end(), t, t + n);
    }
    jintArray out = env->NewIntArray((jsize) tokens.size());
    env->SetIntArrayRegion(out, 0, (jsize) tokens.size(), tokens.data());
    return out;
}

// KV positions the whole prompt takes (differs from the token count for M-RoPE models).
JNI(jint, chunksNPos)(JNIEnv *, jobject, jlong ch) {
    return (jint) mtmd_helper_get_n_pos(reinterpret_cast<mtmd_input_chunks *>(ch));
}

// Decodes the prompt from text-prefix position `keep` on: the rest of the leading text, then the
// media and text chunks after it. Returns 0 on success.
JNI(jint, chunksEval)(JNIEnv *, jobject, jlong m, jlong c, jlong ch, jint keep, jint n_batch) {
    auto * mctx = reinterpret_cast<mtmd_context *>(m);
    auto * chunks = reinterpret_cast<mtmd_input_chunks *>(ch);
    llama_context * ctx = as_ctx(c);
    llama_pos n_past = 0;
    const size_t n_chunks = mtmd_input_chunks_size(chunks);
    size_t i = 0;
    // Leading text: skip what is already in the KV cache.
    for (; i < n_chunks; i++) {
        const auto * chunk = mtmd_input_chunks_get(chunks, i);
        if (mtmd_input_chunk_get_type(chunk) != MTMD_INPUT_CHUNK_TYPE_TEXT) break;
        size_t n = 0;
        const llama_token * t = mtmd_input_chunk_get_tokens_text(chunk, &n);
        std::vector<llama_token> rest;
        for (size_t j = 0; j < n; j++) {
            if (n_past + (llama_pos) j >= keep) rest.push_back(t[j]);
        }
        for (size_t j = 0; j < rest.size(); j += n_batch) {
            const int len = std::min<int>(n_batch, (int) (rest.size() - j));
            const int32_t rc = llama_decode(ctx, llama_batch_get_one(rest.data() + j, len));
            if (rc != 0) return rc;
        }
        n_past += (llama_pos) n;
    }
    for (; i < n_chunks; i++) {
        llama_pos next = n_past;
        const int32_t rc = mtmd_helper_eval_chunk_single(mctx, ctx, mtmd_input_chunks_get(chunks, i), n_past, 0, n_batch,
                                                         i == n_chunks - 1, &next);
        if (rc != 0) {
            LOGE("chunk %zu failed (%d)", i, rc);
            return rc;
        }
        n_past = next;
    }
    return 0;
}

//
// Embeddings (/v1/embeddings), from a model loaded for that purpose.
//

JNI(jlong, newEmbeddingContext)(JNIEnv *, jobject, jlong h, jint n_ctx, jint n_threads) {
    llama_context_params params = llama_context_default_params();
    params.n_ctx = (uint32_t) n_ctx;
    params.n_batch = (uint32_t) n_ctx; // non-causal models need the whole input in one ubatch
    params.n_ubatch = (uint32_t) n_ctx;
    params.n_seq_max = 1;
    params.n_threads = n_threads;
    params.n_threads_batch = n_threads;
    params.embeddings = true;
    params.no_perf = true;
    llama_context * ctx = llama_init_from_model(as_model(h)->model, params);
    if (ctx == nullptr) LOGE("failed to create embedding context (n_ctx=%d)", n_ctx);
    return reinterpret_cast<jlong>(ctx);
}

// Pooled embedding of one input (last-token embedding for models without pooling); null on failure.
JNI(jfloatArray, embed)(JNIEnv * env, jobject, jlong c, jlong h, jintArray jtokens) {
    llama_context * ctx = as_ctx(c);
    llama_memory_clear(llama_get_memory(ctx), true);
    const jsize n = env->GetArrayLength(jtokens);
    std::vector<llama_token> tokens(n);
    env->GetIntArrayRegion(jtokens, 0, n, tokens.data());
    if (llama_decode(ctx, llama_batch_get_one(tokens.data(), n)) != 0) return nullptr;
    const float * e = llama_pooling_type(ctx) == LLAMA_POOLING_TYPE_NONE ? llama_get_embeddings_ith(ctx, -1)
                                                                         : llama_get_embeddings_seq(ctx, 0);
    if (e == nullptr) return nullptr;
    const int n_embd = llama_model_n_embd(as_model(h)->model);
    jfloatArray out = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(out, 0, n_embd, e);
    return out;
}
