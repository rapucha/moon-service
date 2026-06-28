package dev.moonservice.backend.opportunity.scoring;

import dev.moonservice.scoringprototype.service.OpportunityService;
import dev.moonservice.scoringprototype.window.MoonWindow;
import dev.moonservice.scoringprototype.window.WindowGenerator;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

final class LiveOpportunityWindowSelector implements OpportunityService.WindowAdjustment {
    private final Instant notBefore;

    LiveOpportunityWindowSelector(Instant notBefore) {
        this.notBefore = Objects.requireNonNull(notBefore, "notBefore");
    }

    @Override
    public Optional<MoonWindow> adjust(MoonWindow window, WindowGenerator.SampleProvider samples) {
        if (!window.endsAt().isAfter(notBefore)) {
            return Optional.empty();
        }
        if (window.startsAt().isAfter(notBefore)) {
            return Optional.of(window);
        }
        return Optional.of(WindowGenerator.withSuggestedAtOrAfter(window, samples, notBefore));
    }
}
