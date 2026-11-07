CREATE TABLE stockrescue.product_gtin_review_scope (
    org_id UUID NOT NULL REFERENCES stockrescue.organization(id) ON DELETE RESTRICT,
    gtin VARCHAR(14) COLLATE "C" NOT NULL CHECK (stockrescue.catalog_gtin_valid(gtin)),
    version BIGINT NOT NULL CHECK (version>=0 AND version<9223372036854775807),
    PRIMARY KEY (org_id,gtin)
);

INSERT INTO stockrescue.product_gtin_review_scope(org_id,gtin,version)
SELECT org_id,gtin,count(*)::BIGINT
FROM stockrescue.product
WHERE gtin IS NOT NULL
GROUP BY org_id,gtin;

CREATE FUNCTION stockrescue.bump_product_gtin_review_scope() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.gtin IS NOT NULL THEN
            INSERT INTO stockrescue.product_gtin_review_scope(org_id,gtin,version)
            VALUES (NEW.org_id,NEW.gtin,1)
            ON CONFLICT (org_id,gtin) DO UPDATE
                SET version=stockrescue.product_gtin_review_scope.version+1;
        END IF;
        RETURN NEW;
    ELSIF TG_OP='UPDATE' THEN
        IF OLD.org_id IS DISTINCT FROM NEW.org_id OR OLD.gtin IS DISTINCT FROM NEW.gtin THEN
            IF OLD.gtin IS NOT NULL THEN
                UPDATE stockrescue.product_gtin_review_scope SET version=version+1
                WHERE org_id=OLD.org_id AND gtin=OLD.gtin;
            END IF;
            IF NEW.gtin IS NOT NULL THEN
                INSERT INTO stockrescue.product_gtin_review_scope(org_id,gtin,version)
                VALUES (NEW.org_id,NEW.gtin,1)
                ON CONFLICT (org_id,gtin) DO UPDATE
                    SET version=stockrescue.product_gtin_review_scope.version+1;
            END IF;
        ELSIF (OLD.id IS DISTINCT FROM NEW.id
                OR OLD.active IS DISTINCT FROM NEW.active
                OR OLD.sku_normalized IS DISTINCT FROM NEW.sku_normalized
                OR OLD.name IS DISTINCT FROM NEW.name) AND NEW.gtin IS NOT NULL THEN
            UPDATE stockrescue.product_gtin_review_scope SET version=version+1
            WHERE org_id=NEW.org_id AND gtin=NEW.gtin;
        END IF;
        RETURN NEW;
    ELSE
        IF OLD.gtin IS NOT NULL THEN
            UPDATE stockrescue.product_gtin_review_scope SET version=version+1
            WHERE org_id=OLD.org_id AND gtin=OLD.gtin;
        END IF;
        RETURN OLD;
    END IF;
END;
$$;

CREATE TRIGGER product_gtin_review_scope_insert AFTER INSERT ON stockrescue.product
    FOR EACH ROW EXECUTE FUNCTION stockrescue.bump_product_gtin_review_scope();
CREATE TRIGGER product_gtin_review_scope_update AFTER UPDATE OF id,org_id,gtin,active,sku_normalized,name ON stockrescue.product
    FOR EACH ROW EXECUTE FUNCTION stockrescue.bump_product_gtin_review_scope();
CREATE TRIGGER product_gtin_review_scope_delete AFTER DELETE ON stockrescue.product
    FOR EACH ROW EXECUTE FUNCTION stockrescue.bump_product_gtin_review_scope();

COMMENT ON TABLE stockrescue.product_gtin_review_scope IS
    'Per-organization exact GTIN serialization row. Its revision changes when a matching product is added, removed, renamed, recoded, moved or changes active state.';
