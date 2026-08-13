package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AmbientLight;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomEntryPreviewRendererTest {
    private static final String CIVIL_TWILIGHT = "civil_twilight";

    @Test
    void rendersDeterministicRegularTexturedPngAtContractDimensions() throws Exception {
        AtomEntryPreviewRenderer.Preview preview = preview(61, CIVIL_TWILIGHT);

        byte[] first = AtomEntryPreviewRenderer.render(preview);
        byte[] second = AtomEntryPreviewRenderer.render(preview);

        assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
                Arrays.copyOf(first, 8));
        assertArrayEquals(first, second);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(first));
        assertEquals(640, image.getWidth());
        assertEquals(160, image.getHeight());
        assertFalse(image.getColorModel() instanceof IndexColorModel);
        Set<Integer> moonColors = new HashSet<>();
        for (int y = 50; y <= 108; y++) {
            for (int x = 49; x <= 107; x++) {
                if (Math.hypot(x - 78, y - 79) < 28) {
                    moonColors.add(image.getRGB(x, y));
                }
            }
        }
        assertTrue(moonColors.size() > 100, "The large Moon should retain texture detail.");
    }

    @Test
    void mapsExistingWeatherResultAndDrawsDistinctOverlays() {
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.CLEAR, "clear", 0);
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.CLEAR, "mostly_clear", 1);
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.CLOUDY, "partly_cloudy", 2);
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.CLOUDY, "mostly_cloudy", 0);
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.CLOUDY, "overcast", 0);
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.FOG, "poor_visibility", 0);
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.MIXED, "mixed", 0);
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.RAIN, "precipitation_risk", 61);
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.SNOW, "precipitation_risk", 71);
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.STORM, "precipitation_risk", 95);
        assertOverlay(AtomEntryPreviewRenderer.WeatherOverlay.MIXED, "precipitation_risk", 50);
        assertThrows(IllegalArgumentException.class,
                () -> AtomEntryPreviewRenderer.WeatherOverlay.from(weather("precipitation_risk", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> AtomEntryPreviewRenderer.WeatherOverlay.from(weather("unsupported", 0)));

        AtomEntryPreviewRenderer.Preview base = preview(0, CIVIL_TWILIGHT);
        Set<Integer> imageHashes = new HashSet<>();
        for (AtomEntryPreviewRenderer.WeatherOverlay overlay
                : AtomEntryPreviewRenderer.WeatherOverlay.values()) {
            imageHashes.add(Arrays.hashCode(AtomEntryPreviewRenderer.render(withWeather(base, overlay))));
        }
        assertEquals(AtomEntryPreviewRenderer.WeatherOverlay.values().length, imageHashes.size());
    }

    @Test
    void supportsAllSkiesAndRealLightSegments() throws Exception {
        Set<Integer> skyPixels = new HashSet<>();
        for (AmbientLight light : AmbientLight.values()) {
            AtomEntryPreviewRenderer.Preview preview = preview(0, light.wireValue());
            assertEquals(light, preview.sky());
            BufferedImage image = image(preview);
            skyPixels.add(image.getRGB(155, 5));
        }
        assertEquals(5, skyPixels.size());

        List<String> buckets = List.of(
                "daylight", "golden_hour", "civil_twilight", "nautical_twilight", "night",
                "nautical_twilight", "civil_twilight", "golden_hour", "daylight");
        AtomEntryPreviewRenderer.Preview mixed = AtomEntryPreviewRenderer.preview(
                opportunity(0, buckets, .1, .01, 241.1, 18.1), "Europe/Prague");
        List<AmbientLight> actualBuckets = mixed.curve().stream()
                .map(AtomEntryPreviewRenderer.CurvePoint::light)
                .toList();
        assertEquals(buckets.subList(0, buckets.size() - 1).stream()
                        .map(AtomEntryPreviewRendererTest::ambientLight).toList(),
                actualBuckets.subList(0, actualBuckets.size() - 1));
        assertNull(actualBuckets.getLast());

        BufferedImage nautical = image(preview(0, "nautical_twilight"));
        BufferedImage civil = image(preview(0, "civil_twilight"));
        assertTrue(colorDistance(nautical.getRGB(151, 20), nautical.getRGB(153, 20)) > 20);
        assertTrue(colorDistance(civil.getRGB(151, 20), civil.getRGB(153, 20)) < 8);
    }

    @Test
    void usesEachMarkerMoonAndKeepsOrdinaryMarkersAwayFromSuggestedMoon() {
        AtomEntryPreviewRenderer.Preview preview = preview(0, CIVIL_TWILIGHT);

        assertEquals("04:25", preview.suggestedTime());
        assertTrue(preview.markers().size() >= 3);
        assertTrue(preview.markers().stream()
                .map(AtomEntryPreviewRenderer.MoonMarker::moon)
                .distinct().count() > 1);
        for (AtomEntryPreviewRenderer.MoonMarker marker : preview.markers()) {
            assertTrue(Math.hypot(
                    marker.x() - preview.suggested().x(),
                    marker.y() - preview.suggested().y()) >= 28.0);
        }
    }

    @Test
    void usesMissingOrientationRulesAndDrawsNoMoonRing() {
        OpportunitySearchResponse.Opportunity opportunity = opportunity(
                0, repeated(CIVIL_TWILIGHT), .1, .01, null, null);
        AtomEntryPreviewRenderer.Preview preview = AtomEntryPreviewRenderer.preview(
                opportunity, "Europe/Prague");

        assertNull(preview.mainMoon().brightLimbDegrees());
        assertEquals(0, preview.mainMoon().northPoleDegrees());
        assertTrue(preview.markers().stream().allMatch(marker ->
                marker.moon().brightLimbDegrees() == null
                        && marker.moon().northPoleDegrees() == 0));

        BufferedImage disc = new BufferedImage(140, 140, BufferedImage.TYPE_INT_RGB);
        int background = new Color(12, 24, 36).getRGB();
        for (int y = 0; y < disc.getHeight(); y++) {
            for (int x = 0; x < disc.getWidth(); x++) {
                disc.setRGB(x, y, background);
            }
        }
        AtomMoonRenderer.draw(disc, 70, 70, 40,
                new AtomMoonRenderer.MoonStyle(180, null, 0));
        assertNotEquals(background, disc.getRGB(70, 70));
        assertEquals(background, disc.getRGB(112, 70));
    }

    @Test
    void comparesOnlyQuantizedVisiblePictureInputs() {
        AtomEntryPreviewRenderer.Preview first = AtomEntryPreviewRenderer.preview(
                opportunity(61, repeated(CIVIL_TWILIGHT), .1, .01, 241.1, 18.1),
                "Europe/Prague");
        AtomEntryPreviewRenderer.Preview visuallySame = AtomEntryPreviewRenderer.preview(
                opportunity(82, repeated(CIVIL_TWILIGHT), .4, .02, 241.4, 18.4),
                "Europe/Prague");
        AtomEntryPreviewRenderer.Preview changed = AtomEntryPreviewRenderer.preview(
                opportunity(0, repeated(CIVIL_TWILIGHT), .6, .02, 241.6, 18.6),
                "Europe/Prague");

        assertEquals(first, visuallySame);
        assertArrayEquals(AtomEntryPreviewRenderer.render(first),
                AtomEntryPreviewRenderer.render(visuallySame));
        assertNotEquals(first, changed);
    }

    @Test
    void drawsOnlyBitmapTimeBelowThePathAndHasNoBrightBucketStrip() throws Exception {
        AtomEntryPreviewRenderer.Preview first = preview(0, CIVIL_TWILIGHT);
        AtomEntryPreviewRenderer.Preview changedTime = new AtomEntryPreviewRenderer.Preview(
                first.mainMoon(), first.curve(), first.markers(), first.suggested(),
                first.sky(), first.weather(), "04:26");
        BufferedImage before = image(first);
        BufferedImage after = image(changedTime);
        int changed = 0;
        int minimumY = Integer.MAX_VALUE;
        int maximumY = Integer.MIN_VALUE;
        for (int y = 0; y < 160; y++) {
            for (int x = 0; x < 640; x++) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) {
                    changed++;
                    minimumY = Math.min(minimumY, y);
                    maximumY = Math.max(maximumY, y);
                }
            }
        }
        assertTrue(changed > 0);
        assertTrue(minimumY >= 136 && maximumY <= 145);
        assertNotEquals(before.getRGB(230, 124), before.getRGB(230, 127),
                "The path band should shade smoothly instead of ending in a bright strip.");
    }

    @Test
    void zeroPixelLightSegmentDoesNotCreateADoubledVerticalStrip() {
        AtomEntryPreviewRenderer.Preview base = preview(0, CIVIL_TWILIGHT);
        List<AtomEntryPreviewRenderer.CurvePoint> curve = new ArrayList<>(base.curve());
        curve.add(1, curve.getFirst());
        AtomEntryPreviewRenderer.Preview withZeroPixelSegment = new AtomEntryPreviewRenderer.Preview(
                base.mainMoon(), curve, base.markers(), base.suggested(),
                base.sky(), base.weather(), base.suggestedTime());

        assertArrayEquals(
                AtomEntryPreviewRenderer.render(base),
                AtomEntryPreviewRenderer.render(withZeroPixelSegment));
    }

    @Test
    void ignoresLastAndZeroPixelLightBucketsInEqualityAndPng() {
        List<String> baseBuckets = new ArrayList<>(repeated(CIVIL_TWILIGHT));
        List<String> changedLast = new ArrayList<>(baseBuckets);
        changedLast.set(changedLast.size() - 1, "night");
        AtomEntryPreviewRenderer.Preview lastBase = AtomEntryPreviewRenderer.preview(
                opportunity(0, baseBuckets, .1, .01, 241.1, 18.1), "Europe/Prague");
        AtomEntryPreviewRenderer.Preview lastChanged = AtomEntryPreviewRenderer.preview(
                opportunity(0, changedLast, .1, .01, 241.1, 18.1), "Europe/Prague");
        assertNull(lastBase.curve().getLast().light());
        assertEquals(lastBase, lastChanged);
        assertArrayEquals(
                AtomEntryPreviewRenderer.render(lastBase),
                AtomEntryPreviewRenderer.render(lastChanged));

        long[] minutes = {0, 101, 102, 300, 500, 600, 700, 800, 1_000};
        List<String> changedZeroPixel = new ArrayList<>(baseBuckets);
        changedZeroPixel.set(1, "night");
        AtomEntryPreviewRenderer.Preview zeroBase = AtomEntryPreviewRenderer.preview(
                opportunity(0, baseBuckets, .1, .01, 241.1, 18.1, minutes),
                "Europe/Prague");
        AtomEntryPreviewRenderer.Preview zeroChanged = AtomEntryPreviewRenderer.preview(
                opportunity(0, changedZeroPixel, .1, .01, 241.1, 18.1, minutes),
                "Europe/Prague");
        assertEquals(zeroBase.curve().get(1).x(), zeroBase.curve().get(2).x());
        assertNull(zeroBase.curve().get(1).light());
        assertEquals(zeroBase, zeroChanged);
        assertArrayEquals(
                AtomEntryPreviewRenderer.render(zeroBase),
                AtomEntryPreviewRenderer.render(zeroChanged));
    }

    @Test
    void ignoresBrightLimbWhenRoundedPhaseHasNoVisibleLimbDirection() {
        AtomMoonRenderer.MoonStyle fullLeft = AtomMoonRenderer.MoonStyle.from(
                new OpportunitySearchResponse.Moon(
                        10, 91, 100, 179.6, 20.0, 18.0, "full_moon"));
        AtomMoonRenderer.MoonStyle fullRight = AtomMoonRenderer.MoonStyle.from(
                new OpportunitySearchResponse.Moon(
                        10, 91, 100, 180.4, 240.0, 18.0, "full_moon"));
        assertNull(fullLeft.brightLimbDegrees());
        assertEquals(fullLeft, fullRight);

        OpportunitySearchResponse.MoonPathPoint newLeft = new OpportunitySearchResponse.MoonPathPoint(
                "2026-08-14T02:25:00Z", 10, 91, 359.6, 10.0, 18.0,
                -5, 72, CIVIL_TWILIGHT, "suggested");
        OpportunitySearchResponse.MoonPathPoint newRight = new OpportunitySearchResponse.MoonPathPoint(
                "2026-08-14T02:25:00Z", 10, 91, .4, 310.0, 18.0,
                -5, 72, CIVIL_TWILIGHT, "suggested");
        assertEquals(
                AtomMoonRenderer.MoonStyle.from(newLeft),
                AtomMoonRenderer.MoonStyle.from(newRight));

        AtomEntryPreviewRenderer.Preview base = preview(0, CIVIL_TWILIGHT);
        AtomEntryPreviewRenderer.Preview first = withMainMoon(base, fullLeft);
        AtomEntryPreviewRenderer.Preview second = withMainMoon(base, fullRight);
        assertEquals(first, second);
        assertArrayEquals(
                AtomEntryPreviewRenderer.render(first),
                AtomEntryPreviewRenderer.render(second));
    }

    @Test
    void cloudsAreObviousAndRainStrokesStayLow() throws Exception {
        AtomEntryPreviewRenderer.Preview base = preview(0, CIVIL_TWILIGHT);
        BufferedImage clear = image(withWeather(base, AtomEntryPreviewRenderer.WeatherOverlay.CLEAR));
        BufferedImage cloudy = image(withWeather(base, AtomEntryPreviewRenderer.WeatherOverlay.CLOUDY));
        int cloudChanges = differences(clear, cloudy, 0, 150, 30, 130)[0];
        assertTrue(cloudChanges > 3_000, "Overcast weather should visibly obscure the Moon scene.");

        BufferedImage rain = image(withWeather(base, AtomEntryPreviewRenderer.WeatherOverlay.RAIN));
        BufferedImage mixed = image(withWeather(base, AtomEntryPreviewRenderer.WeatherOverlay.MIXED));
        int[] rainDifferences = differences(rain, mixed, 0, 150, 0, 159);
        assertTrue(rainDifferences[0] > 0);
        assertTrue(rainDifferences[1] >= 96 && rainDifferences[2] <= 126,
                "Rain strokes must stay in the lowest third of the large Moon.");
    }

    private static int[] differences(
            BufferedImage first, BufferedImage second,
            int left, int right, int top, int bottom
    ) {
        int count = 0;
        int minimumY = Integer.MAX_VALUE;
        int maximumY = Integer.MIN_VALUE;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) {
                    count++;
                    minimumY = Math.min(minimumY, y);
                    maximumY = Math.max(maximumY, y);
                }
            }
        }
        return new int[]{count, minimumY, maximumY};
    }

    private static int colorDistance(int first, int second) {
        Color left = new Color(first);
        Color right = new Color(second);
        return Math.abs(left.getRed() - right.getRed())
                + Math.abs(left.getGreen() - right.getGreen())
                + Math.abs(left.getBlue() - right.getBlue());
    }

    private static BufferedImage image(AtomEntryPreviewRenderer.Preview preview) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(AtomEntryPreviewRenderer.render(preview)));
    }

    private static AtomEntryPreviewRenderer.Preview withWeather(
            AtomEntryPreviewRenderer.Preview preview,
            AtomEntryPreviewRenderer.WeatherOverlay weather
    ) {
        return new AtomEntryPreviewRenderer.Preview(
                preview.mainMoon(), preview.curve(), preview.markers(), preview.suggested(),
                preview.sky(), weather, preview.suggestedTime());
    }

    private static AtomEntryPreviewRenderer.Preview withMainMoon(
            AtomEntryPreviewRenderer.Preview preview,
            AtomMoonRenderer.MoonStyle mainMoon
    ) {
        return new AtomEntryPreviewRenderer.Preview(
                mainMoon, preview.curve(), preview.markers(), preview.suggested(),
                preview.sky(), preview.weather(), preview.suggestedTime());
    }

    private static AtomEntryPreviewRenderer.Preview preview(int weatherCode, String bucket) {
        return AtomEntryPreviewRenderer.preview(
                opportunity(weatherCode, repeated(bucket), .1, .01, 241.1, 18.1),
                "Europe/Prague");
    }

    private static List<String> repeated(String value) {
        return java.util.Collections.nCopies(9, value);
    }

    private static AmbientLight ambientLight(String wireValue) {
        return Arrays.stream(AmbientLight.values())
                .filter(light -> light.wireValue().equals(wireValue))
                .findFirst()
                .orElseThrow();
    }

    private static void assertOverlay(
            AtomEntryPreviewRenderer.WeatherOverlay expected,
            String segmentKind,
            int... codes
    ) {
        for (int code : codes) {
            assertEquals(expected, AtomEntryPreviewRenderer.WeatherOverlay.from(weather(segmentKind, code)),
                    () -> "Unexpected overlay for " + segmentKind + " and WMO code " + code);
        }
    }

    private static OpportunitySearchResponse.Weather weather(String segmentKind, int weatherCode) {
        return new OpportunitySearchResponse.Weather(
                "hourly", segmentKind, 20, 30, 10, 10, 10,
                20, .2, 10_000, weatherCode, "forecast");
    }

    private static OpportunitySearchResponse.Opportunity opportunity(
            int weatherCode,
            List<String> buckets,
            double angleNudge,
            double altitudeNudge,
            Double brightLimb,
            Double northPole
    ) {
        return opportunity(
                weatherCode, buckets, angleNudge, altitudeNudge, brightLimb, northPole,
                new long[]{0, 5, 10, 15, 20, 25, 30, 35, 40});
    }

    private static OpportunitySearchResponse.Opportunity opportunity(
            int weatherCode,
            List<String> buckets,
            double angleNudge,
            double altitudeNudge,
            Double brightLimb,
            Double northPole,
            long[] minuteOffsets
    ) {
        Instant start = Instant.parse("2026-08-14T02:05:00Z");
        double[] altitudes = {2, 4, 6, 8, 10, 9, 7, 5, 3};
        assertEquals(altitudes.length, minuteOffsets.length);
        List<OpportunitySearchResponse.MoonPathPoint> points = new ArrayList<>();
        for (int index = 0; index < altitudes.length; index++) {
            String role = index == 0 ? "start" : index == 4 ? "suggested"
                    : index == altitudes.length - 1 ? "end" : "sample";
            points.add(new OpportunitySearchResponse.MoonPathPoint(
                    start.plus(minuteOffsets[index], ChronoUnit.MINUTES).toString(),
                    altitudes[index] + altitudeNudge, 91.0,
                    60.0 + index * 3.0 + angleNudge,
                    brightLimb == null ? null : brightLimb + index,
                    northPole == null ? null : northPole + index,
                    -5.0, 72.0, buckets.get(index), role));
        }
        OpportunitySearchResponse.MoonPathPoint suggested = points.get(4);
        return new OpportunitySearchResponse.Opportunity(
                "id", "moonrise_low", null,
                points.getFirst().at(), suggested.at(), points.getLast().at(),
                "Europe/Prague", 83, "high", null, null,
                new OpportunitySearchResponse.Moon(
                        10.0, 91.0, 7.0, 72.0 + angleNudge,
                        brightLimb, northPole, "waxing_crescent"),
                new OpportunitySearchResponse.MoonPath(
                        points.getFirst(), suggested, points.getLast(), points),
                new OpportunitySearchResponse.Sun(-5.0, 72.0, buckets.get(4)),
                weather(weatherCode >= 50 ? "precipitation_risk" : "clear", weatherCode),
                null, "reason", Map.of());
    }
}
