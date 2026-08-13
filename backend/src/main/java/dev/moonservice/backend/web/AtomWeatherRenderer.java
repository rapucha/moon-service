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

final class AtomWeatherRenderer {
    private AtomWeatherRenderer() {
    }

    static void draw(Graphics2D graphics, AtomEntryPreviewRenderer.WeatherCategory weather) {
        if (weather == AtomEntryPreviewRenderer.WeatherCategory.CLEAR) {
            return;
        }
        if (weather == AtomEntryPreviewRenderer.WeatherCategory.FOG) {
            drawFog(graphics);
            return;
        }
        float opacity = weather == AtomEntryPreviewRenderer.WeatherCategory.CLOUDY ? .72f
                : weather == AtomEntryPreviewRenderer.WeatherCategory.STORM ? .78f : .54f;
        drawClouds(graphics, opacity);
        if (weather == AtomEntryPreviewRenderer.WeatherCategory.RAIN
                || weather == AtomEntryPreviewRenderer.WeatherCategory.STORM
                || weather == AtomEntryPreviewRenderer.WeatherCategory.MIXED) {
            drawRain(graphics, weather);
        } else if (weather == AtomEntryPreviewRenderer.WeatherCategory.SNOW) {
            drawSnow(graphics);
        }
        if (weather == AtomEntryPreviewRenderer.WeatherCategory.STORM) {
            graphics.setColor(new Color(224, 229, 205, 92));
            graphics.setStroke(new BasicStroke(2));
            graphics.drawLine(105, 92, 98, 106);
            graphics.drawLine(98, 106, 105, 103);
            graphics.drawLine(105, 103, 99, 117);
        }
    }

    private static void drawClouds(Graphics2D graphics, float opacity) {
        BufferedImage layer = layer();
        Graphics2D cloud = layer.createGraphics();
        cloud.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        cloud.setComposite(AlphaComposite.SrcOver.derive(opacity));
        cloudMass(cloud, -24, 42, 108, 35, new Color(193, 203, 211));
        cloudMass(cloud, 48, 48, 112, 37, new Color(153, 170, 183));
        cloudMass(cloud, -2, 69, 128, 41, new Color(104, 126, 145));
        cloudMass(cloud, 62, 77, 103, 35, new Color(139, 156, 170));
        cloudMass(cloud, -20, 96, 111, 31, new Color(180, 192, 201));
        cloudMass(cloud, 52, 98, 115, 34, new Color(119, 140, 156));
        cloud.dispose();
        graphics.drawImage(blur(layer), 0, 0, null);
    }

    private static void drawFog(Graphics2D graphics) {
        BufferedImage layer = layer();
        Graphics2D fog = layer.createGraphics();
        for (int index = 0; index < 4; index++) {
            fog.setColor(new Color(190 - index * 9, 203 - index * 7, 211 - index * 5, 112));
            fog.fillRoundRect(-8 + index * 5, 42 + index * 23, 172, 14, 14, 14);
        }
        fog.dispose();
        graphics.drawImage(blur(layer), 0, 0, null);
    }

    private static BufferedImage layer() {
        return new BufferedImage(
                AtomEntryPreviewRenderer.WIDTH,
                AtomEntryPreviewRenderer.HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
    }

    private static void drawRain(
            Graphics2D graphics,
            AtomEntryPreviewRenderer.WeatherCategory weather
    ) {
        int[] offsets = weather == AtomEntryPreviewRenderer.WeatherCategory.MIXED
                ? new int[]{50, 69, 88, 107} : new int[]{40, 50, 60, 70, 80, 90, 100, 110, 120};
        graphics.setColor(weather == AtomEntryPreviewRenderer.WeatherCategory.STORM
                ? new Color(134, 186, 215, 184) : new Color(137, 199, 228, 168));
        graphics.setStroke(new BasicStroke(
                weather == AtomEntryPreviewRenderer.WeatherCategory.MIXED ? 1 : 2));
        for (int index = 0; index < offsets.length; index++) {
            int x = offsets[index];
            int y = 98 + index % 3 * 3;
            graphics.drawLine(x + 5, y, x - 3, y + 20);
        }
    }

    private static void drawSnow(Graphics2D graphics) {
        graphics.setColor(new Color(231, 240, 244, 199));
        int[] x = {35, 51, 68, 86, 103, 120};
        for (int index = 0; index < x.length; index++) {
            graphics.fillOval(x[index], 96 + index % 3 * 10, 4, 4);
        }
    }

    private static void cloudMass(
            Graphics2D graphics,
            int x,
            int y,
            int width,
            int height,
            Color color
    ) {
        graphics.setColor(color);
        Path2D.Double mass = new Path2D.Double();
        mass.moveTo(x, y + height * .72);
        mass.curveTo(x + width * .05, y + height * .48, x + width * .13,
                y + height * .55, x + width * .19, y + height * .39);
        mass.curveTo(x + width * .28, y + height * .16, x + width * .36,
                y + height * .34, x + width * .43, y + height * .18);
        mass.curveTo(x + width * .52, y - height * .04, x + width * .64,
                y + height * .08, x + width * .67, y + height * .34);
        mass.curveTo(x + width * .77, y + height * .18, x + width * .87,
                y + height * .31, x + width, y + height * .61);
        mass.curveTo(x + width * .97, y + height, x + width * .71,
                y + height * .9, x + width * .52, y + height * .96);
        mass.curveTo(x + width * .31, y + height, x + width * .08,
                y + height * .96, x, y + height * .72);
        mass.closePath();
        graphics.fill(mass);
    }

    private static BufferedImage blur(BufferedImage image) {
        float[] kernel = {
                1 / 256f, 4 / 256f, 6 / 256f, 4 / 256f, 1 / 256f,
                4 / 256f, 16 / 256f, 24 / 256f, 16 / 256f, 4 / 256f,
                6 / 256f, 24 / 256f, 36 / 256f, 24 / 256f, 6 / 256f,
                4 / 256f, 16 / 256f, 24 / 256f, 16 / 256f, 4 / 256f,
                1 / 256f, 4 / 256f, 6 / 256f, 4 / 256f, 1 / 256f
        };
        return new ConvolveOp(new Kernel(5, 5, kernel), ConvolveOp.EDGE_NO_OP, null)
                .filter(image, null);
    }
}
