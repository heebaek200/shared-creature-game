package com.team.sharedcreature.quest.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPEG Signature, EXIF APP1 크기, EXIF 메타데이터 추출을 검증합니다.
 */
class JpegExifReaderTest {

    private static final int MAX_EXIF_BYTES = 1024 * 1024;
    private static final int JPEG_MAX_SEGMENT_PAYLOAD = 65_533;

    private JpegExifReader reader;

    @BeforeEach
    void setUp() {
        reader = new JpegExifReader();
    }

    @Test
    void 유효한_JPEG에_EXIF가_없으면_누락값을_null로_반환한다()
            throws Exception {
        byte[] jpegBytes = createJpegWithoutExif();

        reader.validateJpegAndExifSize(jpegBytes);
        JpegExifReader.ExifData result = reader.readExif(jpegBytes);

        assertThat(result.latitude()).isNull();
        assertThat(result.longitude()).isNull();
        assertThat(result.capturedAtRaw()).isNull();
        assertThat(result.offsetTimeOriginalRaw()).isNull();
    }

    @Test
    void JPEG_Signature가_잘못되면_INVALID_FIELD_VALUE로_거절한다() {
        byte[] invalidBytes = {0x00, 0x01, 0x02};

        assertThatThrownBy(() -> reader.validateJpegAndExifSize(invalidBytes))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).errorCode()
                ).isEqualTo(ErrorCode.INVALID_FIELD_VALUE));
    }

    @Test
    void EXIF_APP1_총량이_1_MiB_이하면_사전검사를_통과한다()
            throws Exception {
        // 16개 세그먼트의 EXIF 데이터는 1 MiB보다 48바이트 작습니다.
        byte[] jpegBytes = createJpegWithExifSegments(16);

        reader.validateJpegAndExifSize(jpegBytes);
    }

    @Test
    void EXIF_APP1_총량이_1_MiB를_넘으면_INVALID_FIELD_VALUE로_거절한다() {
        // 17개 세그먼트의 EXIF 데이터는 1 MiB를 초과합니다.
        byte[] jpegBytes = createJpegWithExifSegments(17);

        assertThatThrownBy(() -> reader.validateJpegAndExifSize(jpegBytes))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).errorCode()
                ).isEqualTo(ErrorCode.INVALID_FIELD_VALUE));
    }

    /**
     * ImageIO로 EXIF가 없는 작은 JPEG를 생성합니다.
     */
    private byte[] createJpegWithoutExif() throws Exception {
        BufferedImage image = new BufferedImage(
                1,
                1,
                BufferedImage.TYPE_INT_RGB
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThat(ImageIO.write(image, "jpg", output)).isTrue();
        return output.toByteArray();
    }

    /**
     * EXIF APP1 세그먼트가 지정 개수만큼 들어 있는 테스트용 JPEG 헤더를 만듭니다.
     *
     * 이 바이트는 APP1 크기 사전검사용이며 픽셀 데이터는 포함하지 않습니다.
     */
    private byte[] createJpegWithExifSegments(int segmentCount) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // JPEG Start Of Image
        output.write(0xFF);
        output.write(0xD8);

        for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++) {
            // APP1 marker
            output.write(0xFF);
            output.write(0xE1);

            // Segment length에는 2바이트 길이 필드도 포함됩니다.
            output.write(0xFF);
            output.write(0xFF);

            byte[] payload = new byte[JPEG_MAX_SEGMENT_PAYLOAD];

            // APP1 payload가 EXIF 식별자로 시작하게 구성합니다.
            payload[0] = 0x45; // E
            payload[1] = 0x78; // x
            payload[2] = 0x69; // i
            payload[3] = 0x66; // f
            payload[4] = 0x00;
            payload[5] = 0x00;

            output.writeBytes(payload);
        }

        // JPEG End Of Image
        output.write(0xFF);
        output.write(0xD9);

        return output.toByteArray();
    }
}