DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM stockrescue.lot_registration
        GROUP BY org_id,request_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot enforce organization-scoped lot request identifiers while historical request IDs are duplicated';
    END IF;
END $$;

ALTER TABLE stockrescue.lot_registration DROP CONSTRAINT lot_registration_pkey;
ALTER TABLE stockrescue.lot_registration ADD CONSTRAINT lot_registration_pkey PRIMARY KEY (org_id,request_id);
ALTER TABLE stockrescue.lot_registration ADD COLUMN store_id UUID;
ALTER TABLE stockrescue.lot_registration ADD CONSTRAINT lot_registration_store_fk
    FOREIGN KEY (org_id,store_id) REFERENCES stockrescue.store(org_id,id) ON DELETE RESTRICT;
CREATE INDEX lot_registration_store_idx ON stockrescue.lot_registration(org_id,store_id,recorded_at) WHERE store_id IS NOT NULL;
