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
    ('SPROUT', '새싹이', 'creatures/sprout', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('CLOUD', '구름이', 'creatures/cloud', 'ACTIVE', 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('EMBER', '불씨', 'creatures/ember', 'ACTIVE', 3, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    name = VALUES(name)
  , asset_key = VALUES(asset_key)
  , status = VALUES(status)
  , display_order = VALUES(display_order);

INSERT INTO quest_templates (
    code
  , period_type
  , difficulty
  , status
  , rotation_order
  , created_at
  , updated_at
) VALUES
    ('DAILY_WATER', 'DAILY', 'LOW', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('DAILY_STRETCH', 'DAILY', 'LOW', 'ACTIVE', 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('WEEKLY_WALK', 'WEEKLY', 'MEDIUM', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    period_type = VALUES(period_type)
  , difficulty = VALUES(difficulty)
  , status = VALUES(status)
  , rotation_order = VALUES(rotation_order);

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
    ('HAT_LEAF', '새싹 모자', '가볍게 쓸 수 있는 새싹 모자', 1000, 'HEAD', 'items/hat-leaf', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
  , ('BODY_CAPE', '초록 망토', '함께 성장하는 모습을 담은 망토', 2000, 'BODY', 'items/body-cape', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    name = VALUES(name)
  , description = VALUES(description)
  , price_krw = VALUES(price_krw)
  , slot_code = VALUES(slot_code)
  , asset_key = VALUES(asset_key)
  , status = VALUES(status);
