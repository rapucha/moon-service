package dev.moonservice.backend.web;

import dev.moonservice.backend.opportunity.search.OpportunitySearchResponse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/*
 * Draws the textured Moon discs used by AtomEntryPreviewRenderer for the large
 * scene Moon and the Moon-path markers. This is a small, fixed-input stylized
 * renderer. It is meant to communicate phase and orientation, not to simulate
 * physically accurate light or surface reflectance.
 */
final class AtomMoonRenderer {
    private static final String TEXTURE_RESOURCE = "/static/moon-textures/lroc_color_2k.jpg";
    private static final int RASTER_MARGIN_PIXELS = 1;
    private static final double PIXEL_CENTER_OFFSET = .5;
    private static final double UNIT_DISC_RADIUS_SQUARED = 1.0;
    private static final double UNIT_COORDINATE_LIMIT = 1.0;
    private static final double FULL_LIGHT = 1.0;
    private static final double DARK_SIDE_LIGHT = .22;
    private static final double LIMB_BASE_SHADE = .72;
    private static final double LIMB_CENTER_SHADE = .28;
    private static final int LIT_BLUE_ADDITION = 8;
    private static final int DARK_BLUE_ADDITION = 16;
    private static final int NO_CHANNEL_ADDITION = 0;
    private static final int MIN_CHANNEL = 0;
    private static final int MIN_TEXTURE_INDEX = 0;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final int CHANNEL_MASK = 0xff;
    private static final int OPAQUE_ALPHA = 0xff000000;
    private static final int NEW_MOON_DEGREES = 0;
    private static final int FULL_MOON_DEGREES = 180;
    private static final int NORTH_UP_DEGREES = 0;
    private static final int FULL_CIRCLE_DEGREES = 360;
    private static final double EQUIRECTANGULAR_CENTER = .5;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final BufferedImage TEXTURE = loadTexture();

    private AtomMoonRenderer() {
    }

    /*
     * Projects each output pixel onto the visible half of a unit sphere. The
     * phase-derived Sun vector chooses the lit side with a dot product, while
     * the north-pole rotation changes only where the fixed texture is sampled.
     * Fixed dark-side light, limb shading, and blue additions reproduce the
     * accepted Atom artwork rather than a physical photometry model.
     */
    static void draw(BufferedImage image, int centerX, int centerY, int radius, MoonStyle moon) {
        double phase = Math.toRadians(moon.phaseDegrees());
        /*
         * Without a bright-limb tilt, sin(phase) supplies the established
         * location-independent left/right orientation. With a tilt, the same
         * projected magnitude rotates in the image plane. The z component
         * keeps the chosen phase fraction.
         */
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
        /*
         * The one-pixel raster margin makes the loop symmetric around discs
         * whose edge falls between pixel centers. The unit-disc check still
         * prevents a ring or glow from being drawn outside the Moon.
         */
        for (int y = centerY - radius - RASTER_MARGIN_PIXELS;
                y <= centerY + radius + RASTER_MARGIN_PIXELS; y++) {
            for (int x = centerX - radius - RASTER_MARGIN_PIXELS;
                    x <= centerX + radius + RASTER_MARGIN_PIXELS; x++) {
                double dx = (x + PIXEL_CENTER_OFFSET - centerX) / radius;
                double dy = (y + PIXEL_CENTER_OFFSET - centerY) / radius;
                double distance = dx * dx + dy * dy;
                if (distance > UNIT_DISC_RADIUS_SQUARED || x < 0 || y < 0
                        || x >= image.getWidth() || y >= image.getHeight()) {
                    continue;
                }
                double z = Math.sqrt(UNIT_DISC_RADIUS_SQUARED - distance);
                /* Rotate texture coordinates around the viewer-facing axis. */
                double textureX = dx * poleCos + dy * poleSin;
                double textureY = -dx * poleSin + dy * poleCos;
                int textureRgb = texture(textureX, textureY, z);
                boolean lit = dx * sunX + dy * sunY + z * sunZ > 0.0;
                double light = lit ? FULL_LIGHT : DARK_SIDE_LIGHT;
                double limb = LIMB_BASE_SHADE + LIMB_CENTER_SHADE * z;
                int red = channel(textureRgb >> RED_SHIFT, limb, light, NO_CHANNEL_ADDITION);
                int green = channel(textureRgb >> GREEN_SHIFT, limb, light, NO_CHANNEL_ADDITION);
                int blue = channel(textureRgb, limb, light,
                        lit ? LIT_BLUE_ADDITION : DARK_BLUE_ADDITION);
                image.setRGB(x, y,
                        OPAQUE_ALPHA | red << RED_SHIFT | green << GREEN_SHIFT | blue);
            }
        }
    }

    /*
     * Maps the rotated visible-sphere point to the bundled equirectangular
     * LROC texture. Longitude wraps at the image edge; latitude clamps at the
     * poles so every disc pixel has one fixed source pixel.
     */
    private static int texture(double x, double y, double z) {
        double longitude = Math.atan2(x, z);
        double latitude = Math.asin(Math.max(-UNIT_COORDINATE_LIMIT,
                Math.min(UNIT_COORDINATE_LIMIT, -y)));
        int textureX = Math.floorMod(
                (int) Math.floor((longitude / TWO_PI + EQUIRECTANGULAR_CENTER)
                        * TEXTURE.getWidth()),
                TEXTURE.getWidth());
        int textureY = Math.max(MIN_TEXTURE_INDEX, Math.min(TEXTURE.getHeight() - 1,
                (int) Math.floor((EQUIRECTANGULAR_CENTER - latitude / Math.PI)
                        * TEXTURE.getHeight())));
        return TEXTURE.getRGB(textureX, textureY);
    }

    /* Extracts one packed RGB channel, applies the fixed artwork shading, and clamps it. */
    private static int channel(int packed, double limb, double light, int addition) {
        int source = packed & CHANNEL_MASK;
        return Math.max(MIN_CHANNEL, Math.min(CHANNEL_MASK,
                (int) Math.round(source * limb * light) + addition));
    }

    /* Loads the same tracked texture once for every Atom picture in this process. */
    private static BufferedImage loadTexture() {
        try (InputStream input = AtomMoonRenderer.class.getResourceAsStream(TEXTURE_RESOURCE)) {
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

    /*
     * Holds only inputs that can affect final Moon pixels. Angles are rounded
     * to whole degrees so refresh equality follows the accepted visual model.
     * At exactly new or full Moon, the projected bright limb has zero length,
     * so its tilt cannot change pixels and is canonicalized to null.
     */
    record MoonStyle(int phaseDegrees, Integer brightLimbDegrees, int northPoleDegrees) {
        MoonStyle {
            if (phaseDegrees == NEW_MOON_DEGREES || phaseDegrees == FULL_MOON_DEGREES) {
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
            Integer brightLimb = brightLimbTiltDegrees == null
                    || phase == NEW_MOON_DEGREES || phase == FULL_MOON_DEGREES
                    ? null
                    : wholeDegree(brightLimbTiltDegrees);
            return new MoonStyle(
                    phase,
                    brightLimb,
                    /* Null north-pole tilt is the current canonical north-up contract. */
                    northPoleTiltDegrees == null
                            ? NORTH_UP_DEGREES : wholeDegree(northPoleTiltDegrees));
        }

        /* Rounds first, then wraps into the single stable 0–359 degree representation. */
        private static int wholeDegree(double value) {
            return Math.floorMod((int) Math.round(value), FULL_CIRCLE_DEGREES);
        }
    }
}
