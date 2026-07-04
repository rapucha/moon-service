import { defineConfig } from "@playwright/test";

const chromePath = process.env.PLAYWRIGHT_CHROME_PATH || "/usr/bin/google-chrome";
const baseURL = process.env.MOON_SERVICE_BASE_URL || "http://127.0.0.1:8081";
const startServer = process.env.MOON_SERVICE_PLAYWRIGHT_START_SERVER !== "false";

export default defineConfig({
  testDir: "tests/ui",
  timeout: 30_000,
  fullyParallel: false,
  reporter: [["list"]],
  webServer: startServer
    ? {
      command: "mvn -pl backend -am spring-boot:run -Dspring-boot.run.arguments=\"--server.port=8081 --moon.location.resolver=open-meteo --moon.weather.provider=open-meteo --moon.admin.generate-token=true\"",
      url: baseURL + "/search",
      reuseExistingServer: true,
      timeout: 120_000
    }
    : undefined,
  use: {
    baseURL: baseURL,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    launchOptions: {
      executablePath: chromePath,
      args: ["--no-sandbox"]
    }
  },
  projects: [
    {
      name: "desktop",
      use: {
        viewport: { width: 1400, height: 1200 }
      }
    },
    {
      name: "mobile",
      use: {
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true
      }
    }
  ]
});
