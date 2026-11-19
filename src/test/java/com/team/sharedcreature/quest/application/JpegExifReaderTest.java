package com.team.sharedcreature.quest.application;

import com.team.sharedcreature._core.exception.BusinessException;
import com.team.sharedcreature._core.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
    void gpsWithZeroDenominatorIsRejectedBeforeDecimalConversion() throws Exception {
        byte[] jpegBytes = jpegWithGps(1, 0);
        reader.validateJpegAndExifSize(jpegBytes);

        assertThatThrownBy(() -> reader.readExif(jpegBytes))
                .isInstanceOf(JpegExifReader.InvalidGpsException.class);
    }

    @Test
    void validGpsCoordinatesArePreserved() throws Exception {
        JpegExifReader.ExifData data = reader.readExif(jpegWithGps(37, 1));

        assertThat(data.latitude()).isEqualByComparingTo("37");
        assertThat(data.longitude()).isEqualByComparingTo("127");
    }

    /** 작은 JPEG에 GPS 디렉터리가 있는 EXIF APP1을 붙입니다. */
    private byte[] jpegWithGps(int latitudeNumerator, int latitudeDenominator) throws Exception {
        ByteBuffer tiff = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        // IFD0에서 GPS IFD(offset 26)를 참조합니다.
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(26).putInt(0);
        tiff.putShort((short) 4);
        tiff.putShort((short) 1).putShort((short) 2).putInt(2).putInt('N');
        tiff.putShort((short) 2).putShort((short) 5).putInt(3).putInt(80);
        tiff.putShort((short) 3).putShort((short) 2).putInt(2).putInt('E');
        tiff.putShort((short) 4).putShort((short) 5).putInt(3).putInt(104);
        tiff.putInt(0);
        tiff.putInt(latitudeNumerator).putInt(latitudeDenominator);
        tiff.putInt(0).putInt(1).putInt(0).putInt(1);
        tiff.putInt(127).putInt(1).putInt(0).putInt(1).putInt(0).putInt(1);

        byte[] jpeg = createJpegWithoutExif();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(jpeg, 0, 2);
        output.write(0xff);
        output.write(0xe1);
        int segmentLength = 2 + 6 + tiff.capacity();
        output.write(segmentLength >> 8);
        output.write(segmentLength & 0xff);
        output.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        output.writeBytes(tiff.array());
        output.write(jpeg, 2, jpeg.length - 2);
        return output.toByteArray();
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
