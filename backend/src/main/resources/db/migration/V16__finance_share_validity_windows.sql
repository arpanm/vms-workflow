-- Keep every grant as immutable audit history while preventing overlapping
-- non-revoked grants for the same package, recipient and access scope.
-- Unlike the original partial unique index, an expired interval no longer
-- blocks a later grant forever.
CREATE EXTENSION IF NOT EXISTS btree_gist;

DROP INDEX uq_f05_active_package_share;

ALTER TABLE evidence_package_shares
    ADD CONSTRAINT ex_f05_package_share_validity
    EXCLUDE USING gist (
        package_version_id WITH =,
        recipient_subject WITH =,
        access_scope WITH =,
        tstzrange(created_at, expires_at, '[)') WITH &&
    )
    WHERE (revoked_at IS NULL);
