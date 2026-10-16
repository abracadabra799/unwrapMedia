# AI 대화 — 프레임 분석·HDR 분석 자동 실행 (설계)

날짜: 2026-10-17 · 상태: 승인됨 (사용자: "프레임 분석이랑 HDR 분석도 자동 실행되게 해줘")

## 배경

AI 리포트 레지스트리(`ui/ai/AiReportRegistry.kt`)에서 `gop`/`interval`은 프레임 분석(GOP)이, `hdr`은 HDR/SEI 창 실행이
먼저 필요해 그 전에는 `Unavailable`이었다. 사용자가 메뉴를 열지 않아도 AI 요청으로 실행되게 한다.

## 동작

- `gop`, `interval`: 비디오 탭에서 프레임 목록이 없으면 `OnRequest`(자동 실행) — `probeFrameTypes(file)`(ffprobe 1회,
  `MediaIndexCache` 사용, 내부 타임아웃 120 s)로 프레임 목록을 얻어 렌더. 프레임이 2개 미만이면 "frame analysis found fewer
  than 2 frames".
- `hdr`: 비디오 탭에서 보관 결과가 없거나 무효면 `OnRequest`(자동 실행) — identity를 캡처한 뒤 `scanHdrMetadata(file, root,
  isCancelled = { 인터럽트 })`; null이면 "HDR scan: no supported video sample table (MP4/MOV family only)".
- 결과 공유(같은 전송 안): 전송마다 하나의 `AiAutoRunState`(AtomicReference 프레임 목록 + HDR 결과)를 만들어
  OnRequest와 브리지의 `frame <n>` 요청이 같이 쓴다 — `report gop` 뒤의 `frame 30`이 같은 질문 안에서 응답된다.
- 탭에 반영(다음 전송·화면 재사용): 성공 결과를 UI 스레드로 넘겨 `tab.gopFrames`(아직 null이고 분석 중이 아닐 때만)와
  `tab.hdrScan = StoredAnalysis(result, identityBeforeScan)`에 저장. 탭이 닫혔으면 버린다.
- 타임아웃: 자동 실행 kind는 `AUTO_RUN_TIMEOUT_SECONDS = 120`, 기존 가벼운 kind는 20 s 유지. `OnRequest`가 자기
  타임아웃을 갖는다.
- 진행 표시: 자동 실행 중 `tab.aiAutoRunning: String?`(kind 표시명) → 패널 진행 문구 "📊 <프레임|HDR> 분석 실행 중…"
  (그 외에는 기존 문구). finally에서 해제.
- 취소: 정지 → 브리지 스레드 인터럽트 → `runReportWithTimeout`이 작업을 cancel(true) → ffprobe(`readProcessOutputWithTimeout`)
  종료 / HDR 스캔 `isCancelled` true.
- 대상: `type == VIDEO`만 (규칙 6). 컨텍스트 목록에서는 자동 실행 kind를 "요청 시 실행 (분석 자동 실행)"으로 표시.
- 무효 금지(규칙 7): 브리지는 OnRequest 전에 파일 identity를 확인(기존). 보관하는 HDR 결과의 identity는 스캔 직전 캡처.

## 테스트

- 레지스트리: 프레임 없음 → GOP/INTERVAL이 OnRequest; run()이 주입된 프레임 프로버를 한 번만 부르고(두 kind가 공유)
  결과를 렌더; HDR 보관 없음 → OnRequest, 주입된 스캐너 결과 렌더; 스캐너 null → 미지원 문구.
- 브리지: `report gop` 실행 후 같은 컨텍스트의 `frame 1`이 성공.
- 타임아웃: OnRequest의 timeoutSeconds가 사용된다(짧은 값으로 테스트).
- 퍼블리시: 콜백이 성공 결과로 호출되고, 실패/타임아웃에는 호출되지 않는다.
- 실파일: `app/src/test/resources/video-frame/default.mp4`로 실제 probe/scan 경로(ffmpeg 있을 때).
