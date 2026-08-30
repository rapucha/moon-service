package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;
import dev.moonservice.scoringprototype.input.OpportunityPreferences.AmbientLight;
import dev.moonservice.scoringprototype.scoring.ScoringModel.WeatherCodeKind;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import static dev.moonservice.scoringprototype.scoring.ScoringModel.weatherCodeKind;

/**
 * Builds the Atom preview PNG that {@link AtomFeedDocumentRenderer} embeds in
 * each rich Atom entry. This class owns the overall scene, Moon-path geometry,
 * ambient-light bands, marker placement, and layer order. It delegates Moon
 * discs to {@link AtomMoonRenderer} and weather artwork to
 * {@link AtomWeatherRenderer}; no other production class calls those helpers.
 *
 * <p>{@link Preview} is also the value model for the visible picture. Its
 * equality is used indirectly by {@link AtomFeedService} to preserve an
 * entry's {@code updated} value when a refresh looks the same. The exact
 * serialized XML bytes, including the PNG, determine the feed ETag.
 */
final class AtomEntryPreviewRenderer {
    static final int WIDTH = 640;
    static final int HEIGHT = 160;

    private static final int MIN_PATH_SAMPLES = 2;
    private static final int PLOT_LEFT = 170;
    private static final int PLOT_RIGHT = 621;
    private static final int PLOT_TOP = 18;
    private static final int PLOT_BOTTOM = 128;
    private static final double ALTITUDE_PADDING_BELOW_DEGREES = 4.0;
    private static final double ALTITUDE_PADDING_ABOVE_DEGREES = 5.0;
    private static final int MAIN_MOON_CENTER_X = 78;
    private static final int MAIN_MOON_CENTER_Y = 79;
    private static final int MAIN_MOON_RADIUS = 55;
    private static final int ORDINARY_MARKER_RADIUS = 8;
    private static final int SUGGESTED_MARKER_RADIUS = 15;
    private static final double MARKER_GAP_PIXELS = 5.0;
    private static final double MINIMUM_MARKER_CENTER_DISTANCE =
            ORDINARY_MARKER_RADIUS + SUGGESTED_MARKER_RADIUS + MARKER_GAP_PIXELS;
    private static final float[] ORDINARY_MARKER_FRACTIONS = {0, .25f, .5f, .75f, 1};
    private static final int LIGHT_BAND_TOP_ALPHA = 72;
    private static final int LIGHT_BAND_BOTTOM_ALPHA = 158;
    private static final float PATH_STROKE_WIDTH = 3;
    private static final float BASELINE_STROKE_WIDTH = 1;
    private static final int GUIDE_START_CLEARANCE = SUGGESTED_MARKER_RADIUS + 2;
    private static final int GUIDE_BASELINE_EXTENSION = 5;
    private static final int GUIDE_DASH_STEP = 5;
    private static final int GUIDE_DASH_LENGTH = 2;
    private static final int TIME_TOP_OFFSET = 8;
    private static final int TIME_GLYPH_SCALE = 2;
    private static final int TIME_GLYPH_ADVANCE_COLUMNS = 4;
    private static final int TIME_TRAILING_GAP_COLUMNS = 1;
    private static final int SMALL_STAR_SIZE = 1;
    private static final int LARGE_STAR_SIZE = 2;
    private static final int LARGE_STAR_CADENCE = 3;
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Color STAR_COLOR = new Color(220, 234, 240, 128);
    private static final Color PATH_COLOR = new Color(185, 231, 240, 194);
    private static final Color BASELINE_COLOR = new Color(185, 207, 217, 61);
    private static final Color FOREGROUND_COLOR = new Color(4, 12, 21, 189);
    private static final Color GUIDE_COLOR = new Color(236, 244, 246, 107);
    private static final Color TIME_COLOR = new Color(241, 246, 247, 235);
    private static final Color FRAME_COLOR = new Color(179, 205, 215, 87);
    /* Fixed art coordinates avoid random layout and keep scene geometry repeatable. */
    private static final int[][] STARS = {
            {151, 20}, {212, 11}, {265, 29}, {335, 15}, {401, 25}, {468, 11},
            {540, 28}, {605, 15}, {132, 62}, {236, 70}, {380, 53}, {512, 67}, {626, 57}
    };
    private static final int[] FOREGROUND_X =
            {0, 0, 52, 94, 138, 186, 236, 290, 344, 403, 465, 523, 580, 640, 640};
    private static final int[] FOREGROUND_Y =
            {160, 144, 136, 143, 132, 146, 139, 148, 138, 146, 137, 145, 136, 144, 160};

    /* Atom-specific colors for the existing production ambient-light vocabulary. */
    private static final LightPalette DAYLIGHT_PALETTE = new LightPalette(
            new Color(120, 167, 199), new Color(212, 199, 157), new Color(255, 232, 151));
    private static final LightPalette GOLDEN_HOUR_PALETTE = new LightPalette(
            new Color(79, 113, 137), new Color(208, 141, 92), new Color(247, 181, 92));
    private static final LightPalette CIVIL_TWILIGHT_PALETTE = new LightPalette(
            new Color(43, 71, 104), new Color(91, 93, 117), new Color(112, 174, 207));
    private static final LightPalette NAUTICAL_TWILIGHT_PALETTE = new LightPalette(
            new Color(26, 51, 82), new Color(22, 41, 67), new Color(69, 91, 145));
    private static final LightPalette NIGHT_PALETTE = new LightPalette(
            new Color(22, 42, 66), new Color(7, 17, 30), new Color(25, 37, 66));

    private AtomEntryPreviewRenderer() {
    }

    static Preview preview(
            OpportunitySearchResponse.Opportunity opportunity,
            String timezone
    ) {
        Objects.requireNonNull(opportunity, "opportunity");
        OpportunitySearchResponse.MoonPath moonPath = Objects.requireNonNull(
                opportunity.moonPath(), "opportunity.moonPath");
        List<OpportunitySearchResponse.MoonPathPoint> samples = new ArrayList<>(
                Objects.requireNonNull(moonPath.samples(), "moonPath.samples"));
        // Provider samples are sorted here because x position is elapsed time.
        samples.sort(Comparator.comparing(point -> Instant.parse(point.at())));
        if (samples.size() < MIN_PATH_SAMPLES) {
            throw new IllegalArgumentException("Moon path must contain at least two samples.");
        }
        Instant first = Instant.parse(samples.getFirst().at());
        Instant last = Instant.parse(samples.getLast().at());
        if (!last.isAfter(first)) {
            throw new IllegalArgumentException("Moon path samples must span time.");
        }
        /*
         * Expand the dynamic altitude domain so curve centers and Moon markers
         * have room at both plot edges. The extra upper padding leaves more
         * headroom and keeps a low, flat path slightly below the image center.
         */
        double low = samples.stream().mapToDouble(OpportunitySearchResponse.MoonPathPoint::altitudeDegrees)
                .min().orElseThrow() - ALTITUDE_PADDING_BELOW_DEGREES;
        double high = samples.stream().mapToDouble(OpportunitySearchResponse.MoonPathPoint::altitudeDegrees)
                .max().orElseThrow() + ALTITUDE_PADDING_ABOVE_DEGREES;
        List<CurvePoint> curve = samples.stream()
                .map(point -> curvePoint(point, first, last, low, high))
                .toList();
        CurvePoint suggestedPoint = curvePoint(moonPath.suggested(), first, last, low, high);
        MoonMarker suggested = new MoonMarker(
                suggestedPoint.x(), suggestedPoint.y(), AtomMoonRenderer.MoonStyle.from(moonPath.suggested()));
        List<MoonMarker> markers = ordinaryMarkers(samples, curve, suggested);
        String time = LOCAL_TIME.format(Instant.parse(opportunity.suggestedAt())
                .atZone(ZoneId.of(timezone)));
        return new Preview(
                AtomMoonRenderer.MoonStyle.from(opportunity.moon()),
                curve,
                markers,
                suggested,
                ambientLight(moonPath.suggested().lightBucket()),
                WeatherOverlay.from(opportunity.weather()),
                time);
    }

    static byte[] render(Preview preview) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Layer order is intentional: overlays affect the scene, not the path markers or time label.
        drawSky(graphics, preview.sky());
        if (preview.sky() == AmbientLight.NAUTICAL_TWILIGHT || preview.sky() == AmbientLight.NIGHT) {
            drawStars(graphics);
        }
        AtomMoonRenderer.draw(image, MAIN_MOON_CENTER_X, MAIN_MOON_CENTER_Y,
                MAIN_MOON_RADIUS, preview.mainMoon());
        AtomWeatherRenderer.draw(graphics, preview.weather());
        drawLightBands(graphics, preview.curve());
        drawPath(graphics, preview.curve());
        drawForeground(graphics);
        for (MoonMarker marker : preview.markers()) {
            AtomMoonRenderer.draw(image, marker.x(), marker.y(), ORDINARY_MARKER_RADIUS, marker.moon());
        }
        drawSuggestedGuide(graphics, preview.suggested(), preview.suggestedTime());
        AtomMoonRenderer.draw(image, preview.suggested().x(), preview.suggested().y(),
                SUGGESTED_MARKER_RADIUS, preview.suggested().moon());
        graphics.setColor(FRAME_COLOR);
        graphics.drawRect(0, 0, WIDTH - 1, HEIGHT - 1);
        graphics.dispose();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer is not available.");
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not render Atom preview PNG.", ex);
        }
    }

    private static CurvePoint curvePoint(
            OpportunitySearchResponse.MoonPathPoint point,
            Instant first,
            Instant last,
            double low,
            double high
    ) {
        /*
         * Map elapsed time left-to-right and altitude bottom-to-top. Clamping
         * also keeps the separately supplied suggested point inside the plot
         * if it falls just outside the sampled range.
         */
        double time = (double) (Instant.parse(point.at()).toEpochMilli() - first.toEpochMilli())
                / (last.toEpochMilli() - first.toEpochMilli());
        double altitude = (point.altitudeDegrees() - low) / (high - low);
        return new CurvePoint(
                (int) Math.round(PLOT_LEFT + clamp(time) * (PLOT_RIGHT - PLOT_LEFT)),
                (int) Math.round(PLOT_BOTTOM - clamp(altitude) * (PLOT_BOTTOM - PLOT_TOP)),
                ambientLight(point.lightBucket()));
    }

    private static List<CurvePoint> canonicalCurve(List<CurvePoint> points) {
        /*
         * A bucket colors the segment to its following point. The last bucket,
         * and a bucket whose next point rounds to the same x pixel, paint
         * nothing. Removing those invisible values makes Preview equality
         * match the pixels that control Atom updated timestamps and ETags.
         */
        List<CurvePoint> canonical = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            CurvePoint point = points.get(index);
            AmbientLight visibleLight = index < points.size() - 1
                    && points.get(index + 1).x() > point.x()
                    ? point.light()
                    : null;
            canonical.add(new CurvePoint(point.x(), point.y(), visibleLight));
        }
        return List.copyOf(canonical);
    }

    private static List<MoonMarker> ordinaryMarkers(
            List<OpportunitySearchResponse.MoonPathPoint> samples,
            List<CurvePoint> curve,
            MoonMarker suggested
    ) {
        int last = samples.size() - 1;
        /*
         * Choose start, quarter, midpoint, three-quarter, and end samples.
         * Rounded indexes are de-duplicated for short paths. A marker is then
         * omitted when its disc would touch the larger suggested Moon.
         */
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        for (float fraction : ORDINARY_MARKER_FRACTIONS) {
            indexes.add(Math.round(last * fraction));
        }
        List<MoonMarker> markers = new ArrayList<>();
        for (int index : indexes) {
            CurvePoint point = curve.get(index);
            if (Math.hypot(point.x() - suggested.x(), point.y() - suggested.y())
                    < MINIMUM_MARKER_CENTER_DISTANCE) {
                continue;
            }
            markers.add(new MoonMarker(
                    point.x(), point.y(), AtomMoonRenderer.MoonStyle.from(samples.get(index))));
        }
        return List.copyOf(markers);
    }

    private static void drawSky(Graphics2D graphics, AmbientLight light) {
        LightPalette palette = lightPalette(light);
        graphics.setPaint(new GradientPaint(
                0, 0, palette.skyTop(), WIDTH, HEIGHT, palette.skyBottom()));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
    }

    private static void drawStars(Graphics2D graphics) {
        graphics.setColor(STAR_COLOR);
        for (int index = 0; index < STARS.length; index++) {
            int size = index % LARGE_STAR_CADENCE == 0 ? LARGE_STAR_SIZE : SMALL_STAR_SIZE;
            graphics.fillOval(STARS[index][0], STARS[index][1], size, size);
        }
    }

    private static void drawLightBands(Graphics2D graphics, List<CurvePoint> points) {
        /*
         * Each real sample owns the horizontal interval to the next sample.
         * A stronger lower alpha anchors the band near the horizon without the
         * bright separator created by overlapping one-pixel intervals.
         */
        for (int index = 0; index < points.size() - 1; index++) {
            CurvePoint point = points.get(index);
            CurvePoint next = points.get(index + 1);
            int width = next.x() - point.x();
            if (width <= 0) {
                continue;
            }
            Color color = lightPalette(point.light()).band();
            graphics.setPaint(new GradientPaint(
                    0, PLOT_TOP, withAlpha(color, LIGHT_BAND_TOP_ALPHA),
                    0, PLOT_BOTTOM, withAlpha(color, LIGHT_BAND_BOTTOM_ALPHA)));
            graphics.fillRect(point.x(), PLOT_TOP, width, PLOT_BOTTOM - PLOT_TOP);
        }
    }

    private static void drawPath(Graphics2D graphics, List<CurvePoint> points) {
        graphics.setColor(PATH_COLOR);
        graphics.setStroke(new BasicStroke(
                PATH_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int index = 1; index < points.size(); index++) {
            CurvePoint before = points.get(index - 1);
            CurvePoint after = points.get(index);
            graphics.drawLine(before.x(), before.y(), after.x(), after.y());
        }
        graphics.setStroke(new BasicStroke(BASELINE_STROKE_WIDTH));
        graphics.setColor(BASELINE_COLOR);
        graphics.drawLine(PLOT_LEFT, PLOT_BOTTOM, PLOT_RIGHT, PLOT_BOTTOM);
    }

    private static void drawForeground(Graphics2D graphics) {
        Polygon foreground = new Polygon(FOREGROUND_X, FOREGROUND_Y, FOREGROUND_X.length);
        graphics.setColor(FOREGROUND_COLOR);
        graphics.fillPolygon(foreground);
    }

    private static void drawSuggestedGuide(Graphics2D graphics, MoonMarker marker, String time) {
        /* Start below the Moon disc, stop just below the baseline, then put the local time underneath. */
        graphics.setColor(GUIDE_COLOR);
        int guideBottom = PLOT_BOTTOM + GUIDE_BASELINE_EXTENSION;
        for (int y = marker.y() + GUIDE_START_CLEARANCE; y < guideBottom; y += GUIDE_DASH_STEP) {
            graphics.drawLine(marker.x(), y, marker.x(), Math.min(y + GUIDE_DASH_LENGTH, guideBottom));
        }
        drawBitmapTime(graphics, time, marker.x(), PLOT_BOTTOM + TIME_TOP_OFFSET);
    }

    private static void drawBitmapTime(Graphics2D graphics, String time, int centerX, int top) {
        /*
         * A fixed 3-by-5 bitmap font avoids platform font differences in PNG
         * bytes. Four source columns per glyph include its one-column advance;
         * the final trailing gap is removed before centering.
         */
        int width = (time.length() * TIME_GLYPH_ADVANCE_COLUMNS - TIME_TRAILING_GAP_COLUMNS)
                * TIME_GLYPH_SCALE;
        int left = centerX - width / 2;
        graphics.setColor(TIME_COLOR);
        for (int index = 0; index < time.length(); index++) {
            String[] glyph = glyph(time.charAt(index));
            for (int row = 0; row < glyph.length; row++) {
                for (int column = 0; column < glyph[row].length(); column++) {
                    if (glyph[row].charAt(column) == '#') {
                        graphics.fillRect(
                                left + (index * TIME_GLYPH_ADVANCE_COLUMNS + column)
                                        * TIME_GLYPH_SCALE,
                                top + row * TIME_GLYPH_SCALE,
                                TIME_GLYPH_SCALE,
                                TIME_GLYPH_SCALE);
                    }
                }
            }
        }
    }

    private static String[] glyph(char value) {
        // Each row is a three-pixel bitmap; '.' is transparent and '#' is painted.
        return switch (value) {
            case '0' -> new String[]{"###", "#.#", "#.#", "#.#", "###"};
            case '1' -> new String[]{".#.", "##.", ".#.", ".#.", "###"};
            case '2' -> new String[]{"###", "..#", "###", "#..", "###"};
            case '3' -> new String[]{"###", "..#", ".##", "..#", "###"};
            case '4' -> new String[]{"#.#", "#.#", "###", "..#", "..#"};
            case '5' -> new String[]{"###", "#..", "###", "..#", "###"};
            case '6' -> new String[]{"###", "#..", "###", "#.#", "###"};
            case '7' -> new String[]{"###", "..#", "..#", ".#.", ".#."};
            case '8' -> new String[]{"###", "#.#", "###", "#.#", "###"};
            case '9' -> new String[]{"###", "#.#", "###", "..#", "###"};
            case ':' -> new String[]{"...", ".#.", "...", ".#.", "..."};
            default -> throw new IllegalArgumentException("Unsupported time glyph: " + value);
        };
    }

    private static Color withAlpha(Color color, int alpha) {
        // Preserve the palette RGB values while choosing per-layer transparency.
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static double clamp(double value) {
        // Normalized plot coordinates must stay in the closed unit interval.
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static AmbientLight ambientLight(String wireValue) {
        /* The opportunity response carries the shared production vocabulary as wire strings. */
        for (AmbientLight light : AmbientLight.values()) {
            if (light.wireValue().equals(wireValue)) {
                return light;
            }
        }
        throw new IllegalArgumentException("Unknown ambient light: " + wireValue);
    }

    private static LightPalette lightPalette(AmbientLight light) {
        // Only presentation colors stay local; the five light values come from AmbientLight.
        return switch (light) {
            case DAYLIGHT -> DAYLIGHT_PALETTE;
            case GOLDEN_HOUR -> GOLDEN_HOUR_PALETTE;
            case CIVIL_TWILIGHT -> CIVIL_TWILIGHT_PALETTE;
            case NAUTICAL_TWILIGHT -> NAUTICAL_TWILIGHT_PALETTE;
            case NIGHT -> NIGHT_PALETTE;
        };
    }

    record Preview(
            AtomMoonRenderer.MoonStyle mainMoon,
            List<CurvePoint> curve,
            List<MoonMarker> markers,
            MoonMarker suggested,
            AmbientLight sky,
            WeatherOverlay weather,
            String suggestedTime
    ) {
        Preview {
            /* Canonical copies make equality describe only stable, visible rendering inputs. */
            curve = canonicalCurve(curve);
            markers = List.copyOf(markers);
        }
    }

    record CurvePoint(int x, int y, AmbientLight light) {
    }

    record MoonMarker(int x, int y, AtomMoonRenderer.MoonStyle moon) {
    }

    private record LightPalette(Color skyTop, Color skyBottom, Color band) {
    }

    /**
     * Atom-only artwork selected from the opportunity's existing weather result.
     * The WMO code refines precipitation; it does not classify the broad condition again.
     */
    enum WeatherOverlay {
        CLEAR, CLOUDY, FOG, RAIN, SNOW, STORM, MIXED;

        static WeatherOverlay from(OpportunitySearchResponse.Weather weather) {
            return switch (weather.segmentKind()) {
                case "clear", "mostly_clear" -> CLEAR;
                case "partly_cloudy", "mostly_cloudy", "overcast" -> CLOUDY;
                case "poor_visibility" -> FOG;
                case "precipitation_risk" -> precipitation(weather.weatherCode());
                case "unknown_conditions" -> MIXED;
                default -> throw new IllegalArgumentException(
                        "Unknown weather segment kind: " + weather.segmentKind());
            };
        }

        private static WeatherOverlay precipitation(int code) {
            WeatherCodeKind kind = weatherCodeKind(code);
            return switch (kind) {
                case RAIN -> RAIN;
                case SNOW -> SNOW;
                case STORM -> STORM;
                case OTHER_PRECIPITATION -> MIXED;
                default -> throw new IllegalArgumentException(
                        "Weather segment precipitation_risk requires a precipitation WMO code, but code "
                                + code + " is " + kind);
            };
        }
    }
}
