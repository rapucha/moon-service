package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

final class AtomMoonRenderer {
    private static final BufferedImage TEXTURE = loadTexture();

    private AtomMoonRenderer() {
    }

    static void draw(BufferedImage image, int centerX, int centerY, int radius, MoonStyle moon) {
        double phase = Math.toRadians(moon.phaseDegrees());
        double projected = Math.abs(Math.sin(phase));
        double sunX = Math.sin(phase);
        double sunY = 0.0;
        if (moon.brightLimbDegrees() != null) {
            double tilt = Math.toRadians(moon.brightLimbDegrees());
            sunX = projected * Math.sin(tilt);
            sunY = -projected * Math.cos(tilt);
        }
        double sunZ = -Math.cos(phase);
        double pole = Math.toRadians(moon.northPoleDegrees());
        double poleCos = Math.cos(pole);
        double poleSin = Math.sin(pole);
        for (int y = centerY - radius - 1; y <= centerY + radius + 1; y++) {
            for (int x = centerX - radius - 1; x <= centerX + radius + 1; x++) {
                double dx = (x + .5 - centerX) / radius;
                double dy = (y + .5 - centerY) / radius;
                double distance = dx * dx + dy * dy;
                if (distance > 1.0 || x < 0 || y < 0
                        || x >= image.getWidth() || y >= image.getHeight()) {
                    continue;
                }
                double z = Math.sqrt(1.0 - distance);
                double textureX = dx * poleCos + dy * poleSin;
                double textureY = -dx * poleSin + dy * poleCos;
                int textureRgb = texture(textureX, textureY, z);
                boolean lit = dx * sunX + dy * sunY + z * sunZ > 0.0;
                double light = lit ? 1.0 : .22;
                double limb = .72 + .28 * z;
                int red = channel(textureRgb >> 16, limb, light, 0);
                int green = channel(textureRgb >> 8, limb, light, 0);
                int blue = channel(textureRgb, limb, light, lit ? 8 : 16);
                image.setRGB(x, y, 0xff000000 | red << 16 | green << 8 | blue);
            }
        }
    }

    private static int texture(double x, double y, double z) {
        double longitude = Math.atan2(x, z);
        double latitude = Math.asin(Math.max(-1.0, Math.min(1.0, -y)));
        int textureX = Math.floorMod(
                (int) Math.floor((longitude / (Math.PI * 2.0) + .5) * TEXTURE.getWidth()),
                TEXTURE.getWidth());
        int textureY = Math.max(0, Math.min(TEXTURE.getHeight() - 1,
                (int) Math.floor((.5 - latitude / Math.PI) * TEXTURE.getHeight())));
        return TEXTURE.getRGB(textureX, textureY);
    }

    private static int channel(int packed, double limb, double light, int addition) {
        int source = packed & 0xff;
        return Math.max(0, Math.min(255, (int) Math.round(source * limb * light) + addition));
    }

    private static BufferedImage loadTexture() {
        try (InputStream input = AtomMoonRenderer.class.getResourceAsStream(
                "/static/moon-textures/lroc_color_2k.jpg")) {
            if (input == null) {
                throw new IllegalStateException("Moon texture is missing from the classpath.");
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("Moon texture could not be decoded.");
            }
            return image;
        } catch (IOException ex) {
            throw new IllegalStateException("Moon texture could not be loaded.", ex);
        }
    }

    record MoonStyle(int phaseDegrees, Integer brightLimbDegrees, int northPoleDegrees) {
        MoonStyle {
            if (phaseDegrees == 0 || phaseDegrees == 180) {
                brightLimbDegrees = null;
            }
        }

        static MoonStyle from(OpportunitySearchResponse.Moon moon) {
            return from(
                    moon.phaseAngleDegrees(),
                    moon.brightLimbTiltDegrees(),
                    moon.northPoleTiltDegrees());
        }

        static MoonStyle from(OpportunitySearchResponse.MoonPathPoint point) {
            return from(
                    point.moonPhaseAngleDegrees(),
                    point.brightLimbTiltDegrees(),
                    point.northPoleTiltDegrees());
        }

        private static MoonStyle from(
                double phaseAngleDegrees,
                Double brightLimbTiltDegrees,
                Double northPoleTiltDegrees
        ) {
            int phase = wholeDegree(phaseAngleDegrees);
            Integer brightLimb = brightLimbTiltDegrees == null || phase == 0 || phase == 180
                    ? null
                    : wholeDegree(brightLimbTiltDegrees);
            return new MoonStyle(
                    phase,
                    brightLimb,
                    northPoleTiltDegrees == null ? 0 : wholeDegree(northPoleTiltDegrees));
        }

        private static int wholeDegree(double value) {
            return Math.floorMod((int) Math.round(value), 360);
        }
    }
}
