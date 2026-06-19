package dev.moonservice.scoringprototype.cli;

import dev.moonservice.scoringprototype.UsageException;
import dev.moonservice.scoringprototype.input.PrototypeConfig;
import dev.moonservice.scoringprototype.input.RequestConfigReader;
import dev.moonservice.scoringprototype.output.ResponseFormatter;
import dev.moonservice.scoringprototype.service.OpportunityService;
import dev.moonservice.scoringprototype.service.PrototypeResult;

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
        if (args.length != 2 || !args[0].equals("--request")) {
            throw new UsageException("--request must be used by itself in this prototype.");
        }
        return RequestConfigReader.read(Path.of(args[1]));
    }

    public static String run(PrototypeConfig config) {
        PrototypeResult result = new OpportunityService().evaluate(config);
        return new ResponseFormatter().format(result);
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage:",
                "  mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.classpathScope=test -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype -Dexec.args=\"--request fixtures/prague-preview-request.json\"",
                "",
                "Options:",
                "  --request FILE  Request-shaped JSON fixture."
        );
    }
}
