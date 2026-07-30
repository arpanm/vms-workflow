-- V39 and V40 are already deployed append-only migrations and must retain
-- their original checksums. Harden their trigger functions additively.
ALTER FUNCTION delivery_version_content_guard()
    SET search_path = pg_catalog, public;

ALTER FUNCTION f04_evidence_artifact_transition_guard()
    SET search_path = pg_catalog, public;
