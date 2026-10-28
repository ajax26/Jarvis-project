# 인수인계 메모 (2026-10-28 기준, RK3588 테스트 반영)

다른 PC나 새 Claude Code 세션에서 이어서 작업할 때 이 파일부터 읽으세요.

## 프로젝트 목표

도어스코리아 AI 비서 **SINGKRO**. 최종 타깃은 RK3588(RAM 8GB) 안드로이드 기기에서 100% 온디바이스로 동작하는 백그라운드 음성 비서입니다.
로컬 LLM은 사용자의 말을 의도(JSON)로만 분류하고, 실제 정보는 앱이 가져옵니다(날씨: 기상청 API허브, 뉴스: Google 뉴스 RSS, 노래: Singkro 웹 카탈로그, 볼륨: 기기 제어).

## 폴더 구조

| 경로 | 내용 |
|---|---|
| `android/` | 안드로이드 앱 (Gradle 9.5 / AGP 9.3, Android Studio2 내장 JDK 25로 빌드) |
| `frontend/` | Next.js 웹 HUD (도트 구체). `npm run build` → `frontend/out/` 정적 파일이 앱에 들어감 |
| `backend/` | FastAPI. 브라우저용 (`LLM_PROVIDER=ollama|claude|intent`) |
| `pc-test/` | PC 테스트 벤치 (안드로이드와 같은 프롬프트·파서를 Ollama로 실행), 호출어 테스트 |
| `eval/intent_cases.json` | 의도 분류 테스트 문장 23개 (PC와 앱이 공유) |
| `data/` | 기상청 격자 표 `kma_grid.json`(앱·PC 공유)과 원본 엑셀, 변환 스크립트 |

## 진행 단계

- [x] 1-1 PC 테스트 환경 (`pc-test/`)
- [x] 1-2 안드로이드 앱 + 실기기 측정 (태블릿)
- [x] 1-3 음성 입출력 (안드로이드 기본 STT/TTS)
- [x] 날씨: 기상청 API허브, 전국 3,839개 동네 단위
- [x] 1-4 상시 대기 서비스 + 호출어 + 웹 HUD 연동 (RK3588에서 실제 음성으로 동작 확인)
- [x] RK3588 실기기 테스트 (아래 측정 결과)

## 앱 구성 (android/app/src/main/java/com/doorskorea/singkro/)

- `assistant/HudActivity` — 메인 화면. 웹 HUD를 WebView로 표시(오프라인), `SingkroBridge`로 JS와 연결
- `assistant/AssistantService` — 마이크 포그라운드 서비스. 호출어 / MIC 버튼 / 입력 중 먼저 온 요청 처리
- `assistant/AssistantBus` — 서비스 ↔ 화면 이벤트 (백엔드 웹소켓과 같은 JSON 형식)
- `assistant/MainActivity` — 개발용 테스트 화면 ("SINGKRO 테스트" 아이콘): 모델 로딩, 질문, "평가 실행"
- `ai/LlmEngine` — MediaPipe 0.10.35. 시스템 프롬프트 캐시(`cloneSession`), Qwen 한글 깨짐 복원(`ByteLevel`)
- `ai/IntentPrompt`, `ai/AssistantIntent`(파서), `ai/IntentHandler`(날씨·뉴스·노래·볼륨)
- `ai/KmaGrid` — 지역 이름 → 기상청 격자
- `ai/Speech` — STT/TTS (기기 내 인식기가 3초 안에 시작 안 하면 기본 Google 인식기로 바꾸고 기억)
- `ai/WakePhrase` — 호출어 "씽씽" 판정 (SingTree 샘플 규칙). 기본 호출어 방식
- `ai/WakeWordListener` — Vosk 호출어 "싱크" (오프라인, `--es wake_engine vosk`로만 사용). 마이크 소스 선택, USB 마이크 우선

## 측정 결과 (Lenovo TB350FU 태블릿, MT8781, Android 12, RAM 6GB)

- 모델: Qwen2.5-1.5B-Instruct q8 ekv1280 (`.task`, 1.6GB)
- **CPU 의도 분류 ~3.4초 / GPU ~21초** → 기본값 CPU (`--es backend GPU`로 GPU 시험 가능)
- 시스템 프롬프트 최초 처리 ~27초 → 앱 시작 시 미리 처리(워밍업)
- 정확도 18/20 (PC 19/20). 남은 오답: "우산 챙겨야 돼?"→음소거, "소리 꺼줘"→볼륨 업
- 날씨 질문 전체 ~5초, 곡 검색 ~3초, 메모리 ~2.1GB
- 날씨·뉴스는 LLM 요약 없이 정해진 문장으로 답함 (LLM 요약은 느리고 내용을 지어냄)
- 호출어 "씽크" (Vosk, 합성음 테스트): 감지 12/15, 오작동 0/48. 판정 단어 `싱크`,`신키` (단어 전체 일치)
  - "씽씽" 0/15, "싱싱" 5/15라 제외. "싱크로야"는 15/15지만 브랜드명 언급 시 오작동

## 측정 결과 (RK3588_s, Android 12, RAM 8GB) — 2026-10-28

- 의도 분류 CPU **21/23, 평균 1.8초** / GPU 20/23, 6.4초 → CPU 유지. 로딩 7.6초 + 워밍업 14초, 메모리 ~2.6GB
- 비교: 같은 기기 SingTree 샘플(Gemma 4 E2B Q4, llama-server 4스레드)은 요청당 중간값 4.3초 (`/data/local/tmp/gemma/server.log`)
  - SingTree가 빠르게 느껴진 이유: 노래 요청은 LLM 없이 규칙 + `songs.json`으로 처리, 답변을 문장 단위로 바로 읽음
- Google 서비스 있음. 음성인식은 Google TTS 패키지(`GoogleTTSRecognitionService`)가 담당
  - "기기 내 인식기"(`com.google.android.as`)는 등록만 되어 있고 동작 안 함 (bind error 10) → 기본 인식기 사용 (온라인)
- 마이크: Jieli 무선마이크 USB 수신기. 보드 내장 마이크는 소리 없음
  - Vosk 기본 녹음기(`VOICE_RECOGNITION` 고정)는 내장 마이크를 들음 → `WakeWordListener`가 직접 녹음하고 USB 장치를 지정
  - 수신기 AGC가 켜져 있으면 말소리가 최대치에서 잘림(클리핑). 테스트 때 `su 0 tinymix -D 3 'Auto Gain Control' 0`, `'Mic Capture Volume' 110` (원래 140/147, 재연결 시 원복될 수 있음)
- 호출어
  - Vosk "싱크": 실제 음성으로 절반 정도만 인식 ("시즌", "생크", "실크" 등으로 들음) → 기본값에서 제외
  - Google "씽씽": 동작 확인. "씽씽, 오늘 뉴스 알려 줘"처럼 한 번에 말하면 바로 처리. 최종 결과에서 앞의 "씽씽"이 빠지는 경우가 있어 중간 결과에 나와도 인정
  - 호출 → 질문 듣기 시작 0.16~0.24초 (띵 효과음)
- WebView는 Chrome 95 (업데이트 불가) → HUD에서 `findLastIndex` 같은 최신 JS 사용 금지
- 화면 1920x1080 가로 (CSS 높이 ~545px) → HUD 구체 크기를 높이에 맞춤
- 모뎀 데몬 `rild`가 몇 초마다 죽어서 logcat이 금방 지워짐 → 로그는 PC에서 `adb logcat -s ...> 파일`로 계속 받아 둘 것

## 새 PC 준비

1. `git clone https://github.com/ajax26/Jarvis-project.git`
2. Git에 없는 파일 (원래 위치 `I:\00.Singkro-web\models\`):
   - LLM: https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct → `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task`
   - Vosk: https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip (압축 해제)
3. `android/local.properties` 작성 (git 제외 파일):
   ```
   sdk.dir=C\:/Users/<사용자>/AppData/Local/Android/Sdk
   KMA_AUTH_KEY=<기상청 API허브 인증키>
   ```
   `backend/.env`에도 `KMA_AUTH_KEY=` (PC 테스트·웹 intent 모드용)
4. `cd frontend && npm install && npm run build` (HUD 정적 파일 생성 — 앱 빌드 전에 필수)
5. Android Studio에서 `android/` 열고 빌드, 또는 `./gradlew assembleDebug`

## 기기에 설치·모델 넣기

```powershell
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
# 앱을 한 번 실행해야 files 폴더가 생김
adb push <경로>\qwen2.5-1.5b-instruct-q8-ekv1280.task /sdcard/Android/data/com.doorskorea.singkro.assistant/files/model.task
# Vosk: adb push 로는 Android/data 안에 하위 폴더를 못 만듦 → Download 경유
adb push <경로>\vosk-model-small-ko-0.22 /sdcard/Download/
adb shell "mv /sdcard/Download/vosk-model-small-ko-0.22 /sdcard/Download/vosk-ko && cp -r /sdcard/Download/vosk-ko /sdcard/Android/data/com.doorskorea.singkro.assistant/files/ && rm -rf /sdcard/Download/vosk-ko"
```

## RK3588 남은 일

1. 노래·볼륨 요청을 LLM 없이 규칙으로 처리 (SingTree `parseSongRequest` 참고). "○○ 시작해 줘"가 검색으로 잘못 분류되는 문제 포함
2. 응답 줄이기: 곡 검색은 첫 곡만, 뉴스는 몇 건만 (지금 곡 목록 14초, 뉴스 33초 읽음)
3. 날짜·시간 질문은 앱이 바로 답하기 ("무슨 요일이야"에 LLM이 답 못 함)
4. 대기음 OFF는 대기 중 알림 볼륨을 0으로 내림 (Google 인식기 효과음이 알림 소리로 몇 초마다 남). 이 기기에서 앱은 알림 스트림을 음소거만 할 수 있고 해제는 못 함 → 음소거(ADJUST_MUTE) 쓰지 말 것
5. 호출어가 온라인(Google)이라 "100% 온디바이스" 목표와 다름. 오프라인 호출어가 필요하면 더 큰 Vosk 모델이나 전용 호출어 엔진 검토

## RK3588 첫 설치 절차 (참고)

1. **Google 서비스 유무 확인** — 없으면 안드로이드 기본 STT/TTS가 없음 → Vosk(STT)·별도 한국어 TTS로 교체 필요
   ```powershell
   adb shell pm list packages | findstr google
   ```
2. 설치 + 모델 넣기 (위 절차)
3. "SINGKRO 테스트" 앱 → "평가 실행"을 CPU/GPU 둘 다 (`adb logcat -s SingkroEval`)
   ```powershell
   adb shell am start -n com.doorskorea.singkro.assistant/.MainActivity --es backend GPU
   ```
4. "SINGKRO 비서" 앱 → "씽크" 호출, MIC 버튼, 입력 테스트 (`adb logcat -s AssistantService SpeechInput`)
5. 호출어가 잘 안 되면: 로그에서 실제 인식 결과를 보고 `AssistantService.WAKE_TOKENS` 조정
   (설치 없이 시험: `adb shell am start-foreground-service -n com.doorskorea.singkro.assistant/.AssistantService --es wake_tokens 싱크,신키`)

## 테스트 도구

- 한글 질문을 adb로 보내기 (adb는 한글 입력 불가):
  `adb shell "am start --activity-single-top -n com.doorskorea.singkro.assistant/.MainActivity --es ask '내일 부산 비 와?'"`
- 비서 서비스 옵션 (설치 없이): `adb shell am start-foreground-service -n com.doorskorea.singkro.assistant/.AssistantService --es wake_engine vosk --es mic_source VOICE_PERFORMANCE`
- PC 의도 평가: `cd pc-test && python assistant.py --eval --model qwen2.5:1.5b`
- PC 호출어 평가: `py -3.13 pc-test/wakeword_test.py --phrase 씽크 --tokens 싱크,신키` (vosk, edge-tts, ffmpeg 필요)

## 알려진 문제·남은 일

- 태블릿·RK3588 모두 기기 내 음성인식(on-device) 미지원 → 기본 Google 인식기 사용 (오프라인 보장 안 됨)
- 태블릿 USB가 큰 파일 전송 중 자주 끊김 (배터리 부족 상태였음)
- 곡 재생은 브라우저로 Singkro 웹 플레이어를 엶 (로그인 필요). 백그라운드에서 화면 띄우기는 안드로이드 제약 있음
- 곡 검색 결과가 인기순이 아님 (제목 가나다순)
- "오늘" 최저기온은 지난 시간대가 빠져 실제보다 높게 나올 수 있음
- MediaPipe LLM Inference API의 지원 상태(LiteRT-LM 이전 여부) 확인 필요
