package dev.moonservice.backend.feedback;

import dev.moonservice.backend.admission.HostedAlphaProviderAdmission;
import dev.moonservice.backend.location.LocationResolution;
import dev.moonservice.backend.location.LocationResolver;
import dev.moonservice.backend.location.ResolvedLocation;
import dev.moonservice.scoringprototype.scoring.ScoringModel;
import io.github.cosinekitty.astronomy.Aberration;
import io.github.cosinekitty.astronomy.Astronomy;
import io.github.cosinekitty.astronomy.Body;
import io.github.cosinekitty.astronomy.EquatorEpoch;
import io.github.cosinekitty.astronomy.Equatorial;
import io.github.cosinekitty.astronomy.IlluminationInfo;
import io.github.cosinekitty.astronomy.Observer;
import io.github.cosinekitty.astronomy.Refraction;
import io.github.cosinekitty.astronomy.Time;
import io.github.cosinekitty.astronomy.Topocentric;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Service
final class CalibrationFeedbackService {
    private static final int WRITE_CAPACITY = 12;
    private static final long REFILL_NANOS = Duration.ofHours(1).toNanos();

    private final CalibrationFeedbackRepository repository;
    private final LocationResolver locationResolver;
    private final boolean featureEnabled;
    private final BiFunction<ResolvedLocation, Instant, CalibrationFeedbackReport.AstronomyFacts> astronomy;
    private final WriteLimiter writeLimiter;
    private final Function<Supplier<LocationResolution>, Optional<LocationResolution>> locationAdmission;

    @Autowired
    CalibrationFeedbackService(
            CalibrationFeedbackRepository repository,
            LocationResolver locationResolver,
            HostedAlphaProviderAdmission providerAdmission,
            @Value("${moon.feedback.enabled:false}") boolean featureEnabled
    ) {
        this(repository, locationResolver, featureEnabled,
                CalibrationFeedbackService::computeAstronomy, System::nanoTime,
                locationAdmission(providerAdmission));
    }

    CalibrationFeedbackService(
            CalibrationFeedbackRepository repository,
            LocationResolver locationResolver,
            boolean featureEnabled,
            BiFunction<ResolvedLocation, Instant, CalibrationFeedbackReport.AstronomyFacts> astronomy,
            LongSupplier monotonicNanos,
            Function<Supplier<LocationResolution>, Optional<LocationResolution>> locationAdmission
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.locationResolver = Objects.requireNonNull(locationResolver, "locationResolver");
        this.featureEnabled = featureEnabled;
        this.astronomy = Objects.requireNonNull(astronomy, "astronomy");
        this.writeLimiter = new WriteLimiter(Objects.requireNonNull(monotonicNanos, "monotonicNanos"));
        this.locationAdmission = Objects.requireNonNull(locationAdmission, "locationAdmission");
    }

    private static Function<Supplier<LocationResolution>, Optional<LocationResolution>> locationAdmission(
            HostedAlphaProviderAdmission providerAdmission
    ) {
        Objects.requireNonNull(providerAdmission, "providerAdmission");
        return operation -> {
            try (HostedAlphaProviderAdmission.Admission admission = providerAdmission.tryAcquire()) {
                return admission.accepted() ? Optional.ofNullable(operation.get()) : Optional.empty();
            }
        };
    }

    Capability capability() {
        if (!featureEnabled) {
            return new Capability("disabled", "disabled");
        }
        CalibrationFeedbackRepository.RepositoryStatus status;
        try {
            status = repository.status();
        } catch (RuntimeException exception) {
            return new Capability("enabled", "unavailable");
        }
        if (status instanceof CalibrationFeedbackRepository.RepositoryStatus.Disabled) {
            return new Capability("enabled", "disabled");
        }
        if (status instanceof CalibrationFeedbackRepository.RepositoryStatus.Available available
                && available.state() != CalibrationFeedbackRepository.CapacityState.FULL) {
            return new Capability("enabled", "available");
        }
        return new Capability("enabled", "unavailable");
    }

    SubmissionResult submit(
            CalibrationFeedbackSubmission submission,
            Instant receivedAt,
            String applicationRevision
    ) {
        Objects.requireNonNull(submission, "submission");
        Instant submittedAt = Objects.requireNonNull(receivedAt, "receivedAt").truncatedTo(ChronoUnit.MICROS);
        if (!featureEnabled) {
            return new Unavailable();
        }

        CalibrationFeedbackRepository.LookupResult lookup;
        try {
            lookup = repository.findByClientSubmissionId(submission.clientSubmissionId());
        } catch (RuntimeException exception) {
            return new Unavailable();
        }
        if (lookup instanceof CalibrationFeedbackRepository.LookupResult.Found found) {
            return MessageDigest.isEqual(found.idempotencyHash(), submission.idempotencyHash())
                    ? new Replayed(submission.clientSubmissionId(), found.serverReportId(), found.submittedAt())
                    : new Conflict();
        }
        if (!(lookup instanceof CalibrationFeedbackRepository.LookupResult.NotFound)) {
            return new Unavailable();
        }

        CalibrationFeedbackRepository.RepositoryStatus status;
        try {
            status = repository.status();
        } catch (RuntimeException exception) {
            return new Unavailable();
        }
        if (!(status instanceof CalibrationFeedbackRepository.RepositoryStatus.Available available)
                || available.state() == CalibrationFeedbackRepository.CapacityState.FULL) {
            return new Unavailable();
        }

        Optional<LocationResolution> admittedResolution;
        try {
            admittedResolution = Objects.requireNonNull(locationAdmission.apply(
                    () -> locationResolver.resolveLocationId(submission.locationId())));
        } catch (RuntimeException exception) {
            return new Unavailable();
        }
        if (admittedResolution.isEmpty()) {
            return new Unavailable();
        }
        LocationResolution resolution = Objects.requireNonNull(admittedResolution.orElseThrow());
        if (resolution.status() == LocationResolution.Status.NOT_FOUND) {
            return new LocationNotFound();
        }
        if (resolution.status() != LocationResolution.Status.RESOLVED) {
            return new Unavailable();
        }
        ResolvedLocation location;
        try {
            location = resolution.singleCandidate().orElseThrow();
        } catch (RuntimeException exception) {
            return new Unavailable();
        }

        WriteLimiter.Admission admission = writeLimiter.tryAcquire();
        if (!admission.accepted()) {
            return new RateLimited(admission.retryAfterSeconds());
        }

        CalibrationFeedbackReport report;
        try {
            report = new CalibrationFeedbackReport(
                    submission.schemaVersion(),
                    submission.clientSubmissionId(),
                    submission.opportunityId(),
                    location.locationId(),
                    submission.ambientLight(),
                    submission.crescentVisibility(),
                    submission.notes(),
                    astronomy.apply(location, submittedAt),
                    applicationRevision,
                    submission.idempotencyHash(),
                    submittedAt);
        } catch (RuntimeException exception) {
            return new Unavailable();
        }

        CalibrationFeedbackRepository.StoreResult stored;
        try {
            stored = repository.store(report);
        } catch (RuntimeException exception) {
            return new Unavailable();
        }
        if (stored instanceof CalibrationFeedbackRepository.StoreResult.Created created) {
            return new Created(submission.clientSubmissionId(), created.serverReportId(), created.submittedAt());
        }
        if (stored instanceof CalibrationFeedbackRepository.StoreResult.Replayed replayed) {
            return new Replayed(submission.clientSubmissionId(), replayed.serverReportId(), replayed.submittedAt());
        }
        if (stored instanceof CalibrationFeedbackRepository.StoreResult.Conflict) {
            return new Conflict();
        }
        return new Unavailable();
    }

    private static CalibrationFeedbackReport.AstronomyFacts computeAstronomy(
            ResolvedLocation location,
            Instant instant
    ) {
        OffsetDateTime utc = instant.atOffset(ZoneOffset.UTC);
        double second = utc.getSecond() + utc.getNano() / 1_000_000_000.0;
        Time time = new Time(
                utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth(), utc.getHour(), utc.getMinute(), second);
        Observer observer = new Observer(
                location.latitude(), location.longitude(), location.elevationMeters());
        Topocentric moon = horizon(Body.Moon, time, observer);
        Topocentric sun = horizon(Body.Sun, time, observer);
        IlluminationInfo illumination = Astronomy.illumination(Body.Moon, time);
        CalibrationFeedbackReport.LightBucket lightBucket = CalibrationFeedbackReport.LightBucket.valueOf(
                ScoringModel.lightBucket(sun.getAltitude()).toUpperCase(Locale.ROOT));
        return new CalibrationFeedbackReport.AstronomyFacts(
                moon.getAltitude(),
                100.0 * illumination.getPhaseFraction(),
                sun.getAltitude(),
                lightBucket);
    }

    private static Topocentric horizon(Body body, Time time, Observer observer) {
        Equatorial equatorial = Astronomy.equator(
                body, time, observer, EquatorEpoch.OfDate, Aberration.Corrected);
        return Astronomy.horizon(
                time, observer, equatorial.getRa(), equatorial.getDec(), Refraction.Normal);
    }

    record Capability(String featureState, String submissionAvailability) {
    }

    interface SubmissionResult {
    }

    record Created(UUID clientSubmissionId, UUID serverReportId, Instant submittedAt) implements SubmissionResult {
    }

    record Replayed(UUID clientSubmissionId, UUID serverReportId, Instant submittedAt) implements SubmissionResult {
    }

    record Conflict() implements SubmissionResult {
    }

    record LocationNotFound() implements SubmissionResult {
    }

    record RateLimited(long retryAfterSeconds) implements SubmissionResult {
    }

    record Unavailable() implements SubmissionResult {
    }

    private static final class WriteLimiter {
        private final LongSupplier monotonicNanos;
        private int tokens = WRITE_CAPACITY;
        private long refilledAt;

        private WriteLimiter(LongSupplier monotonicNanos) {
            this.monotonicNanos = monotonicNanos;
            this.refilledAt = monotonicNanos.getAsLong();
        }

        private synchronized Admission tryAcquire() {
            long now = monotonicNanos.getAsLong();
            long elapsed = now - refilledAt;
            if (elapsed >= REFILL_NANOS) {
                long intervals = elapsed / REFILL_NANOS;
                tokens = (int) Math.min(WRITE_CAPACITY, tokens + Math.min(intervals, WRITE_CAPACITY));
                refilledAt += intervals * REFILL_NANOS;
                elapsed = now - refilledAt;
            }
            if (tokens > 0) {
                tokens--;
                return new Admission(true, 0L);
            }
            long remaining = REFILL_NANOS - Math.max(0L, elapsed);
            long retryAfter = Math.max(1L, (remaining + 999_999_999L) / 1_000_000_000L);
            return new Admission(false, retryAfter);
        }

        private record Admission(boolean accepted, long retryAfterSeconds) {
        }
    }
}

final class CalibrationFeedbackDigest {
    private static final byte[] PREFIX =
            "moon-service/calibration-feedback/idempotency/v1".getBytes(StandardCharsets.US_ASCII);

    private CalibrationFeedbackDigest() {
    }

    static byte[] hash(
            String locationId,
            String opportunityId,
            String ambientLight,
            String crescentVisibility,
            String notes
    ) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(frameInput(
                    locationId, opportunityId, ambientLight, crescentVisibility, notes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    static byte[] frameInput(
            String locationId,
            String opportunityId,
            String ambientLight,
            String crescentVisibility,
            String notes
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(PREFIX);
        for (String value : new String[]{locationId, opportunityId, ambientLight, crescentVisibility, notes}) {
            if (value == null) {
                output.write(0);
                continue;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            output.write(1);
            output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            output.writeBytes(bytes);
        }
        return output.toByteArray();
    }
}
