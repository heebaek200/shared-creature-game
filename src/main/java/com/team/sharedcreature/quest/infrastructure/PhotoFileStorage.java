package com.team.sharedcreature.quest.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 사진 제출 파일을 로컬 저장소에 저장하고 삭제합니다.
 *
 * DB에는 실제 경로 대신 UUID 기반 storageKey만 저장합니다.
 */
@Component
public class PhotoFileStorage {

    /** 사진 파일 저장 디렉터리 */
    private final Path storageDirectory;

    public PhotoFileStorage(
            @Value("${app.photo.storage-dir:./data/photo-submissions}")
            String storageDirectory
    ) {
        this.storageDirectory = Path.of(storageDirectory)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 사진 파일을 저장하고 DB에 보관할 키를 반환합니다.
     *
     * @param jpegBytes 검증할 JPEG 파일 바이트
     * @return UUID 기반 저장 키
     * @throws IOException 디렉터리 생성 또는 파일 쓰기 실패
     */
    public String store(byte[] jpegBytes) throws IOException {
        if (jpegBytes == null || jpegBytes.length == 0) {
            throw new IllegalArgumentException("저장할 사진 데이터가 없습니다.");
        }

        // 원본 파일명은 사용하지 않고 추측하기 어려운 UUID 이름을 만듭니다.
        String storageKey = UUID.randomUUID() + ".jpg";
        Path target = resolveStoragePath(storageKey);

        Files.createDirectories(storageDirectory);
        Files.write(
                target,
                jpegBytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );

        return storageKey;
    }

    /**
     * storageKey에 해당하는 사진 파일을 삭제합니다.
     *
     * @param storageKey DB에 저장된 UUID 기반 파일 키
     * @throws IOException 파일 삭제 실패
     */
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolveStoragePath(storageKey));
    }

    /**
     * UUID 파일명만 허용해 저장 디렉터리 바깥 경로 접근을 막습니다.
     */
    private Path resolveStoragePath(String storageKey) {
        if (storageKey == null
                || !storageKey.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg"
        )) {
            throw new IllegalArgumentException("storageKey 형식이 올바르지 않습니다.");
        }

        Path target = storageDirectory.resolve(storageKey).normalize();
        if (!target.getParent().equals(storageDirectory)) {
            throw new IllegalArgumentException("저장소 경로가 올바르지 않습니다.");
        }

        return target;
    }
}