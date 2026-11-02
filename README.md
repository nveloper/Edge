# Edge

폰에서 직접 고른 SLM(GGUF 등)을 돌리고 **OpenAI 호환 API**로 LAN에 제공하는 Android 앱.
OpenWebUI, LiteLLM, llama-swap 등 OpenAI API를 쓰는 클라이언트에 그대로 붙인다.
기본은 **GGUF(llama.cpp)**: Hugging Face에서 원하는 모델·양자화를 받아 Vulkan/OpenCL/CPU로 돌린다.

```
[OpenWebUI 등] --HTTP--> [Foreground Service (specialUse)]
                           ├─ Ktor CIO 서버 :8080  (/v1/models, /v1/chat/completions, SSE)
                           ├─ InferenceManager    (동시 1건 + 대기열, 초과 시 429, 과열 시 503)
                           └─ InferenceBackend
                                ├─ LlamaCppBackend  .gguf, Vulkan(기본) / OpenCL / CPU, 토큰 단위 KV 재사용
                                ├─ LiteRtLmBackend  .litertlm, GPU / CPU (Edge Gallery와 같은 런타임)
                                └─ MockBackend      모델 없이 API 경로 디버그 (기본 7 tok/s)
```

## 빌드

Android Studio 의 JBR(Java 21)을 사용한다.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

- AGP 9.2.1 / Gradle 9.4.1 / Kotlin 2.3.20 / Compose BOM 2026.03.01 / NDK 28.2 / CMake 3.22
- Ktor 3.6.0 (CIO), LiteRT-LM 0.17.1, llama.cpp `v0.5.0` (`third_party/`)
- minSdk 31, targetSdk 36, ABI: arm64-v8a, x86_64

### 네이티브 빌드 (llama.cpp)

`third_party/`에 고정 버전으로 들어 있다: llama.cpp, Vulkan-Headers, SPIRV-Headers,
OpenCL-Headers, OpenCL-ICD-Loader, `shaderc/bin/glslc.exe`(Google shaderc 공식 빌드 — NDK의 glslc는 2022년판이라
ggml의 Vulkan 셰이더를 컴파일하지 못함). 로컬 수정은 [`third_party/PATCHES.md`](third_party/PATCHES.md)에 기록.

빌드 머신에 필요한 것 (경로는 `local.properties`에, 없으면 PATH에서 찾음):

| 키 | 용도 |
|---|---|
| `edge.python` | OpenCL 커널을 소스에 임베드하는 스크립트 |
| `edge.hostCc`, `edge.hostCxx` | PC에서 돌아가는 Vulkan 셰이더 생성기 빌드 (예: MinGW gcc/g++) |
| `edge.nativeJobs` | 병렬 컴파일 수 (기본 2 — RAM 8~12GB PC에서 메모리 부족 방지) |

- 백엔드는 각각 별도 `.so`로 빌드되어 실행 시 로드된다(`GGML_BACKEND_DL`). OpenCL이 없는 기기는 그 백엔드만 건너뛴다.
- CPU는 여러 ISA 변형(arm64 7종, x86_64 14종)을 빌드하고 기기에 맞는 것을 고른다.
- OpenCL ICD 로더는 링크용이고 APK에서 제외된다 — 실행 시 기기 제조사의 `libOpenCL.so`를 쓴다.
- 모든 네이티브 라이브러리는 16KB 페이지로 정렬된다.
- 처음 빌드는 15분 이상 걸린다(Vulkan 셰이더 수백 개 × 2 ABI).

## 에뮬레이터에서 API 디버그

1. **에뮬레이터 실행과 앱 설치:** AVD `Edge_API37`(API 37, x86_64, 16KB 페이지)을 띄운 뒤 설치한다.
   ```powershell
   adb install -r -g app\build\outputs\apk\debug\app-debug.apk
   ```
2. **서버 시작:** 앱에서 **서버 시작**을 누른다. 기본 백엔드는 Mock이며 서버를 시작하면 자동으로 로드된다.
3. **호스트 PC에서 전체 점검:**
   ```powershell
   .\scripts\smoke-test.ps1            # adb forward 후 전 엔드포인트 점검
   .\scripts\smoke-test.ps1 -ApiKey xxx
   ```
4. **앱 안에서 점검:** **셀프 테스트** 버튼을 누르면 앱이 루프백으로 자기 API를 호출한다. 스트리밍 여부, TTFT, 청크 수를 보여준다.
5. **로그 확인:** 요청 로그와 에러는 화면 아래쪽과 logcat 태그 `Edge/*`에 남는다.
   ```powershell
   adb logcat -s "Edge/*:*"
   ```

Mock에서 쓸 수 있는 특수 입력:
- 메시지에 `/error`: 스트림 도중 실패를 흉내 낸다.
- 메시지에 `/long`: 긴 응답을 만든다. `max_tokens`, 연결 끊김 테스트용.

### 홈랩 OpenWebUI를 에뮬레이터에 붙이기

`adb forward`는 PC의 127.0.0.1에만 바인딩된다. 다른 머신에서 접근하려면 Windows 포트프록시가 필요하다(관리자 권한, 방화벽 허용 필요).

```powershell
adb forward tcp:8080 tcp:8080
netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=18080 connectaddress=127.0.0.1 connectport=8080
```

OpenWebUI의 Admin Settings → Connections → OpenAI API에 다음을 넣는다.
- URL: `http://<PC IP>:18080/v1`
- Key: 앱에 설정한 키. 비워둔 경우 아무 값이나 넣는다.

실기기에서는 앱에 표시되는 `http://<폰 IP>:8080/v1`을 그대로 쓴다.

## 모델 받기 · 가져오기

기본은 **GGUF**: 이미 정해진 목록에서 고르는 게 아니라 원하는 모델을 직접 받는다.

- **Hugging Face에서 GGUF 찾기:** 모델 탭의 검색으로 GGUF 저장소를 찾고(다운로드 수 순), 저장소를 열면 양자화별 파일이 크기순으로 나온다.
  - 파일마다 기기 RAM 대비 **여유 / 빠듯함 / RAM 부족**과, Q4_0이면 **OpenCL 최적**(Adreno 최적화 대상) 표시.
  - 접근 승인이 필요한 저장소는 설정 탭의 HF 토큰을 쓴다. 분할 파일(`-00001-of-0000N.gguf`)은 아직 미지원.
- **파일 가져오기 / URL로 추가:** `.gguf`, `.litertlm` 모두 된다.
- **LiteRT-LM 모델 (Gemma 4 등):** AI Edge Gallery와 같은 저장소·고정 커밋의 카탈로그(접을 수 있음).
- **다운로드:** WorkManager 포그라운드 작업 — 진행 알림, `.part` + HTTP Range 이어받기, 크기 검증, HF 토큰은 `huggingface.co`에만 전송.
- **adb로 넣기 (개발용):** `.\scripts\push-model.ps1 <파일>` 후 모델 탭 새로고침.

### 가속기

| 형식 | 선택지 | 비고 |
|---|---|---|
| GGUF | **Vulkan**(기본) / OpenCL / (CPU) | GPU 로드 실패 시 자동 전환 없음. 원인과 함께 다른 GPU 가속기(또는 허용된 경우 CPU)를 고르는 확인 창을 띄움 |
| LiteRT-LM | GPU / (CPU) | LiteRT-LM GPU는 OpenCL 우선, 없으면 WebGPU(Vulkan) |

CPU 추론은 기본으로 꺼져 있다(이 앱의 목적은 GPU 서빙). 설정 → CPU 허용을 켜기 전에는 CPU 선택지가 보이지 않고, 켜도 자동으로 CPU로 넘어가지는 않는다.
서버가 백그라운드에서 재시작되다 GPU 로드에 실패하면 알림을 띄우고, 앱을 열면 확인 창이 뜬다.

### KV 캐시 형식

설정 → 추론 → KV 캐시에서 F16(기본)과 Q8_0 중 고른다. Q8_0은 KV 메모리가 약 절반이라 같은 메모리에 컨텍스트를 약 두 배로 잡을 수 있다(flash attention 자동 사용).
Vulkan에서만 적용한다. llama.cpp OpenCL 백엔드는 Adreno 7xx에서 양자화 KV용 어텐션 커널을 드라이버 문제로 쓰지 않고 CPU로 넘기므로, OpenCL에서는 F16을 유지한다.

### 자동 재시작과 크래시 한도

서버를 켜 둔 상태면 START_STICKY, 부팅/업데이트 브로드캐스트, 15분 WorkManager 감시자가 서버를 되살린다.
다음 시작 때 ApplicationExitInfo로 직전 종료 원인을 읽고, 그때 하던 일(로드/생성 · 백엔드 · 모델)과 묶어 기록한다.
같은 원인이 1시간 안에 설정한 횟수(기본 3)만큼 반복되면 자동 재시작을 멈추고 알림을 띄운다. 서버를 직접 시작하면 풀린다.
단순 SIGKILL은 제조사 절전 종료와 구별되지 않으므로 로드·생성 중일 때만 센다.

### GGUF 백엔드의 KV 재사용

llama.cpp는 토크나이저와 KV 캐시 절단을 제공하므로 llama-server의 prompt cache처럼 동작한다:
슬롯마다 KV에 든 토큰을 기억하고, 요청 프롬프트와 **가장 긴 공통 접두사**를 가진 슬롯을 골라 나머지만 prefill.
대화 중간 편집·답변 재생성에도 바뀐 지점 앞까지 재사용하고, 취소된 생성 뒤에도 슬롯을 그대로 쓴다.
토큰 수가 정확하므로 컨텍스트 맞추기도 추정 없이 한다. 응답의 `x_edge.reused_tokens`로 확인.

## API

| 메서드 | 경로 | 비고 |
|---|---|---|
| GET | `/health` | llama-server 형식. 준비되면 200, 로딩 중이면 503 |
| GET | `/v1/models` | 로드된 채팅 모델과(선택한 경우) 임베딩 모델 |
| POST | `/v1/chat/completions` | 아래 표 참고 |
| POST | `/v1/completions` | 템플릿 없는 원문 이어 쓰기. `prompt`(문자열/문자열 배열), `echo`, `logprobs`(0–5), `n`, `stop`, 스트리밍 |
| POST | `/v1/embeddings` | 모델 탭에서 임베딩 모델로 지정한 GGUF. `input`(문자열·배열·토큰 ID), `encoding_format`(float/base64), `dimensions`(잘라서 재정규화) |
| GET | `/debug/status` | 서버, 모델(지원 기능 목록 포함), 대기열, 기기 상태 |

`/v1/chat/completions`가 받는 항목 (GGUF 기준, llama.cpp `common` 라이브러리를 llama-server와 같은 방식으로 사용):

| 항목 | 동작 |
|---|---|
| `stream`, `stream_options.include_usage`, `max_tokens`/`max_completion_tokens`, `temperature`, `top_p`, `top_k`, `seed` | 기본 |
| `stop` | 문자열/배열(최대 16). 일부가 걸칠 수 있는 꼬리는 보류했다가 확정되면 보냄 |
| `frequency_penalty`, `presence_penalty`, `logit_bias` | `common_sampler` |
| `logprobs`, `top_logprobs`(0–20) | 샘플링 전 원래 logits의 log-softmax |
| `response_format` (`json_object`, `json_schema`) | 스키마 → GBNF 문법으로 출력 강제 |
| `tools`, `tool_choice`(auto/none/required/함수 지정), `parallel_tool_calls` | 모델 템플릿의 도구 형식으로 렌더, 출력은 `tool_calls`로 파싱, `finish_reason: tool_calls` |
| 생각(thinking) 모델 | 추론은 `reasoning_content`로 분리(스트리밍 포함). `reasoning_format: "none"`이면 본문에 그대로. `reasoning_effort`, `chat_template_kwargs`(`enable_thinking` 등) 전달 |
| 이미지(`image_url`: data URL 또는 http) · 오디오(`input_audio`) | 모델 탭에서 mmproj를 연결한 경우. 이미지가 있는 요청은 첫 이미지 앞까지만 KV를 재사용 |
| 마지막 메시지가 `assistant` | 그 내용을 이어서 생성 |
| `n` (1–8) | 순차 생성. 2번째부터는 프롬프트 KV를 재사용 |

요청이 필요로 하는 기능을 로드된 모델이 지원하지 않으면(예: mmproj 없이 이미지, LiteRT-LM에 `tools`) 처음부터 `400`으로 거절한다.

- API 키를 설정하면 `/v1/*`에 `Authorization: Bearer <key>`가 필요하다.
- 대기열(기본 4)을 넘는 요청은 `429`와 `Retry-After: 5`를 받는다.
- 발열 상태가 `SEVERE` 이상이면 `503`과 `Retry-After: 30`을 받는다.

## 대화 컨텍스트 재사용 (KV 캐시)

OpenAI API는 매 요청마다 대화 전체를 보낸다. Edge는 LiteRT-LM Conversation을 슬롯(기본 3개)에 살려 두고, 들어온 요청을 다음과 같이 처리한다.

- **재사용(hit):** "마지막 user 메시지를 뺀 나머지"가 슬롯의 대화(앱이 생성한 답변 포함)와 같으면 그 Conversation을 이어서 쓴다. 이때는 **새 user 메시지만 prefill**한다.
- **새로 만들기(miss):** 같지 않으면 새 Conversation을 만들어 전체를 prefill한다. 다음 경우가 miss다.
  - 이전 메시지를 편집했을 때
  - 답변을 재생성할 때
  - system 프롬프트나 샘플링 파라미터(temperature, top_p, top_k, seed)가 바뀌었을 때
- **폐기:** `max_tokens`로 잘렸거나, 클라이언트 연결이 끊겼거나, 오류가 난 세션은 버린다. 내부 상태를 신뢰할 수 없기 때문이다.
- **교체 순서:** 슬롯이 차면 재사용 횟수가 적은 것, 턴 수가 적은 것, 오래된 것 순으로 내보낸다. 그래서 OpenWebUI의 제목·태그·후속 질문 생성 요청이 본 대화 캐시를 밀어내지 않는다.
- **메모리:** 슬롯마다 KV 캐시 메모리를 쓴다. 실기기에서 RAM 여유를 보고 슬롯 수를 조정하고, 0으로 두면 재사용하지 않는다.
- **결과 확인:** 응답의 `x_edge` 필드(스트리밍이면 마지막 chunk에 들어 있음), `/debug/status`의 `conversation_cache`, 로그에서 볼 수 있다.

```
turn 1: cache=miss ttft=5787ms            (Mock, 긴 첫 메시지)
turn 2 (same history):   cache=hit reused=3 ttft=153ms
turn 2 (edited history): cache=miss ttft=6653ms
```

## 컨텍스트 길이 초과 처리

컨텍스트 크기(기본 4096, 1024~32768)는 엔진의 `maxNumTokens`로 설정한다. 요청마다 다음 순서로 처리한다.

1. **토큰 추정:** LiteRT-LM에는 토크나이저 API가 없어서 문자 종류별 휴리스틱으로 추정한다.
   - ASCII는 4자당 1토큰, 한글·CJK는 1.5자당 1토큰, 메시지당 템플릿 오버헤드 5토큰
   - 생성이 끝날 때마다 `Conversation.getTokenCount()`의 정확한 값으로 보정 계수를 학습한다.
2. **답변 공간 확보:** `max_tokens`만큼(없으면 512, 최대 창의 절반) 답변용 공간을 남긴다.
3. **초과 시 정책:**
   - **오래된 턴부터 잘라내기 (기본값):**
     - system 메시지와 마지막 user 메시지는 남긴다.
     - 가장 오래된 턴부터 버리고, 남은 대화는 항상 user 메시지로 시작하게 한다.
     - 창의 75%까지 여유를 두고 자르므로 이후 몇 턴은 캐시를 재사용한다. 캐시는 클라이언트가 보낸 원본 대화로 매칭하기 때문이다.
   - **에러 반환:** `400`과 `code: context_length_exceeded`를 돌려준다(OpenAI와 같음). 마지막 메시지 하나만으로도 넘치면 잘라내기 정책에서도 이 에러가 난다.
4. **캐시를 재사용할 때:** KV 캐시의 정확한 토큰 수에 새 메시지를 더해 한도를 검사한다. 넘으면 세션을 버리고 잘라서 새로 만든다.
5. **답변 길이 제한:** 남은 컨텍스트만큼만 생성하고, 넘으면 `finish_reason: length`로 끝낸다.
6. **재시도:** 추정이 빗나가 첫 토큰 전에 백엔드가 실패하면, 추정 계수를 1.5배로 올려 한 번만 다시 계획한다.
7. **스트리밍 에러:** 첫 토큰이 나올 때까지 SSE 응답 시작을 미룬다. 그래서 시작 전 오류(초과, 로드 실패)는 정상적인 HTTP 상태 코드로 전달된다.
8. **결과 확인:**
   - 응답의 `x_edge`: `truncated_messages`, `prompt_tokens_estimate`, `completion_budget`
   - `usage.prompt_tokens`: 추정값
   - `/debug/status`의 `context`: `max_tokens`, `overflow_policy`, `token_estimate_factor`

```
context 4096 tok: sent 10 msgs, truncated 6, prompt ~2055 tok, budget 2041
HTTP 400, code=context_length_exceeded: this request needs about 10584 tokens but the context window is 4096 tokens
```

OpenWebUI 설정 팁: Admin Settings → Interface에서 제목·태그·후속 질문 생성을 끄거나 Task Model을 다른 모델로 지정하면 폰의 대기열과 캐시 슬롯을 본 대화에만 쓸 수 있다.

## 백그라운드 유지 (제조사별)

코드에서 처리하는 것:
- `specialUse` 포그라운드 서비스 (Android 15의 `dataSync` 6시간 제한을 피함)
- `PARTIAL_WAKE_LOCK`: 항상 잡거나 요청 처리 중에만 잡도록 선택
- Wi-Fi lock

제조사별 추가 설정은 `Build.MANUFACTURER`로 판별해서 설정 탭에 안내하고, 해당 설정 화면으로 바로 이동한다([`OemPowerGuide.kt`](app/src/main/java/node/nveloper/edge/system/OemPowerGuide.kt)).
- 지원: Xiaomi/Redmi/POCO, Samsung, Huawei/Honor, OPPO/realme/OnePlus, vivo/iQOO
- 그 밖의 기기: 표준 "앱 배터리 설정"만 안내한다.
- 공통: 배터리 최적화 제외 요청

## 알려진 한계 (다음 작업 후보)

- **(LiteRT-LM) 토큰 수가 근사치다.** 청크 1개를 1토큰으로 센다. GGUF는 정확하다.
- **KV 캐시 재사용은 앞부분이 완전히 같을 때만 된다.** 대화 중간을 편집하거나 답변을 재생성하면 처음부터 다시 prefill한다. LiteRT-LM Conversation에는 되감기(rollback) 기능이 없기 때문이다.
- **GGUF 실추론은 아직 실행해 보지 않았다.** 위 API 항목(도구 호출, JSON 스키마, 추론 분리, logprobs, 이미지, 임베딩, 분할 GGUF)은 빌드와 단위 테스트(요청 파싱·stop 처리·JSON 교환)까지만 확인했다.
- **비디오 입력은 지원하지 않는다.** mtmd의 비디오 경로는 ffmpeg 실행 파일이 필요하다.
- **(LiteRT-LM) 토큰 수는 추정치다.** 한글이 많은 대화는 첫 몇 요청 동안 보정 계수가 자리를 잡을 때까지 오차가 크다. 실기기에서 `token_estimate_factor`를 확인해야 한다.
- **LiteRT-LM의 실제 초과 동작은 미검증이다.** 네이티브 엔진이 `maxNumTokens`를 넘었을 때 어떤 에러를 내는지 확인하지 못했다. 재시도 로직은 "첫 토큰 전 실패"를 기준으로 동작한다.
- **클라이언트 연결 끊김 감지가 1초 이상 늦다.** 끊긴 뒤에도 소켓 버퍼에 쓰기가 성공하는 동안 생성이 계속된다.
- **(LiteRT-LM) 텍스트·기본 샘플링만 지원한다.** `stop`, 패널티, `logprobs`, JSON 모드, 도구, 이어 쓰기, 이미지는 400으로 거절한다.
- **떠난 요청이 슬롯을 차지한다.** 대기 중에 클라이언트가 끊긴 요청도 첫 토큰을 쓰는 시점까지 슬롯을 점유한다.
- **Wi-Fi lock의 효과가 제한적이다.** `WIFI_MODE_FULL_HIGH_PERF`는 Android 14부터 동작하지 않는다. 화면이 꺼진 상태의 Wi-Fi 절전 지연은 실기기에서 측정해야 한다.
- **CORS 헤더가 없다.** OpenWebUI처럼 서버에서 호출하는 클라이언트는 문제없고, 브라우저에서 직접 호출할 때만 필요하다.
