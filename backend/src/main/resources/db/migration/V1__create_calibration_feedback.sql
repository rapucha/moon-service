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
    report_mode TEXT NOT NULL,
    timing_kind TEXT NOT NULL,
    entered_local_datetime TIMESTAMP WITHOUT TIME ZONE,
    corrected_local_datetime TIMESTAMP WITHOUT TIME ZONE,
    resolved_local_datetime TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    timing_timezone TEXT NOT NULL,
    utc_offset_seconds INTEGER NOT NULL,
    timing_source TEXT NOT NULL,
    timing_confidence TEXT NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    location_id TEXT NOT NULL,
    location_display_name TEXT NOT NULL,
    latitude NUMERIC NOT NULL,
    longitude NUMERIC NOT NULL,
    elevation_meters INTEGER NOT NULL,
    location_timezone TEXT NOT NULL,
    country_code TEXT NOT NULL,
    overall_rating TEXT NOT NULL,
    moon_rating TEXT NOT NULL,
    ambient_light_rating TEXT NOT NULL,
    weather_rating TEXT NOT NULL,
    horizon_rating TEXT NOT NULL,
    notes TEXT NOT NULL,
    recommendation_snapshot JSONB,
    astronomy_snapshot JSONB NOT NULL,
    application_revision TEXT NOT NULL,
    idempotency_hash BYTEA NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT calibration_feedback_schema_version CHECK (schema_version = 1),
    CONSTRAINT calibration_feedback_server_uuid_v4 CHECK (
        substring(server_report_id::text FROM 15 FOR 1) = '4'
        AND substring(server_report_id::text FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
    ),
    CONSTRAINT calibration_feedback_client_uuid_v4 CHECK (
        substring(client_submission_id::text FROM 15 FOR 1) = '4'
        AND substring(client_submission_id::text FROM 20 FOR 1) IN ('8', '9', 'a', 'b')
    ),
    CONSTRAINT calibration_feedback_mode CHECK (
        report_mode IN ('recommendation_review', 'observation')
    ),
    CONSTRAINT calibration_feedback_timing_kind CHECK (timing_kind IN ('now', 'past')),
    CONSTRAINT calibration_feedback_timing_shape CHECK (
        (
            timing_kind = 'now'
            AND entered_local_datetime IS NULL
            AND corrected_local_datetime IS NULL
            AND timing_source = 'server_receipt'
            AND timing_confidence = 'exact'
        )
        OR
        (
            timing_kind = 'past'
            AND entered_local_datetime IS NOT NULL
            AND corrected_local_datetime IS NOT NULL
            AND timing_source IN (
                'camera_metadata', 'phone_metadata', 'written_record', 'memory', 'other'
            )
            AND timing_confidence IN (
                'exact', 'within_5_minutes', 'within_30_minutes', 'within_2_hours', 'date_only'
            )
        )
    ),
    CONSTRAINT calibration_feedback_timezone_match CHECK (timing_timezone = location_timezone),
    CONSTRAINT calibration_feedback_utc_offset CHECK (
        utc_offset_seconds BETWEEN -64800 AND 64800
    ),
    CONSTRAINT calibration_feedback_location_id CHECK (
        char_length(location_id) BETWEEN 1 AND 100
    ),
    CONSTRAINT calibration_feedback_location_name CHECK (char_length(location_display_name) >= 1),
    CONSTRAINT calibration_feedback_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT calibration_feedback_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT calibration_feedback_location_timezone CHECK (char_length(location_timezone) >= 1),
    CONSTRAINT calibration_feedback_country_code CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT calibration_feedback_overall_rating CHECK (
        overall_rating IN ('positive', 'marginal', 'negative')
    ),
    CONSTRAINT calibration_feedback_moon_rating CHECK (
        moon_rating IN ('clear', 'partial', 'not_visible', 'unknown')
    ),
    CONSTRAINT calibration_feedback_ambient_light_rating CHECK (
        ambient_light_rating IN ('sufficient', 'marginal', 'insufficient', 'unknown')
    ),
    CONSTRAINT calibration_feedback_weather_rating CHECK (
        weather_rating IN ('better', 'matched', 'worse', 'not_compared')
    ),
    CONSTRAINT calibration_feedback_horizon_rating CHECK (
        horizon_rating IN ('none', 'minor', 'blocked', 'unknown')
    ),
    CONSTRAINT calibration_feedback_notes CHECK (char_length(notes) BETWEEN 10 AND 4000),
    CONSTRAINT calibration_feedback_report_shape CHECK (
        (report_mode = 'recommendation_review' AND recommendation_snapshot IS NOT NULL)
        OR
        (
            report_mode = 'observation'
            AND recommendation_snapshot IS NULL
            AND weather_rating = 'not_compared'
        )
    ),
    CONSTRAINT calibration_feedback_recommendation_json CHECK (
        recommendation_snapshot IS NULL OR jsonb_typeof(recommendation_snapshot) = 'object'
    ),
    CONSTRAINT calibration_feedback_astronomy_json CHECK (
        jsonb_typeof(astronomy_snapshot) = 'object'
    ),
    CONSTRAINT calibration_feedback_application_revision CHECK (char_length(application_revision) >= 1),
    CONSTRAINT calibration_feedback_idempotency_hash CHECK (octet_length(idempotency_hash) = 32)
);

CREATE INDEX calibration_feedback_submission_order_idx
    ON calibration_feedback_report (submitted_at, server_report_id);
