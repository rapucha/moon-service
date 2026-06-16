package dev.moonservice.scoringprototype.cli;

import dev.moonservice.scoringprototype.service.OpportunityService;
import dev.moonservice.scoringprototype.service.PrototypeResult;
import dev.moonservice.scoringprototype.output.ResponseFormatter;
import dev.moonservice.scoringprototype.UsageException;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.input.RequestConfigReader;

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

    public static PrototypeConfig parseConfig(String[] args) {
        if (args.length > 0 && args[0].equals("--request")) {
            if (args.length != 2) {
                throw new UsageException("--request must be used by itself in this prototype.");
            }
            return RequestConfigReader.read(Path.of(args[1]));
        }
        return PrototypeConfig.parse(args);
    }

    public static String run(PrototypeConfig config) {
        PrototypeResult result = new OpportunityService().evaluate(config);
        return new ResponseFormatter().format(result);
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage:",
                "  mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype -Dexec.args=\"[options]\"",
                "  mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype -Dexec.args=\"--request fixtures/prague-preview-request.json\"",
                "",
                "Options:",
                "  --request FILE              Request-shaped JSON fixture; use by itself.",
                "  --location prague-cz         Fixture location; only prague-cz exists in this prototype.",
                "  --start YYYY-MM-DD|INSTANT  Local start date. Instants are mapped to their UTC date. Default: 2026-06-29.",
                "  --days N                    Local days to evaluate. Default: 7.",
                "  --max-altitude DEG          Low-Moon ceiling. Default: 12.",
                "  --limit N                   Maximum returned windows. Default: 10."
        );
    }
}
