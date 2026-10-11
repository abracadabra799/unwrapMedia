# GUI 및 네이티브 메모리 검증

2026-10-12, macOS 15.1.1 arm64 / JDK 21.0.7 / 현재 미커밋 수정분 기준.

## 결론

60회 반복에서 검사 시작 실패, 테스트 오류, 검사 JVM의 미디어 프로세스 잔류는 관찰되지 않았다. GC 후 Java 힙과 GPU 메모리 영역은 반복에 비례해 증가하지 않았다. 다만 준비 구간 이후에도 물리 footprint가 286.0 → 304.6 MiB로 증가했으므로 **네이티브 메모리 누수가 전혀 없다고 확정할 수는 없다.**

## 방법과 범위

- 실제 앱을 `:app:run`으로 실행하고 정상 Quit 메뉴로 종료했다. 종료 직전 미리보기용 ffmpeg 두 개를 확인했고, 종료 후 앱과 두 프로세스가 모두 사라졌다. 최종 시스템 프로세스 조회에도 ffmpeg/ffprobe는 없었다.
- 네이티브 파일 선택창 자동화는 포커스/입력 문제가 있어 반복 횟수에서 제외했다.
- 임시 JUnit 도구가 실제 `AppState.openFile/closeTab`, ComposeWindow, GIF 재생 컴포넌트, 영상 썸네일, `ContentCheckWindow`를 동일 JVM에서 실행했다. 검사 시작은 Java 접근성 액션으로 실제 버튼을 호출했다. 운영 코드는 변경하지 않았다.
- `~/Downloads/unwrapMedia-samples`의 real.jpg, anim.gif, v.mp4, real.heic, crc.png, trunc.gif를 각각 10회씩 사용했다. 정상 파일과 손상 파일을 포함한다.
- 60회 모두 `inspectionStarted=true`. 검사 창은 시작 후 0.1초 또는 1.5초에 닫고, 탭을 닫은 뒤 프로세스 정리를 확인했다. 검사 60건 모두 완료됐다는 의미는 아니다.
- 반복 도구 실행 시간은 193.359초. 별도 실제 앱 실행은 약 10분 26초. 실제 앱 전체 메뉴/폴더 탐색기/동영상 플레이어를 60회 자동 조작한 것은 아니다.
- JVM 최대 힙 512 MiB인 Gradle 테스트 프로세스에서 측정했다. 각 메모리 샘플 전에 명시적 GC를 요청했다. 일반 앱의 힙 정책·장시간 무GC 사용과는 조건이 다르다.

## 측정 결과

| 시점 | RSS (KiB) | GC 후 Java 힙 (bytes) | 물리 footprint (MiB) | 잔류 미디어 프로세스 |
|---|---:|---:|---:|---:|
| 시작 | 196,784 | 13,623,104 | 201.1 | 0 |
| 6회 | 257,232 | 12,678,376 | 259.3 | 0 |
| 24회 | 277,904 | 12,486,824 | 286.0 | 0 |
| 48회 | 273,296 | 12,486,880 | 299.6 | 0 |
| 60회 | 270,016 | 12,543,712 | 308.9 | 0 |
| 종료 전 추가 유휴/GC | 270,064 | 10,935,200 | 304.6 | 0 |

모든 샘플에서 ProcessManager 추적 수 역시 0이었다. 테스트는 실패 0, 오류 0이며 stderr는 비어 있었다.

## 네이티브 영역 해석

- `vmmap`에서 24회와 최종 유휴 시점의 GPU IOAccelerator 영역은 58.7 MiB, IOSurface는 44.9 MiB로 동일했다.
- malloc 영역의 실제 할당량은 약 62.5 → 62.8 MiB였다. 같은 기간 allocator fragmentation은 약 9.4 → 18.4 MiB, VM_ALLOCATE의 resident는 92.0 → 101.1 MiB였다.
- 초기 실제 앱에는 jcmd attach가 실패했다. 검증 JVM에는 `-XX:+StartAttachListener`를 켜 NMT 측정을 수행했다.
- 24회 근처 기준점 대비 후반 NMT에서 JVM 스레드는 52개로 동일했다. Code committed 약 +5.3 MiB, Metaspace committed 약 +0.95 MiB 등이 관찰됐다. 이 NMT 샘플은 검사 실행 중 채취했으므로 최종 유휴 vmmap과 직접 동일시하지 않는다.
- RSS만으로는 압축·스왑 메모리를 놓칠 수 있어 footprint도 함께 기록했다. JVM NMT가 모든 Skia/그래픽 할당을 추적하는 것은 아니므로 OS 메모리 맵도 비교했다.

**남은 확인:** 수십 분 이상의 동일 작업 반복에서 footprint 증가가 멈추는지, 대용량 GIF/긴 영상 및 전체 탐색기·플레이어까지 포함했을 때도 안정적인지 확인해야 한다. 이번 데이터만으로 추가 코드 결함이나 누수 원인을 확정하지 않았다.

## 재현 및 자료

실행 명령:

```sh
GUI_SOAK_CYCLES=60 ./gradlew -I /private/tmp/unwrap-gui-soak.sLNF63/soak.init.gradle :app:test --tests com.multiviewer.ui.GuiNativeSoakTest
```

- 임시 도구: `/private/tmp/unwrap-gui-soak.sLNF63/src/com/multiviewer/ui/GuiNativeSoakTest.kt` (임시 디렉터리 삭제 시 재현 도구도 제거됨).
- 측정 XML 및 vmmap 원본 사본: `app/build/reports/gui-native-soak/` (빌드 산출물이며 버전 관리 대상 아님).
- 이전 전체 단위/통합 테스트 1,416개 통과 결과와 이번 단독 GUI 검증은 별도 실행이다.
