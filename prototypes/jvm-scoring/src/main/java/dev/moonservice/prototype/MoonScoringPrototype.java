package dev.moonservice.prototype;

import java.nio.file.Path;

public final class MoonScoringPrototype {
    private MoonScoringPrototype() {
    }

    public static void main(String[] args) {
        try {
            System.out.println(run(parseConfig(args)));
        } catch (UsageException ex) {
            System.err.println(ex.getMessage());
            System.err.println(usage());
            System.exit(2);
        }
    }

    static PrototypeConfig parseConfig(String[] args) {
        if (args.length > 0 && args[0].equals("--request")) {
            if (args.length != 2) {
                throw new UsageException("--request must be used by itself in this prototype.");
            }
            return RequestConfigReader.read(Path.of(args[1]));
        }
        return PrototypeConfig.parse(args);
    }

    static String run(PrototypeConfig config) {
        PrototypeResult result = new OpportunityService().evaluate(config);
        return new ResponseFormatter().format(result);
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage:",
                "  mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.mainClass=dev.moonservice.prototype.MoonScoringPrototype -Dexec.args=\"[options]\"",
                "  mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.mainClass=dev.moonservice.prototype.MoonScoringPrototype -Dexec.args=\"--request fixtures/prague-preview-request.json\"",
                "",
                "Options:",
                "  --request FILE              Request-shaped JSON fixture; use by itself.",
                "  --location prague-cz         Fixture location; only prague-cz exists in this prototype.",
                "  --start YYYY-MM-DD|INSTANT  UTC start date or instant. Default: 2026-06-29.",
                "  --days N                    Days to sample. Default: 7.",
                "  --step-minutes N            Sampling step. Default: 30.",
                "  --max-altitude DEG          Low-Moon ceiling. Default: 12.",
                "  --min-score N               Minimum returned score. Default: 50.",
                "  --limit N                   Maximum returned windows. Default: 10."
        );
    }
}
