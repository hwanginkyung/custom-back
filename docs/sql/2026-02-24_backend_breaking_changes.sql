-- Backend breaking changes (2026-02-24)
-- Target DB: MySQL 8.x

START TRANSACTION;

-- 1) shipper: add shipper_type, remove export_destination_country
ALTER TABLE shipper
    ADD COLUMN IF NOT EXISTS shipper_type VARCHAR(40) NULL;

-- existing rows fallback
UPDATE shipper
SET shipper_type = 'CORPORATE_BUSINESS'
WHERE shipper_type IS NULL;

ALTER TABLE shipper
    DROP COLUMN IF EXISTS export_destination_country;

-- 2) vehicle: purchase_source -> owner_type, remove export_destination_country,
--            first_registrated_at -> first_registration_date,
--            add additional spec fields
ALTER TABLE vehicle
    CHANGE COLUMN purchase_source owner_type VARCHAR(40) NULL;

ALTER TABLE vehicle
    DROP COLUMN IF EXISTS export_destination_country;

ALTER TABLE vehicle
    CHANGE COLUMN first_registrated_at first_registration_date DATE NULL;

ALTER TABLE vehicle
    ADD COLUMN IF NOT EXISTS manufacture_year_month VARCHAR(7) NULL,
    ADD COLUMN IF NOT EXISTS weight INT NULL,
    ADD COLUMN IF NOT EXISTS seating_capacity INT NULL,
    ADD COLUMN IF NOT EXISTS length INT NULL,
    ADD COLUMN IF NOT EXISTS height INT NULL,
    ADD COLUMN IF NOT EXISTS width INT NULL;

-- transmission was free text, now enum-like code(MT/AT/CVT/DCT/ETC) on application layer.

-- 3) customs_request: shipping_method default null
ALTER TABLE customs_request
    MODIFY COLUMN shipping_method VARCHAR(20) NULL;

-- 4) customs_request container info: add warehouse_location for shoring
ALTER TABLE customs_request
    ADD COLUMN IF NOT EXISTS warehouse_location VARCHAR(100) NULL;

COMMIT;
