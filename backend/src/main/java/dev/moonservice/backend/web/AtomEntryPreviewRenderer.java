package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;

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

final class AtomEntryPreviewRenderer {
    static final int WIDTH = 640;
    static final int HEIGHT = 160;

    private static final int PLOT_LEFT = 170;
    private static final int PLOT_RIGHT = 621;
    private static final int PLOT_TOP = 18;
    private static final int PLOT_BOTTOM = 128;
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final int[][] STARS = {
            {151, 20}, {212, 11}, {265, 29}, {335, 15}, {401, 25}, {468, 11},
            {540, 28}, {605, 15}, {132, 62}, {236, 70}, {380, 53}, {512, 67}, {626, 57}
    };

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
        samples.sort(Comparator.comparing(point -> Instant.parse(point.at())));
        if (samples.size() < 2) {
            throw new IllegalArgumentException("Moon path must contain at least two samples.");
        }
        Instant first = Instant.parse(samples.getFirst().at());
        Instant last = Instant.parse(samples.getLast().at());
        if (!last.isAfter(first)) {
            throw new IllegalArgumentException("Moon path samples must span time.");
        }
        double low = samples.stream().mapToDouble(OpportunitySearchResponse.MoonPathPoint::altitudeDegrees)
                .min().orElseThrow() - 4.0;
        double high = samples.stream().mapToDouble(OpportunitySearchResponse.MoonPathPoint::altitudeDegrees)
                .max().orElseThrow() + 5.0;
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
                LightBucket.from(moonPath.suggested().lightBucket()),
                WeatherCategory.fromCode(opportunity.weather().weatherCode()),
                time);
    }

    static byte[] render(Preview preview) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawSky(graphics, preview.sky());
        if (preview.sky() == LightBucket.NAUTICAL_TWILIGHT || preview.sky() == LightBucket.NIGHT) {
            drawStars(graphics);
        }
        AtomMoonRenderer.draw(image, 78, 79, 55, preview.mainMoon());
        AtomWeatherRenderer.draw(graphics, preview.weather());
        drawLightBands(graphics, preview.curve());
        drawPath(graphics, preview.curve());
        drawForeground(graphics);
        for (MoonMarker marker : preview.markers()) {
            AtomMoonRenderer.draw(image, marker.x(), marker.y(), 8, marker.moon());
        }
        drawSuggestedGuide(graphics, preview.suggested(), preview.suggestedTime());
        AtomMoonRenderer.draw(image, preview.suggested().x(), preview.suggested().y(),
                15, preview.suggested().moon());
        graphics.setColor(new Color(179, 205, 215, 87));
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
        double time = (double) (Instant.parse(point.at()).toEpochMilli() - first.toEpochMilli())
                / (last.toEpochMilli() - first.toEpochMilli());
        double altitude = (point.altitudeDegrees() - low) / (high - low);
        return new CurvePoint(
                (int) Math.round(PLOT_LEFT + clamp(time) * (PLOT_RIGHT - PLOT_LEFT)),
                (int) Math.round(PLOT_BOTTOM - clamp(altitude) * (PLOT_BOTTOM - PLOT_TOP)),
                LightBucket.from(point.lightBucket()));
    }

    private static List<CurvePoint> canonicalCurve(List<CurvePoint> points) {
        List<CurvePoint> canonical = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            CurvePoint point = points.get(index);
            LightBucket visibleLight = index < points.size() - 1
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
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>(List.of(
                0, Math.round(last * .25f), Math.round(last * .5f), Math.round(last * .75f), last));
        List<MoonMarker> markers = new ArrayList<>();
        for (int index : indexes) {
            CurvePoint point = curve.get(index);
            if (Math.hypot(point.x() - suggested.x(), point.y() - suggested.y()) < 28.0) {
                continue;
            }
            markers.add(new MoonMarker(
                    point.x(), point.y(), AtomMoonRenderer.MoonStyle.from(samples.get(index))));
        }
        return List.copyOf(markers);
    }

    private static void drawSky(Graphics2D graphics, LightBucket bucket) {
        Color[] colors = bucket.sky();
        graphics.setPaint(new GradientPaint(0, 0, colors[0], WIDTH, HEIGHT, colors[1]));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
    }

    private static void drawStars(Graphics2D graphics) {
        graphics.setColor(new Color(220, 234, 240, 128));
        for (int index = 0; index < STARS.length; index++) {
            int size = index % 3 == 0 ? 2 : 1;
            graphics.fillOval(STARS[index][0], STARS[index][1], size, size);
        }
    }

    private static void drawLightBands(Graphics2D graphics, List<CurvePoint> points) {
        for (int index = 0; index < points.size() - 1; index++) {
            CurvePoint point = points.get(index);
            CurvePoint next = points.get(index + 1);
            int width = next.x() - point.x();
            if (width <= 0) {
                continue;
            }
            Color color = point.light().band();
            graphics.setPaint(new GradientPaint(
                    0, PLOT_TOP, withAlpha(color, 72), 0, PLOT_BOTTOM, withAlpha(color, 158)));
            graphics.fillRect(point.x(), PLOT_TOP, width, PLOT_BOTTOM - PLOT_TOP);
        }
    }

    private static void drawPath(Graphics2D graphics, List<CurvePoint> points) {
        graphics.setColor(new Color(185, 231, 240, 194));
        graphics.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int index = 1; index < points.size(); index++) {
            CurvePoint before = points.get(index - 1);
            CurvePoint after = points.get(index);
            graphics.drawLine(before.x(), before.y(), after.x(), after.y());
        }
        graphics.setStroke(new BasicStroke(1));
        graphics.setColor(new Color(185, 207, 217, 61));
        graphics.drawLine(PLOT_LEFT, PLOT_BOTTOM, PLOT_RIGHT, PLOT_BOTTOM);
    }

    private static void drawForeground(Graphics2D graphics) {
        Polygon foreground = new Polygon(
                new int[]{0, 0, 52, 94, 138, 186, 236, 290, 344, 403, 465, 523, 580, 640, 640},
                new int[]{160, 144, 136, 143, 132, 146, 139, 148, 138, 146, 137, 145, 136, 144, 160},
                15);
        graphics.setColor(new Color(4, 12, 21, 189));
        graphics.fillPolygon(foreground);
    }

    private static void drawSuggestedGuide(Graphics2D graphics, MoonMarker marker, String time) {
        graphics.setColor(new Color(236, 244, 246, 107));
        for (int y = marker.y() + 17; y < PLOT_BOTTOM + 5; y += 5) {
            graphics.drawLine(marker.x(), y, marker.x(), Math.min(y + 2, PLOT_BOTTOM + 5));
        }
        drawBitmapTime(graphics, time, marker.x(), PLOT_BOTTOM + 8);
    }

    private static void drawBitmapTime(Graphics2D graphics, String time, int centerX, int top) {
        int scale = 2;
        int width = time.length() * 4 * scale - scale;
        int left = centerX - width / 2;
        graphics.setColor(new Color(241, 246, 247, 235));
        for (int index = 0; index < time.length(); index++) {
            String[] glyph = glyph(time.charAt(index));
            for (int row = 0; row < glyph.length; row++) {
                for (int column = 0; column < glyph[row].length(); column++) {
                    if (glyph[row].charAt(column) == '#') {
                        graphics.fillRect(left + (index * 4 + column) * scale,
                                top + row * scale, scale, scale);
                    }
                }
            }
        }
    }

    private static String[] glyph(char value) {
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
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    record Preview(
            AtomMoonRenderer.MoonStyle mainMoon,
            List<CurvePoint> curve,
            List<MoonMarker> markers,
            MoonMarker suggested,
            LightBucket sky,
            WeatherCategory weather,
            String suggestedTime
    ) {
        Preview {
            curve = canonicalCurve(curve);
            markers = List.copyOf(markers);
        }
    }

    record CurvePoint(int x, int y, LightBucket light) {
    }

    record MoonMarker(int x, int y, AtomMoonRenderer.MoonStyle moon) {
    }

    enum LightBucket {
        DAYLIGHT(new Color(120, 167, 199), new Color(212, 199, 157), new Color(255, 232, 151)),
        GOLDEN_HOUR(new Color(79, 113, 137), new Color(208, 141, 92), new Color(247, 181, 92)),
        CIVIL_TWILIGHT(new Color(43, 71, 104), new Color(91, 93, 117), new Color(112, 174, 207)),
        NAUTICAL_TWILIGHT(new Color(26, 51, 82), new Color(22, 41, 67), new Color(69, 91, 145)),
        NIGHT(new Color(22, 42, 66), new Color(7, 17, 30), new Color(25, 37, 66));

        private final Color skyTop;
        private final Color skyBottom;
        private final Color band;

        LightBucket(Color skyTop, Color skyBottom, Color band) {
            this.skyTop = skyTop;
            this.skyBottom = skyBottom;
            this.band = band;
        }

        Color[] sky() {
            return new Color[]{skyTop, skyBottom};
        }

        Color band() {
            return band;
        }

        static LightBucket from(String value) {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }

    enum WeatherCategory {
        CLEAR, CLOUDY, FOG, RAIN, SNOW, STORM, MIXED;

        static WeatherCategory fromCode(int code) {
            return switch (code) {
                case 0, 1 -> CLEAR;
                case 2, 3 -> CLOUDY;
                case 45, 48 -> FOG;
                case 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> RAIN;
                case 71, 73, 75, 77, 85, 86 -> SNOW;
                case 95, 96, 99 -> STORM;
                default -> MIXED;
            };
        }
    }
}
