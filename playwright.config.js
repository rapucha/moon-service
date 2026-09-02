import { defineConfig } from "@playwright/test";

const chromePath = process.env.PLAYWRIGHT_CHROME_PATH || "/usr/bin/google-chrome";
const hostedAlpha = process.env.MOON_SERVICE_PLAYWRIGHT_HOSTED_ALPHA === "true";
const hostedAlphaSpec = /hosted-alpha-surface\.spec\.js/;
const baseURL = process.env.MOON_SERVICE_BASE_URL || "http://127.0.0.1:8081";
const startServer = process.env.MOON_SERVICE_PLAYWRIGHT_START_SERVER !== "false";
const serverCommand = hostedAlpha
  ? "mvn -pl backend -am spring-boot:run -Dspring-boot.run.arguments=\"--server.port=8081 --moon.location.resolver=open-meteo --moon.weather.provider=open-meteo --moon.admin.generate-token=false --moon.admin.token="
    + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    + " --moon.hosted-alpha.enabled=true\""
  : "mvn -pl backend -am spring-boot:run -Dspring-boot.run.arguments=\"--server.port=8081 --moon.location.resolver=open-meteo --moon.weather.provider=open-meteo --moon.admin.generate-token=true\"";

export default defineConfig({
  testDir: "tests/ui",
  testIgnore: hostedAlpha ? undefined : hostedAlphaSpec,
  timeout: 30_000,
  fullyParallel: false,
  reporter: [["list"]],
  webServer: startServer
    ? {
      command: serverCommand,
      url: hostedAlpha ? "http://localhost:8081/readyz" : baseURL + "/search",
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
