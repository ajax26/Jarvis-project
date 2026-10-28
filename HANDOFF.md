# 인수인계 메모 (2026-10-28 기준)

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
- [~] 1-4 상시 대기 서비스 + 호출어 "씽크" + 웹 HUD 연동 → **빌드·설치 완료, 실제 음성 테스트 남음**
- [ ] RK3588 실기기 테스트

## 앱 구성 (android/app/src/main/java/com/doorskorea/singkro/)

- `assistant/HudActivity` — 메인 화면. 웹 HUD를 WebView로 표시(오프라인), `SingkroBridge`로 JS와 연결
- `assistant/AssistantService` — 마이크 포그라운드 서비스. 호출어 / MIC 버튼 / 입력 중 먼저 온 요청 처리
- `assistant/AssistantBus` — 서비스 ↔ 화면 이벤트 (백엔드 웹소켓과 같은 JSON 형식)
- `assistant/MainActivity` — 개발용 테스트 화면 ("SINGKRO 테스트" 아이콘): 모델 로딩, 질문, "평가 실행"
- `ai/LlmEngine` — MediaPipe 0.10.35. 시스템 프롬프트 캐시(`cloneSession`), Qwen 한글 깨짐 복원(`ByteLevel`)
- `ai/IntentPrompt`, `ai/AssistantIntent`(파서), `ai/IntentHandler`(날씨·뉴스·노래·볼륨)
- `ai/KmaGrid` — 지역 이름 → 기상청 격자
- `ai/Speech` — STT/TTS, `ai/WakeWordListener` — Vosk 호출어

## 측정 결과 (Lenovo TB350FU 태블릿, MT8781, Android 12, RAM 6GB)

- 모델: Qwen2.5-1.5B-Instruct q8 ekv1280 (`.task`, 1.6GB)
- **CPU 의도 분류 ~3.4초 / GPU ~21초** → 기본값 CPU (`--es backend GPU`로 GPU 시험 가능)
- 시스템 프롬프트 최초 처리 ~27초 → 앱 시작 시 미리 처리(워밍업)
- 정확도 18/20 (PC 19/20). 남은 오답: "우산 챙겨야 돼?"→음소거, "소리 꺼줘"→볼륨 업
- 날씨 질문 전체 ~5초, 곡 검색 ~3초, 메모리 ~2.1GB
- 날씨·뉴스는 LLM 요약 없이 정해진 문장으로 답함 (LLM 요약은 느리고 내용을 지어냄)
- 호출어 "씽크" (Vosk, 합성음 테스트): 감지 12/15, 오작동 0/48. 판정 단어 `싱크`,`신키` (단어 전체 일치)
  - "씽씽" 0/15, "싱싱" 5/15라 제외. "싱크로야"는 15/15지만 브랜드명 언급 시 오작동

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

## RK3588에서 할 일 (순서대로)

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
- PC 의도 평가: `cd pc-test && python assistant.py --eval --model qwen2.5:1.5b`
- PC 호출어 평가: `py -3.13 pc-test/wakeword_test.py --phrase 씽크 --tokens 싱크,신키` (vosk, edge-tts, ffmpeg 필요)

## 알려진 문제·남은 일

- 태블릿은 기기 내 음성인식(on-device) 미지원 → 기본 Google 인식기 사용 (오프라인 보장 안 됨)
- 태블릿 USB가 큰 파일 전송 중 자주 끊김 (배터리 부족 상태였음)
- 곡 재생은 브라우저로 Singkro 웹 플레이어를 엶 (로그인 필요). 백그라운드에서 화면 띄우기는 안드로이드 제약 있음
- 곡 검색 결과가 인기순이 아님 (제목 가나다순)
- "오늘" 최저기온은 지난 시간대가 빠져 실제보다 높게 나올 수 있음
- MediaPipe LLM Inference API의 지원 상태(LiteRT-LM 이전 여부) 확인 필요
