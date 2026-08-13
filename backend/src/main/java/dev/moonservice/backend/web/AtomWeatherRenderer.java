package dev.moonservice.backend.web;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

/*
 * Paints the weather layer over the large Moon scene created by
 * AtomEntryPreviewRenderer. The fixed shapes, colors, and blur form repeatable
 * artwork for quick feed scanning, not a map of actual cloud or precipitation
 * position or timing.
 */
final class AtomWeatherRenderer {
    private static final float CLOUDY_OPACITY = .72f;
    private static final float STORM_OPACITY = .78f;
    private static final float PRECIPITATION_OPACITY = .54f;
    private static final Color LIGHTNING_COLOR = new Color(224, 229, 205, 92);
    private static final int LIGHTNING_STROKE_PIXELS = 2;
    private static final int[][] LIGHTNING_PATH = {
            {105, 92}, {98, 106}, {105, 103}, {99, 117}
    };
    private static final CloudMass[] CLOUD_MASSES = {
            new CloudMass(-24, 42, 108, 35, new Color(193, 203, 211)),
            new CloudMass(48, 48, 112, 37, new Color(153, 170, 183)),
            new CloudMass(-2, 69, 128, 41, new Color(104, 126, 145)),
            new CloudMass(62, 77, 103, 35, new Color(139, 156, 170)),
            new CloudMass(-20, 96, 111, 31, new Color(180, 192, 201)),
            new CloudMass(52, 98, 115, 34, new Color(119, 140, 156))
    };
    private static final int FOG_BAND_COUNT = 4;
    private static final int FOG_START_X = -8;
    private static final int FOG_X_STEP = 5;
    private static final int FOG_START_Y = 42;
    private static final int FOG_Y_STEP = 23;
    private static final int FOG_WIDTH = 172;
    private static final int FOG_HEIGHT = 14;
    private static final int FOG_ARC = 14;
    private static final int FOG_RED = 190;
    private static final int FOG_GREEN = 203;
    private static final int FOG_BLUE = 211;
    private static final int FOG_RED_STEP = 9;
    private static final int FOG_GREEN_STEP = 7;
    private static final int FOG_BLUE_STEP = 5;
    private static final int FOG_ALPHA = 112;
    private static final int[] MIXED_RAIN_X = {50, 69, 88, 107};
    private static final int[] RAIN_X = {40, 50, 60, 70, 80, 90, 100, 110, 120};
    private static final Color STORM_RAIN_COLOR = new Color(134, 186, 215, 184);
    private static final Color RAIN_COLOR = new Color(137, 199, 228, 168);
    private static final int MIXED_RAIN_STROKE_PIXELS = 1;
    private static final int RAIN_STROKE_PIXELS = 2;
    private static final int RAIN_START_X_OFFSET = 5;
    private static final int RAIN_END_X_OFFSET = -3;
    private static final int RAIN_START_Y = 98;
    private static final int RAIN_ROW_COUNT = 3;
    private static final int RAIN_ROW_STEP = 3;
    private static final int RAIN_LENGTH_Y = 20;
    private static final Color SNOW_COLOR = new Color(231, 240, 244, 199);
    private static final int[] SNOW_X = {35, 51, 68, 86, 103, 120};
    private static final int SNOW_START_Y = 96;
    private static final int SNOW_ROW_COUNT = 3;
    private static final int SNOW_ROW_STEP = 10;
    private static final int SNOW_POINT_SIZE = 4;
    private static final int BLUR_SIZE = 5;
    /* The 1,4,6,4,1 outer product has numerator sum 256; division normalizes it to 1. */
    private static final float[] NORMALIZED_BINOMIAL_BLUR = {
            1 / 256f, 4 / 256f, 6 / 256f, 4 / 256f, 1 / 256f,
            4 / 256f, 16 / 256f, 24 / 256f, 16 / 256f, 4 / 256f,
            6 / 256f, 24 / 256f, 36 / 256f, 24 / 256f, 6 / 256f,
            4 / 256f, 16 / 256f, 24 / 256f, 16 / 256f, 4 / 256f,
            1 / 256f, 4 / 256f, 6 / 256f, 4 / 256f, 1 / 256f
    };
    private static final double CLOUD_START_Y_RATIO = .72;
    /* Six cubic Bézier segments forming the fixed normalized cloud silhouette. */
    private static final double[][] CLOUD_CURVES = {
            {.05, .48, .13, .55, .19, .39},
            {.28, .16, .36, .34, .43, .18},
            {.52, -.04, .64, .08, .67, .34},
            {.77, .18, .87, .31, 1.0, .61},
            {.97, 1.0, .71, .9, .52, .96},
            {.31, 1.0, .08, .96, 0.0, .72}
    };

    private AtomWeatherRenderer() {
    }

    /*
     * Composes one selected overlay. CLEAR intentionally draws nothing. MIXED
     * uses restrained clouds and rain without claiming a named condition.
     */
    static void draw(Graphics2D graphics, AtomEntryPreviewRenderer.WeatherOverlay weather) {
        if (weather == AtomEntryPreviewRenderer.WeatherOverlay.CLEAR) {
            return;
        }
        if (weather == AtomEntryPreviewRenderer.WeatherOverlay.FOG) {
            drawFog(graphics);
            return;
        }
        float opacity = weather == AtomEntryPreviewRenderer.WeatherOverlay.CLOUDY
                ? CLOUDY_OPACITY
                : weather == AtomEntryPreviewRenderer.WeatherOverlay.STORM
                        ? STORM_OPACITY
                        : PRECIPITATION_OPACITY;
        drawClouds(graphics, opacity);
        if (weather == AtomEntryPreviewRenderer.WeatherOverlay.RAIN
                || weather == AtomEntryPreviewRenderer.WeatherOverlay.STORM
                || weather == AtomEntryPreviewRenderer.WeatherOverlay.MIXED) {
            drawRain(graphics, weather);
        } else if (weather == AtomEntryPreviewRenderer.WeatherOverlay.SNOW) {
            drawSnow(graphics);
        }
        if (weather == AtomEntryPreviewRenderer.WeatherOverlay.STORM) {
            graphics.setColor(LIGHTNING_COLOR);
            graphics.setStroke(new BasicStroke(LIGHTNING_STROKE_PIXELS));
            for (int index = 1; index < LIGHTNING_PATH.length; index++) {
                graphics.drawLine(
                        LIGHTNING_PATH[index - 1][0], LIGHTNING_PATH[index - 1][1],
                        LIGHTNING_PATH[index][0], LIGHTNING_PATH[index][1]);
            }
        }
    }

    /*
     * Paints the accepted six overlapping cloud masses on a transparent layer,
     * then applies the shared soft blur before compositing over the Moon.
     */
    private static void drawClouds(Graphics2D graphics, float opacity) {
        BufferedImage layer = layer();
        Graphics2D cloud = layer.createGraphics();
        cloud.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        cloud.setComposite(AlphaComposite.SrcOver.derive(opacity));
        for (CloudMass mass : CLOUD_MASSES) {
            cloudMass(cloud, mass);
        }
        cloud.dispose();
        graphics.drawImage(blur(layer), 0, 0, null);
    }

    /* Draws four offset translucent bands and softens them with the same blur. */
    private static void drawFog(Graphics2D graphics) {
        BufferedImage layer = layer();
        Graphics2D fog = layer.createGraphics();
        for (int index = 0; index < FOG_BAND_COUNT; index++) {
            fog.setColor(new Color(
                    FOG_RED - index * FOG_RED_STEP,
                    FOG_GREEN - index * FOG_GREEN_STEP,
                    FOG_BLUE - index * FOG_BLUE_STEP,
                    FOG_ALPHA));
            fog.fillRoundRect(
                    FOG_START_X + index * FOG_X_STEP,
                    FOG_START_Y + index * FOG_Y_STEP,
                    FOG_WIDTH,
                    FOG_HEIGHT,
                    FOG_ARC,
                    FOG_ARC);
        }
        fog.dispose();
        graphics.drawImage(blur(layer), 0, 0, null);
    }

    /* Keeps all temporary weather pixels transparent outside the fixed Atom canvas. */
    private static BufferedImage layer() {
        return new BufferedImage(
                AtomEntryPreviewRenderer.WIDTH,
                AtomEntryPreviewRenderer.HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
    }

    /*
     * Draws low slanted strokes under the cloud layer. MIXED deliberately uses
     * fewer, thinner strokes so the picture does not claim definite rain.
     */
    private static void drawRain(
            Graphics2D graphics,
            AtomEntryPreviewRenderer.WeatherOverlay weather
    ) {
        int[] offsets = weather == AtomEntryPreviewRenderer.WeatherOverlay.MIXED
                ? MIXED_RAIN_X : RAIN_X;
        graphics.setColor(weather == AtomEntryPreviewRenderer.WeatherOverlay.STORM
                ? STORM_RAIN_COLOR : RAIN_COLOR);
        graphics.setStroke(new BasicStroke(
                weather == AtomEntryPreviewRenderer.WeatherOverlay.MIXED
                        ? MIXED_RAIN_STROKE_PIXELS : RAIN_STROKE_PIXELS));
        for (int index = 0; index < offsets.length; index++) {
            int x = offsets[index];
            int y = RAIN_START_Y + index % RAIN_ROW_COUNT * RAIN_ROW_STEP;
            graphics.drawLine(
                    x + RAIN_START_X_OFFSET,
                    y,
                    x + RAIN_END_X_OFFSET,
                    y + RAIN_LENGTH_Y);
        }
    }

    /* Places six small snow points in three rows below the clouds. */
    private static void drawSnow(Graphics2D graphics) {
        graphics.setColor(SNOW_COLOR);
        for (int index = 0; index < SNOW_X.length; index++) {
            graphics.fillOval(
                    SNOW_X[index],
                    SNOW_START_Y + index % SNOW_ROW_COUNT * SNOW_ROW_STEP,
                    SNOW_POINT_SIZE,
                    SNOW_POINT_SIZE);
        }
    }

    /* Scales the shared normalized Bézier silhouette to one cloud artwork entry. */
    private static void cloudMass(Graphics2D graphics, CloudMass cloud) {
        graphics.setColor(cloud.color());
        Path2D.Double mass = new Path2D.Double();
        mass.moveTo(cloud.x(), cloud.y() + cloud.height() * CLOUD_START_Y_RATIO);
        for (double[] curve : CLOUD_CURVES) {
            mass.curveTo(
                    cloud.x() + cloud.width() * curve[0],
                    cloud.y() + cloud.height() * curve[1],
                    cloud.x() + cloud.width() * curve[2],
                    cloud.y() + cloud.height() * curve[3],
                    cloud.x() + cloud.width() * curve[4],
                    cloud.y() + cloud.height() * curve[5]);
        }
        mass.closePath();
        graphics.fill(mass);
    }

    /*
     * Applies a normalized 5×5 binomial convolution. It softens hard vector
     * edges without deliberately brightening them or depending on platform
     * fonts. EDGE_NO_OP leaves boundary pixels unchanged when the full kernel
     * would extend beyond the image.
     */
    private static BufferedImage blur(BufferedImage image) {
        return new ConvolveOp(
                new Kernel(BLUR_SIZE, BLUR_SIZE, NORMALIZED_BINOMIAL_BLUR),
                ConvolveOp.EDGE_NO_OP,
                null)
                .filter(image, null);
    }

    private record CloudMass(int x, int y, int width, int height, Color color) {
    }
}
