package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Display;
import net.fortuna.ical4j.model.parameter.Encoding;
import net.fortuna.ical4j.model.parameter.FmtType;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Image;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.immutable.ImmutableCalScale;
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion;
import net.fortuna.ical4j.validate.ValidationResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

final class ICalendarEventRenderer {
    private static final int UTF8_SAFE_FOLD_LENGTH = 25;
    private static final int MOON_IMAGE_SIZE = 192;
    private static final int MOON_IMAGE_CENTER = MOON_IMAGE_SIZE / 2;
    private static final int MOON_IMAGE_RADIUS = 88;
    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm VV", Locale.ENGLISH);

    private ICalendarEventRenderer() {
    }

    static byte[] render(
            OpportunitySearchResponse.Location location,
            OpportunitySearchResponse.Opportunity opportunity,
            Instant generatedAt
    ) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(opportunity, "opportunity");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Instant startsAt = Instant.parse(opportunity.startsAt());
        Instant endsAt = Instant.parse(opportunity.endsAt());
        Instant minuteStart = startsAt.truncatedTo(ChronoUnit.MINUTES);
        Instant minuteEnd = endsAt.truncatedTo(ChronoUnit.MINUTES);
        if (!minuteEnd.equals(endsAt)) {
            minuteEnd = minuteEnd.plus(1, ChronoUnit.MINUTES);
        }

        VEvent event = new VEvent(new PropertyList(List.of(
                new Uid(uid(location.id(), opportunity.id())),
                new DtStamp(generatedAt.truncatedTo(ChronoUnit.SECONDS)),
                new DtStart<>(minuteStart),
                new DtEnd<>(minuteEnd),
                new Summary(normalizeLineBreaks(
                        "Moon photography opportunity near " + location.displayName())),
                new Location(normalizeLineBreaks(location.displayName())),
                new Description(normalizeLineBreaks(description(location, opportunity))),
                new Image(
                        new ParameterList(List.of(
                                Encoding.BASE64,
                                Value.BINARY,
                                new Display("BADGE"),
                                new FmtType("image/png"))),
                        moonImage(opportunity))
        )));
        Calendar calendar = new Calendar(
                new PropertyList(List.of(
                        ImmutableVersion.VERSION_2_0,
                        new ProdId("-//Moon Service//Moon Opportunity//EN"),
                        ImmutableCalScale.GREGORIAN
                )),
                new ComponentList<>(List.of(event))
        );
        ValidationResult validation = calendar.validate();
        if (validation.hasErrors()) {
            throw new IllegalStateException("Generated iCalendar failed validation.");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            new CalendarOutputter(false, UTF8_SAFE_FOLD_LENGTH).output(calendar, output);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to serialize iCalendar event.", exception);
        }
        return output.toByteArray();
    }

    private static String moonImage(OpportunitySearchResponse.Opportunity opportunity) {
        BufferedImage image = new BufferedImage(
                MOON_IMAGE_SIZE, MOON_IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        AtomMoonRenderer.draw(
                image,
                MOON_IMAGE_CENTER,
                MOON_IMAGE_CENTER,
                MOON_IMAGE_RADIUS,
                AtomMoonRenderer.MoonStyle.from(opportunity.moon()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG image writer is unavailable.");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to serialize Moon image.", exception);
        }
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private static String uid(String locationId, String opportunityId) {
        String seed = "moon-service.ics.event.v1\n" + locationId + "\n" + opportunityId;
        return "urn:uuid:" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String description(
            OpportunitySearchResponse.Location location,
            OpportunitySearchResponse.Opportunity opportunity
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("Suggested local time: " + LOCAL_TIME.format(
                Instant.parse(opportunity.suggestedAt()).atZone(ZoneId.of(location.timezone()))) + ".");
        lines.add("Moon phase: " + plainLabel(opportunity.moon().phaseName())
                + "; illumination: " + oneDecimal(opportunity.moon().illuminationPercent())
                + "%; altitude: " + oneDecimal(opportunity.moon().altitudeDegrees()) + " degrees.");
        OpportunitySearchResponse.Weather weather = Objects.requireNonNull(
                opportunity.weather(), "opportunity.weather");
        String weatherSummary = Objects.requireNonNull(
                weather.summary(), "opportunity.weather.summary").strip();
        lines.add("Weather: " + weatherSummary + ".");
        return String.join("\n", lines);
    }

    private static String plainLabel(String value) {
        return Objects.requireNonNull(value, "value").replace('_', ' ').toLowerCase(Locale.ENGLISH);
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ENGLISH, "%.1f", value);
    }

    private static String normalizeLineBreaks(String value) {
        return Objects.requireNonNull(value, "value")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
