INSERT INTO seasons (
    season_number
  , start_at
  , end_at
  , status
  , version
  , created_at
  , updated_at
) VALUES (
    1
  , '2026-11-01 15:00:00.000000'
  , '2026-11-29 15:00:00.000000'
  , 'ACTIVE'
  , 0
  , UTC_TIMESTAMP(6)
  , UTC_TIMESTAMP(6)
) ON DUPLICATE KEY UPDATE season_number = VALUES(season_number);

INSERT INTO creature_species (
    code
  , name
  , asset_key
  , status
  , display_order
  , created_at
  , updated_at
) VALUES
    ('MOSS_GOLEM', '이끼 낀 바위 골렘', 'moss-golem', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('ABYSS_JELLYFISH', '공허 속 우주 해파리', 'abyss-jellyfish', 'ACTIVE', 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('SPROUT', '새싹이', 'creatures/sprout', 'INACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('CLOUD', '구름이', 'creatures/cloud', 'INACTIVE', 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('EMBER', '불씨', 'creatures/ember', 'INACTIVE', 3, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    name = VALUES(name)
  , asset_key = VALUES(asset_key)
  , status = VALUES(status)
  , display_order = VALUES(display_order);

-- 퀘스트 카탈로그는 db/quest-catalog.sql에서 별도로 초기화합니다.

INSERT INTO shop_items (
    code
  , name
  , description
  , price_krw
  , slot_code
  , asset_key
  , status
  , created_at
  , updated_at
) VALUES
    ('RIBBON_HAIRBAND', '리본 헤어밴드', '리본 장식 헤어밴드', 1000, 'ACCESSORY', 'ribbon-hairband', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('BLUE_SHIRT', '파란 셔츠', '파란색 셔츠 의상', 2000, 'BODY', 'blue-shirt', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('YELLOW_SMILE', '노란 미소', '노란색 미소 표정', 1000, 'FACE', 'yellow-smile', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('BROWN_LOB', '갈색 단발', '갈색 단발 헤어스타일', 2000, 'HEAD', 'brown-lob', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('HAT_LEAF', '새싹 모자', '기존 참조 보존용 판매 종료 상품', 1000, 'HEAD', 'hat-leaf', 'INACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('BODY_CAPE', '초록 망토', '기존 참조 보존용 판매 종료 상품', 2000, 'BODY', 'body-cape', 'INACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    name = VALUES(name)
  , description = VALUES(description)
  , price_krw = VALUES(price_krw)
  , slot_code = VALUES(slot_code)
  , asset_key = VALUES(asset_key)
  , status = VALUES(status);

-- 아바타 이미지 연동 테스트용 상품
INSERT INTO shop_items (
    code,
    name,
    description,
    price_krw,
    slot_code,
    asset_key,
    status,
    created_at,
    updated_at
)
VALUES
    -- ACCESSORY
    (
        'RIBBON_HAIRBAND', '리본 머리띠', '리본 머리띠',
        1000, 'ACCESSORY', 'ribbon-hairband', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),
    (
        'BLACK_CAP', '검정색 모자', '검정색 모자',
        1000, 'ACCESSORY', 'black-cap', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),
    (
        'YELLOW_CLIP', '노란색 머리핀', '노란색 머리핀',
        1000, 'ACCESSORY', 'yellow-clip', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),

    -- HEAD
    (
        'BROWN_LOB', '갈색 단발 머리', '갈색 단발 머리',
        1000, 'HEAD', 'brown-lob', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),
    (
        'BLACK_LONG', '검정색 긴 머리', '검정색 긴 머리',
        1000, 'HEAD', 'black-long', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),
    (
        'BLUE_SHORT', '파란색 짧은 머리', '파란색 짧은 머리',
        1000, 'HEAD', 'blue-short', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),

    -- FACE
    (
        'BLUE_SMILE', '웃는 얼굴(파랑)', '웃는 얼굴(파랑)',
        1000, 'FACE', 'blue-smile', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),
    (
        'YELLOW_SMILE', '웃는 얼굴(노랑)', '웃는 얼굴(노랑)',
        1000, 'FACE', 'yellow-smile', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),
    (
        'PINK_SAD', '우는 얼굴(분홍)', '우는 얼굴(분홍)',
        1000, 'FACE', 'pink-sad', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),

    -- BODY
    (
        'BLUE_SHIRT', '티셔츠 세트(파랑)', '티셔츠 세트(파랑)',
        1000, 'BODY', 'blue-shirt', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),
    (
        'WHITE_OVERALLS', '멜빵 세트(하양)', '멜빵 세트(하양)',
        1000, 'BODY', 'white-overalls', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    ),
    (
        'GREEN_DRESS', '원피스 세트(초록)', '원피스 세트(초록)',
        1000, 'BODY', 'green-dress', 'ACTIVE',
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
    )
    ON DUPLICATE KEY UPDATE
                         name = VALUES(name),
                         description = VALUES(description),
                         slot_code = VALUES(slot_code),
                         asset_key = VALUES(asset_key),
                         updated_at = UTC_TIMESTAMP(6);
