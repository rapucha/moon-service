CREATE TABLE calibration_feedback_capacity (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    report_count INTEGER NOT NULL DEFAULT 0 CHECK (report_count >= 0)
);

INSERT INTO calibration_feedback_capacity (singleton, report_count)
VALUES (TRUE, 0);

CREATE TABLE calibration_feedback_report (
    server_report_id UUID PRIMARY KEY,
    client_submission_id UUID NOT NULL UNIQUE,
    schema_version SMALLINT NOT NULL,
    opportunity_id TEXT NOT NULL,
    location_id TEXT NOT NULL,
    ambient_light TEXT,
    crescent_visibility TEXT,
    notes TEXT,
    moon_altitude_degrees DOUBLE PRECISION NOT NULL,
    moon_illumination_percent DOUBLE PRECISION NOT NULL,
    sun_altitude_degrees DOUBLE PRECISION NOT NULL,
    light_bucket TEXT NOT NULL,
    application_revision TEXT NOT NULL,
    idempotency_hash BYTEA NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- This is the initial feedback report format, not Flyway's migration version.
    -- Applied migrations are immutable; a later format must change this constraint in a new migration.
    CONSTRAINT calibration_feedback_schema_version CHECK (schema_version = 1),
    CONSTRAINT calibration_feedback_server_uuid_v4 CHECK (
        substring(server_report_id::text FROM 15 FOR 1) = '4'
        AND substring(server_report_id::text FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
    ),
    CONSTRAINT calibration_feedback_client_uuid_v4 CHECK (
        substring(client_submission_id::text FROM 15 FOR 1) = '4'
        AND substring(client_submission_id::text FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
    ),
    CONSTRAINT calibration_feedback_opportunity_id CHECK (char_length(opportunity_id) >= 1),
    CONSTRAINT calibration_feedback_location_id CHECK (
        char_length(location_id) BETWEEN 1 AND 100
    ),
    CONSTRAINT calibration_feedback_ambient_light CHECK (
        ambient_light IS NULL OR ambient_light IN ('GOOD', 'TOO_BRIGHT', 'TOO_DARK')
    ),
    CONSTRAINT calibration_feedback_crescent_visibility CHECK (
        crescent_visibility IS NULL OR crescent_visibility IN ('VISIBLE', 'TOO_SMALL_TO_SEE')
    ),
    CONSTRAINT calibration_feedback_notes CHECK (
        notes IS NULL OR (
            char_length(notes) BETWEEN 1 AND 4000
            AND notes IS NFC NORMALIZED
            AND notes = btrim(
                notes,
                U&'\0009\000A\000B\000C\000D\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
            )
        )
    ),
    CONSTRAINT calibration_feedback_has_evidence CHECK (
        ambient_light IS NOT NULL OR crescent_visibility IS NOT NULL OR notes IS NOT NULL
    ),
    CONSTRAINT calibration_feedback_moon_altitude CHECK (
        moon_altitude_degrees BETWEEN -90.0 AND 90.0
    ),
    CONSTRAINT calibration_feedback_moon_illumination CHECK (
        moon_illumination_percent BETWEEN 0.0 AND 100.0
    ),
    CONSTRAINT calibration_feedback_sun_altitude CHECK (
        sun_altitude_degrees BETWEEN -90.0 AND 90.0
    ),
    CONSTRAINT calibration_feedback_light_bucket CHECK (
        light_bucket IN ('DAYLIGHT', 'GOLDEN_HOUR', 'CIVIL_TWILIGHT', 'NAUTICAL_TWILIGHT', 'NIGHT')
    ),
    CONSTRAINT calibration_feedback_application_revision CHECK (char_length(application_revision) >= 1),
    CONSTRAINT calibration_feedback_idempotency_hash CHECK (octet_length(idempotency_hash) = 32)
);

CREATE INDEX calibration_feedback_submission_order_idx
    ON calibration_feedback_report (submitted_at, server_report_id);
