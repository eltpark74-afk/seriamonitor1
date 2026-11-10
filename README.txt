SW 모니터 (안드로이드) - APK 만드는 방법 (Android Studio 없이)

1. https://github.com 에 무료 가입 후 로그인합니다.
2. 오른쪽 위 [+] > New repository > 이름 입력(예: SerialMonitor) > Private 선택 > Create repository
3. 만들어진 화면에서 "uploading an existing file" 링크를 누르고,
   이 폴더 안의 모든 파일/폴더(.github 폴더 포함)를 끌어다 놓은 뒤 [Commit changes]
   (.github 폴더가 올라가지 않으면: Add file > Create new file > 이름에
    .github/workflows/build.yml 을 입력하고 build.yml 내용을 붙여넣기)
4. 위쪽 [Actions] 탭 > Build APK 실행이 끝날 때까지 기다립니다 (3~5분, 초록 체크).
5. 완료된 실행을 눌러 맨 아래 Artifacts 의 SerialMonitor-apk 를 내려받아 압축을 풉니다.
6. app-debug.apk 를 폰으로 옮겨 설치합니다 ("출처를 알 수 없는 앱 설치" 허용 필요).
7. 폰 - OTG 케이블 - USB-시리얼 어댑터 - 장비 순으로 연결하고 앱에서 [연결]을 누릅니다.
   USB 권한 팝업이 뜨면 허용합니다.

CSV 로그 위치: 내장저장소/Android/data/com.example.serialmonitor/files/
