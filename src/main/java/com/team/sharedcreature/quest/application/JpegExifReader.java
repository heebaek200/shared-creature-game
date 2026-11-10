package com.team.sharedcreature.quest.application;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * JPEG 파일에서 EXIF GPS와 촬영 시각 원본값을 읽습니다.
 *
 * 이미지 픽셀을 디코딩하지 않고 Metadata Extractor로 EXIF 메타데이터만 조회합니다.
 */
@Component
public class JpegExifReader {

    /** EXIF APP1 메타데이터의 최대 허용 크기: 1 MiB */
    private static final int MAX_EXIF_APP1_BYTES = 1024 * 1024;

    /** JPEG의 OffsetTimeOriginal 태그 번호 */
    private static final int TAG_OFFSET_TIME_ORIGINAL = 0x9011;

    /** EXIF APP1 세그먼트 시작 식별자: "Exif\0\0" */
    private static final byte[] EXIF_IDENTIFIER = {
            0x45, 0x78, 0x69, 0x66, 0x00, 0x00
    };

    /**
     * JPEG Signature와 EXIF APP1 메타데이터 크기를 사전 검사합니다.
     *
     * 이 메서드는 사진 제출 이력을 저장하기 전에 호출합니다.
     * JPEG 형식이 잘못됐거나 EXIF 크기가 제한을 넘으면 제출 횟수에 포함하지 않습니다.
     *
     * @param jpegBytes 업로드된 파일 바이트
     */
    public void validateJpegAndExifSize(byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length < 3
                || unsignedByte(jpegBytes[0]) != 0xFF
                || unsignedByte(jpegBytes[1]) != 0xD8
                || unsignedByte(jpegBytes[2]) != 0xFF) {
            throw new BusinessException(
                    ErrorCode.INVALID_FIELD_VALUE,
                    "유효한 JPEG 파일이 아닙니다."
            );
        }

        int offset = 2;
        long totalExifBytes = 0;

        // JPEG 헤더 세그먼트를 순회하며 EXIF APP1 크기를 확인합니다.
        while (offset < jpegBytes.length) {
            if (unsignedByte(jpegBytes[offset]) != 0xFF) {
                throw new BusinessException(
                        ErrorCode.INVALID_FIELD_VALUE,
                        "JPEG 세그먼트 형식이 올바르지 않습니다."
                );
            }

            // JPEG 마커 앞에 올 수 있는 FF 채움 바이트를 건너뜁니다.
            while (offset < jpegBytes.length
                    && unsignedByte(jpegBytes[offset]) == 0xFF) {
                offset++;
            }

            if (offset >= jpegBytes.length) {
                throw new BusinessException(
                        ErrorCode.INVALID_FIELD_VALUE,
                        "JPEG 파일이 불완전합니다."
                );
            }

            int marker = unsignedByte(jpegBytes[offset++]);

            // SOS부터는 압축 이미지 데이터이므로 헤더 세그먼트 검사를 종료합니다.
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }

            // 길이 필드가 없는 JPEG 마커입니다.
            if (isStandaloneMarker(marker)) {
                continue;
            }

            if (offset + 2 > jpegBytes.length) {
                throw new BusinessException(
                        ErrorCode.INVALID_FIELD_VALUE,
                        "JPEG 세그먼트가 불완전합니다."
                );
            }

            int segmentLength = (unsignedByte(jpegBytes[offset]) << 8)
                    | unsignedByte(jpegBytes[offset + 1]);

            if (segmentLength < 2) {
                throw new BusinessException(
                        ErrorCode.INVALID_FIELD_VALUE,
                        "JPEG 세그먼트 길이가 올바르지 않습니다."
                );
            }

            int payloadOffset = offset + 2;
            int payloadLength = segmentLength - 2;
            long segmentEnd = (long) payloadOffset + payloadLength;

            if (segmentEnd > jpegBytes.length) {
                throw new BusinessException(
                        ErrorCode.INVALID_FIELD_VALUE,
                        "JPEG 세그먼트가 파일 범위를 벗어났습니다."
                );
            }

            // APP1 세그먼트 중 EXIF 식별자로 시작하는 데이터만 크기 제한을 적용합니다.
            if (marker == 0xE1
                    && startsWithExifIdentifier(jpegBytes, payloadOffset, payloadLength)) {
                totalExifBytes += payloadLength;

                if (totalExifBytes > MAX_EXIF_APP1_BYTES) {
                    throw new BusinessException(
                            ErrorCode.INVALID_FIELD_VALUE,
                            "EXIF 메타데이터는 1 MiB를 초과할 수 없습니다."
                    );
                }
            }

            // 다음 JPEG 세그먼트로 이동합니다.
            offset = (int) segmentEnd;
        }
    }

    /**
     * JPEG 바이트에서 GPS와 촬영 시각의 EXIF 원본값을 추출합니다.
     *
     * 호출 전에 validateJpegAndExifSize를 실행해야 합니다.
     *
     * @param jpegBytes 업로드된 JPEG 파일 바이트
     * @return 추출한 GPS 값과 촬영 시각 문자열. EXIF 항목 누락 시 해당 값은 null입니다.
     * @throws IOException 파일 바이트 읽기 오류
     * @throws ImageProcessingException JPEG 메타데이터 파싱 오류
     */
    public ExifData readExif(byte[] jpegBytes)
            throws IOException, ImageProcessingException {
        // Metadata Extractor는 이미지 전체 픽셀을 렌더링하지 않고 메타데이터를 읽습니다.
        Metadata metadata = ImageMetadataReader.readMetadata(
                new ByteArrayInputStream(jpegBytes)
        );

        BigDecimal latitude = null;
        BigDecimal longitude = null;

        // GPS 디렉터리와 좌표를 읽습니다. GPS가 없으면 좌표는 null로 반환합니다.
        GpsDirectory gpsDirectory =
                metadata.getFirstDirectoryOfType(GpsDirectory.class);

        if (gpsDirectory != null && gpsDirectory.getGeoLocation() != null) {
            latitude = BigDecimal.valueOf(
                    gpsDirectory.getGeoLocation().getLatitude()
            );
            longitude = BigDecimal.valueOf(
                    gpsDirectory.getGeoLocation().getLongitude()
            );
        }

        String capturedAtRaw = null;
        String offsetTimeOriginalRaw = null;

        // 촬영 시각과 선택적 UTC Offset 원본 문자열을 읽습니다.
        ExifSubIFDDirectory subIfdDirectory =
                metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);

        if (subIfdDirectory != null) {
            capturedAtRaw = subIfdDirectory.getString(
                    ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL
            );
            offsetTimeOriginalRaw =
                    subIfdDirectory.getString(TAG_OFFSET_TIME_ORIGINAL);
        }

        return new ExifData(
                latitude,
                longitude,
                capturedAtRaw,
                offsetTimeOriginalRaw
        );
    }

    /**
     * 길이 필드가 없는 JPEG 마커인지 확인합니다.
     */
    private boolean isStandaloneMarker(int marker) {
        return marker == 0x01
                || (marker >= 0xD0 && marker <= 0xD7);
    }

    /**
     * 해당 바이트 구간이 EXIF APP1 식별자로 시작하는지 확인합니다.
     */
    private boolean startsWithExifIdentifier(
            byte[] bytes,
            int offset,
            int length
    ) {
        if (length < EXIF_IDENTIFIER.length) {
            return false;
        }

        for (int i = 0; i < EXIF_IDENTIFIER.length; i++) {
            if (bytes[offset + i] != EXIF_IDENTIFIER[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Java byte를 0~255 범위의 정수로 변환합니다.
     */
    private int unsignedByte(byte value) {
        return value & 0xFF;
    }

    /**
     * EXIF에서 추출한 GPS 좌표와 촬영 시각 원본값입니다.
     *
     * 촬영 시각은 서비스에서 Offset 유무를 확인해 Instant로 변환합니다.
     * Offset이 없으면 KST로 해석해야 합니다.
     */
    public record ExifData(
            BigDecimal latitude,
            BigDecimal longitude,
            String capturedAtRaw,
            String offsetTimeOriginalRaw
    ) {
    }
}