package dev.moonservice.backend;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class AstronomyEngineLicenseTest {

    @Test
    void packagesThePinnedAstronomyEngineLicenseNotice() throws IOException {
        String pinnedVersion = System.getProperty("astronomy.version");
        assertNotNull(pinnedVersion, "the build must expose the pinned Astronomy Engine version to this test");

        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("META-INF/LICENSE-Astronomy-Engine.txt")) {
            assertNotNull(input, "the distributed backend must retain Astronomy Engine's MIT notice");
            String notice = new String(input.readAllBytes(), UTF_8);
            assertEquals("Astronomy Engine " + pinnedVersion, notice.lines().findFirst().orElse(""));
            assertTrue(notice.contains("Copyright (c) 2019-2023 Don Cross"));
            assertTrue(notice.contains("Permission is hereby granted"));
        }
    }
}
