package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import org.springframework.web.util.UriUtils;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Converts search results into stable display values and serializes those
 * values as Atom XML. {@link AtomFeedService} owns refresh and cache state and
 * calls this renderer; this renderer calls {@link AtomEntryPreviewRenderer}
 * for the image embedded in each entry. Keeping presentation here lets the
 * service compare complete visible values before choosing Atom timestamps.
 */
final class AtomFeedDocumentRenderer {
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
    private static final String XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml";
    private static final String FEED_ID_SEED_PREFIX = "moon-service.atom.feed.v1\n";
    private static final String ENTRY_ID_SEED_PREFIX = "moon-service.atom.entry.v1\n";
    private static final String AUTHOR = "Moon Service";
    private static final String HORIZON_CAVEAT =
            "Local hills, buildings, or trees may affect exact visibility near the horizon.";
    private static final String LIVE_RESULT_WARNING =
            "Open the live result before leaving because forecasts and recommendations can change.";
    private static final String EYE_SAFETY_PREFIX = "🚨 Eye safety:";
    private static final String EYE_SAFETY_REMAINDER =
            "search for the Moon near the Sun through binoculars, a telescope,"
                    + " or a camera's optical viewfinder.";
    private static final double EYE_SAFETY_MAX_ILLUMINATION_PERCENT = 10.0;
    private static final DateTimeFormatter TITLE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm z", Locale.ENGLISH);

    private AtomFeedDocumentRenderer() {
    }

    static FeedMetadata metadata(OpportunitySearchResponse.Location location) {
        /*
         * Canonical IDs make feed identity stable across host names and
         * display-name changes. Only the query value is encoded; relative
         * links let each deployment supply its own public origin.
         */
        String canonicalId = location.id();
        String encodedId = UriUtils.encodeQueryParam(canonicalId, StandardCharsets.UTF_8);
        return new FeedMetadata(
                "Moon opportunities near " + location.displayName(),
                atomId(FEED_ID_SEED_PREFIX + canonicalId),
                AUTHOR,
                "/feeds/atom?locationId=" + encodedId);
    }

    static DisplayedEntry displayedEntry(
            OpportunitySearchResponse.Location location,
            OpportunitySearchResponse.Opportunity opportunity
    ) {
        /*
         * The title is local for quick reading; the section timestamps stay
         * precise instants. Entry identity combines the canonical location and
         * window start, so a recommendation refresh can update the same entry.
         * The alternate result URL follows the same relative-link rule.
         */
        String canonicalId = location.id();
        String encodedId = UriUtils.encodeQueryParam(canonicalId, StandardCharsets.UTF_8);
        String title = TITLE_TIME.format(Instant.parse(opportunity.suggestedAt())
                        .atZone(ZoneId.of(location.timezone())))
                + " — Moon opportunity near " + location.displayName();
        String phase = plainLabel(opportunity.moon().phaseName());
        String altitude = oneDecimal(opportunity.moon().altitudeDegrees()) + " degrees";
        String illumination = oneDecimal(opportunity.moon().illuminationPercent()) + "%";
        List<String> when = List.of(
                "Suggested: " + opportunity.suggestedAt(),
                "Window: " + opportunity.startsAt() + " to " + opportunity.endsAt(),
                "Timezone: " + location.timezone(),
                "Moon altitude: " + altitude);
        List<String> conditions = List.of(
                "Phase: " + phase,
                "Moon illumination: " + illumination,
                "Weather: " + opportunity.weather().summary(),
                "Confidence: " + plainLabel(opportunity.confidence()),
                "Ambient light: " + plainLabel(opportunity.sun().lightBucket()));
        boolean eyeSafety = needsEyeSafetyWarning(
                opportunity.moon().phaseName(),
                opportunity.moon().illuminationPercent());
        List<String> before = List.of(LIVE_RESULT_WARNING, HORIZON_CAVEAT);
        String summary = "When — Starts: " + opportunity.startsAt()
                + "; Suggested: " + opportunity.suggestedAt()
                + "; Ends: " + opportunity.endsAt()
                + "; Timezone: " + location.timezone()
                + "; Moon altitude: " + altitude + ".\n"
                + "Conditions — Phase: " + phase
                + "; Moon illumination: " + illumination
                + "; Weather: " + opportunity.weather().summary()
                + "; Confidence: " + plainLabel(opportunity.confidence())
                + "; Ambient light: " + plainLabel(opportunity.sun().lightBucket()) + ".\n"
                + "Before you go — " + LIVE_RESULT_WARNING + " " + HORIZON_CAVEAT
                + (eyeSafety ? " " + eyeSafetyText() : "");
        AtomEntryPreviewRenderer.Preview preview = AtomEntryPreviewRenderer.preview(
                opportunity, location.timezone());
        String previewAlt = "Visual preview: " + phase + " Moon, Moon path, and "
                + plainLabel(preview.weather().name()) + " weather.";
        return new DisplayedEntry(
                atomId(ENTRY_ID_SEED_PREFIX + canonicalId + "\n" + opportunity.startsAt()),
                title,
                summary,
                "/search?locationId=" + encodedId,
                opportunity.suggestedAt(),
                when,
                conditions,
                before,
                eyeSafety,
                preview,
                previewAlt);
    }

    static byte[] render(
            FeedMetadata metadata,
            List<EntrySnapshot> entries,
            String feedUpdated
    ) {
        /* StAX supplies XML escaping while fixed element order gives the ETag one repeatable input. */
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory()
                    .createXMLStreamWriter(output, StandardCharsets.UTF_8.name());
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement("feed");
            xml.writeDefaultNamespace(ATOM_NAMESPACE);
            textElement(xml, "title", metadata.title());
            textElement(xml, "id", metadata.id());
            textElement(xml, "updated", feedUpdated);
            xml.writeStartElement("author");
            textElement(xml, "name", metadata.author());
            xml.writeEndElement();
            link(xml, "self", metadata.selfUrl(), "application/atom+xml");
            for (EntrySnapshot entry : entries) {
                writeEntry(xml, entry);
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("Could not render Atom XML.", ex);
        }
        return output.toByteArray();
    }

    private static void writeEntry(XMLStreamWriter xml, EntrySnapshot entry)
            throws XMLStreamException {
        DisplayedEntry displayed = entry.displayed();
        xml.writeStartElement("entry");
        textElement(xml, "id", displayed.id());
        textElement(xml, "title", displayed.title());
        textElement(xml, "updated", entry.updated());
        link(xml, "alternate", displayed.alternateUrl(), "text/html");
        xml.writeStartElement("summary");
        xml.writeAttribute("type", "text");
        xml.writeCharacters(requireXmlText(displayed.summary()));
        xml.writeEndElement();
        writeXhtmlContent(xml, displayed);
        xml.writeEndElement();
    }

    private static void writeXhtmlContent(XMLStreamWriter xml, DisplayedEntry entry)
            throws XMLStreamException {
        /*
         * Atom XHTML content requires exactly one XHTML div wrapper. The PNG
         * is a self-contained data URL, so a feed reader need not fetch a
         * second Moon Service route; all useful facts remain in text too.
         */
        xml.writeStartElement("content");
        xml.writeAttribute("type", "xhtml");
        xhtmlRoot(xml);
        xhtmlStart(xml, "p");
        xml.writeEmptyElement("img");
        xml.writeAttribute("src", "data:image/png;base64," + Base64.getEncoder().encodeToString(
                AtomEntryPreviewRenderer.render(entry.preview())));
        xml.writeAttribute("width", Integer.toString(AtomEntryPreviewRenderer.WIDTH));
        xml.writeAttribute("height", Integer.toString(AtomEntryPreviewRenderer.HEIGHT));
        xml.writeAttribute("alt", requireXmlText(entry.previewAlt()));
        xml.writeEndElement();
        section(xml, "When", entry.when());
        section(xml, "Conditions", entry.conditions());
        beforeSection(xml, entry);
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void section(XMLStreamWriter xml, String label, List<String> lines)
            throws XMLStreamException {
        xhtmlStart(xml, "p");
        strong(xml, label);
        for (String line : lines) {
            xml.writeEmptyElement("br");
            xml.writeCharacters(requireXmlText(line));
        }
        xml.writeEndElement();
    }

    private static void beforeSection(XMLStreamWriter xml, DisplayedEntry entry)
            throws XMLStreamException {
        xhtmlStart(xml, "p");
        strong(xml, "Before you go");
        for (String line : entry.before()) {
            xml.writeEmptyElement("br");
            xml.writeCharacters(requireXmlText(line));
        }
        if (entry.eyeSafety()) {
            xml.writeEmptyElement("br");
            strong(xml, EYE_SAFETY_PREFIX);
            xml.writeCharacters(" Do not ");
            strong(xml, "ever");
            xml.writeCharacters(" " + EYE_SAFETY_REMAINDER);
        }
        xml.writeEndElement();
    }

    private static void xhtmlStart(XMLStreamWriter xml, String name) throws XMLStreamException {
        xml.writeStartElement(name);
    }

    private static void xhtmlRoot(XMLStreamWriter xml) throws XMLStreamException {
        xml.writeStartElement("div");
        xml.writeDefaultNamespace(XHTML_NAMESPACE);
    }

    private static void strong(XMLStreamWriter xml, String text) throws XMLStreamException {
        xhtmlStart(xml, "strong");
        xml.writeCharacters(requireXmlText(text));
        xml.writeEndElement();
    }

    private static void textElement(XMLStreamWriter xml, String name, String value)
            throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(requireXmlText(value));
        xml.writeEndElement();
    }

    private static void link(XMLStreamWriter xml, String rel, String href, String type)
            throws XMLStreamException {
        xml.writeEmptyElement("link");
        xml.writeAttribute("rel", requireXmlText(rel));
        xml.writeAttribute("href", requireXmlText(href));
        xml.writeAttribute("type", requireXmlText(type));
    }

    private static boolean needsEyeSafetyWarning(String phaseName, double illuminationPercent) {
        /* Product rule: warn for New Moon and for either crescent when illumination is at most 10%. */
        return "new_moon".equals(phaseName)
                || illuminationPercent <= EYE_SAFETY_MAX_ILLUMINATION_PERCENT
                && ("waxing_crescent".equals(phaseName) || "waning_crescent".equals(phaseName));
    }

    private static String eyeSafetyText() {
        return EYE_SAFETY_PREFIX + " Do not ever " + EYE_SAFETY_REMAINDER;
    }

    private static String requireXmlText(String value) {
        /*
         * StAX escapes markup characters but can still write code points that
         * XML 1.0 forbids. These ranges are the XML 1.0 Char production, so a
         * rejected provider/display value cannot make the whole feed malformed.
         */
        boolean valid = value.codePoints().allMatch(codePoint ->
                codePoint == 0x9
                        || codePoint == 0xA
                        || codePoint == 0xD
                        || codePoint >= 0x20 && codePoint <= 0xD7FF
                        || codePoint >= 0xE000 && codePoint <= 0xFFFD
                        || codePoint >= 0x10000 && codePoint <= 0x10FFFF);
        if (!valid) {
            throw new IllegalArgumentException(
                    "Atom text contains a character that XML 1.0 cannot represent.");
        }
        return value;
    }

    private static String atomId(String input) {
        /* Name-based UUIDs provide repeatable absolute Atom IRIs; they are identifiers, not secrets. */
        return "urn:uuid:" + UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String oneDecimal(double value) {
        // Force a dot decimal separator so rendered bytes do not depend on the server locale.
        return String.format(Locale.ENGLISH, "%.1f", value);
    }

    private static String plainLabel(String value) {
        // Response wire labels use lower_snake_case; feed prose uses lower-case words.
        return value.replace('_', ' ').toLowerCase(Locale.ENGLISH);
    }

    record FeedMetadata(String title, String id, String author, String selfUrl) {
    }

    record EntrySnapshot(DisplayedEntry displayed, String updated) {
    }

    /** Complete display snapshot whose record equality controls the entry's {@code updated} timestamp. */
    record DisplayedEntry(
            String id,
            String title,
            String summary,
            String alternateUrl,
            String suggestedAt,
            List<String> when,
            List<String> conditions,
            List<String> before,
            boolean eyeSafety,
            AtomEntryPreviewRenderer.Preview preview,
            String previewAlt
    ) {
        DisplayedEntry {
            // Defensive copies keep the snapshot immutable after it enters the cache.
            when = List.copyOf(when);
            conditions = List.copyOf(conditions);
            before = List.copyOf(before);
        }
    }
}
