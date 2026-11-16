// @ts-check

export const MAX_PHOTO_BYTES = 10 * 1024 * 1024;

/** 사용자가 다시 선택할 수 있도록 사진 선택 오류 코드를 보존합니다. */
export class PhotoSelectionError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'PhotoSelectionError';
    this.code = code;
  }
}

/**
 * 서버 전송 전에 명확히 알 수 있는 JPEG 형식과 10 MiB 한도를 검사합니다.
 * JPEG Signature와 EXIF 유효성은 신뢰 경계인 서버가 최종 판정합니다.
 */
export function validateQuestPhoto(file) {
  if (!(file instanceof File) || file.size === 0) {
    throw new PhotoSelectionError(
        'PHOTO_REQUIRED',
        '제출할 JPEG 사진을 선택해 주세요.',
    );
  }

  if (file.size > MAX_PHOTO_BYTES) {
    throw new PhotoSelectionError(
        'FILE_TOO_LARGE',
        '사진은 최대 10 MiB까지 제출할 수 있습니다.',
    );
  }

  const extension = file.name.split('.').pop()?.toLowerCase();
  if (file.type !== 'image/jpeg' || !['jpg', 'jpeg'].includes(extension ?? '')) {
    throw new PhotoSelectionError(
        'PHOTO_FORMAT_UNSUPPORTED',
        'JPEG(.jpg, .jpeg)만 제출할 수 있습니다. HEIC·HEIF는 JPEG로 내보내 주세요.',
    );
  }

  return file;
}
