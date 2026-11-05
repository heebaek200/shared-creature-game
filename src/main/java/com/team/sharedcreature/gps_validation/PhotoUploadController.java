package com.team.sharedcreature.gps_validation;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/photo-submissions")
public class PhotoUploadController {

    private final String UPLOAD_DIR = System.getProperty("user.home") + "/Desktop/uploaded_photos/"; // 컴퓨터로 업로드 받을 경로
    private final long MAX_FILE_SIZE = 10L * 1024 * 1024; // 10 MiB 용량제한

    /**
     * 사진 업로드 및 EXIF GPS / 촬영 시각 검증 API
     * @param file 업로드할 이미지 파일
     * @return 검증 결과 메시지
     */
    @PostMapping("/upload")
    public ResponseEntity<String> uploadPhoto(@RequestParam("file") MultipartFile file) {
        // 1. 파일 비어있음 검증
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE, "업로드된 파일이 비어 있습니다.");
        }

        // 2. 용량 제한 검증 (10 MiB)
        if (file.getSize() > MAX_FILE_SIZE) {
            log.warn("파일 용량 초과: {} bytes", file.getSize());
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        try {
            // 3. MIME 형식 및 JPEG 시그니처(Magic Bytes: FF D8) 검증
            String contentType = file.getContentType();
            if (contentType == null || (!contentType.equals("image/jpeg") && !contentType.equals("image/jpg"))) {
                log.warn("지원하지 않는 MIME 타입: {}", contentType);
                throw new BusinessException(
                        ErrorCode.INVALID_FIELD_VALUE,
                        "지원하지 않는 형식입니다. JPEG 파일만 업로드 가능합니다."
                );
            }

            // 3-1 형식만jpg인 가짜 파일을 거르기위한 jpg만의 고유바이트 앞자리 두자리를 따는 방법
            try (InputStream isForCheck = file.getInputStream()) {
                byte[] header = new byte[2];
                if (isForCheck.read(header) != 2 || header[0] != (byte) 0xFF || header[1] != (byte) 0xD8) {
                    log.warn("JPEG 파일 시그니처 불일치 (변조되었거나 다른 형식)");
                    throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE, "유효한 JPEG 이미지 파일이 아닙니다.");
                }
            }

            // 4. 디렉토리 확인 및 파일 저장
            File dir = new File(UPLOAD_DIR);
            if (!dir.exists()) dir.mkdirs();

            String originalFilename = file.getOriginalFilename();
            String savedFileName = UUID.randomUUID() + "_" + originalFilename;
            Path filePath = Paths.get(UPLOAD_DIR + savedFileName);
            Files.write(filePath, file.getBytes());

            // 5. EXIF 메타데이터 분석 (GPS 및 촬영 시각)
            Metadata metadata = ImageMetadataReader.readMetadata(file.getInputStream());

            // 5-1. GPS 정보 추출
            GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gpsDirectory == null || gpsDirectory.getGeoLocation() == null) {
                log.warn("사진에 GPS 위치 정보가 포함되어 있지 않습니다.");
                throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE, "사진에 GPS 위치 정보가 없습니다.");
            }

            double latitude = gpsDirectory.getGeoLocation().getLatitude();
            double longitude = gpsDirectory.getGeoLocation().getLongitude();

            // 5-2. EXIF 촬영 시각 추출 (Asia/Seoul 기준 해석)
            ExifSubIFDDirectory subIFDDirectory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            ZonedDateTime captureDateTime = null;

            if (subIFDDirectory != null) {
                Date dateOriginal = subIFDDirectory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                if (dateOriginal != null) {
                    // 시간대 Offset이 없는 경우 Asia/Seoul 기준 해석
                    LocalDateTime localDateTime = dateOriginal.toInstant()
                            .atZone(ZoneId.of("Asia/Seoul"))
                            .toLocalDateTime();
                    captureDateTime = localDateTime.atZone(ZoneId.of("Asia/Seoul"));
                }
            }

            if (captureDateTime == null) {
                log.warn("사진에 EXIF 촬영 시각 정보가 없습니다.");
                throw new BusinessException(ErrorCode.INVALID_FIELD_VALUE, "사진에 촬영 시각 정보가 없습니다.");
            }

            log.info("[검증 성공] 위도: {}, 경도: {}, 촬영시각: {}", latitude, longitude, captureDateTime);
            return ResponseEntity.ok(String.format(
                    "업로드 및 EXIF 검증 성공!\n위도: %.6f\n경도: %.6f\n촬영시각: %s",
                    latitude, longitude, captureDateTime
            ));

        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception e) {
            log.error("사진 처리 중 오류 발생", e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
