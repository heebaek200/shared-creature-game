## EXIF 검증
관련 이슈 : [[기술검증] Android·iOS 환경의 JPEG EXIF 위치·촬영 시각 추출 검증 #4](https://github.com/heebaek200/shared-creature-game/issues/4)

### 1. 검증 목적
#### 모바일 브라우저에서 업로드한 JPEG 사진의 EXIF GPS 좌표와촬영 시각을 Spring Boot 서버에서 정상적으로 추출할 수 있는지 검증한다. 
#### 본 프로젝트에서 PHOTO_EXIF는 필수 퀘스트 검증 유형이므로, 정식 기능 구현 전에 Android Chrome과 iOS Safari의 실제 동작을 확인한다.

### 2. 검증 환경 및 방법
#### 1. build.gradle에 메타데이터 추출을 위한 코드 추가
	implementation 'org.springframework.boot:spring-boot-starter-web'
	implementation 'com.drewnoakes:metadata-extractor:2.19.0'
#### 2. 검증하기위해 임시로 SecurityConfig쪽에서 예외처리를 했고, resources에 static폴더를 만들어 index.html을 사용 하였습니다 
#### 3. 외부 프로그램인 ngrok이 필요합니다 ( 모바일 검증시 )

### 3. 검증 결과(성공·실패 사례)
#### 모바일로 접속하여 파일을 보내 컴퓨터로 저장되고 경도와위도,촬영시간이 잘 표시 됩니다. 다만 ios환경에서는 테스트를 해보지 못 하였으며, Security를 무력화시키지 않으면 모바일로 접속이 안되는 현상이 있습니다
#### EXIF 시간대 Offset이 없는 경우 Asia/Seoul 기준 해석 검증,GPS 정보가 없는 사진의 검증 실패 확인,HEIC·HEIF 등 지원하지 않는 형식의 검증 실패 확인,MIME 형식과 JPEG Signature 확인 및 10 MiB 용량 제한 검증 를 확인했습니다.

### 4. 최종 채택 기술 또는 방법과 판단 근거
#### metadata-extractor : 디렉토리 단위(GpsDirectory, ExifSubIFDDirectory)로 직관적인 API를 제공하여 위도, 경도, 촬영 시각 등의 필요 데이터를 가독성 높고 안전하게 추출할 수 있습니다.
#### 촬영 시각 시간대(Timezone) 예외 처리 정책: Asia/Seoul : 일부 구형 카메라나 특정 모바일 앱에서 촬영된 사진은 날짜/시간만 기록하고 시간대 오프셋을 남기지 않는 경우가 발생합니다.
#### 파일 무결성 및 보안 검증: Magic Byte : JPEG 파일의 고유 시작 바이트(Magic Bytes)인 FF D8을 바이트 스트림으로 직접 검증함으로써, 실제 유효한 이미지 파일만 서버에 업로드되도록 보안성을 강화했습니다.

### 5. 실제 구현 시 참고사항
#### 모바일로 접속하기 위해선 다른 주소를 받아와야 하므로 Security에 예외처리를 해야함 (검증단계이기때문에 잠시 무효화)
#### ngrok이라는 외부 프로그램을 사용하여 간단하게 모바일로 접속할수있는 주소를 만들수 있습니다.
