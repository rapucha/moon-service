package dev.moonservice.scoringprototype;

import java.util.Locale;

public final class Json {
    private final StringBuilder builder = new StringBuilder();
    private int indent = 0;

    public void line(String value) {
        if (value.equals("}") || value.equals("},") || value.equals("]") || value.equals("],")) {
            indent--;
        }
        builder.append("  ".repeat(Math.max(0, indent))).append(value).append(System.lineSeparator());
        if (value.endsWith("{") || value.endsWith("[")) {
            indent++;
        }
    }

    public void field(String name, String value, boolean comma) {
        line("\"" + name + "\": \"" + escape(value) + "\"" + (comma ? "," : ""));
    }

    public void field(String name, double value, boolean comma) {
        line("\"" + name + "\": " + String.format(Locale.ROOT, "%.3f", value) + (comma ? "," : ""));
    }

    public void field(String name, double value, int decimals, boolean comma) {
        line("\"" + name + "\": " + String.format(Locale.ROOT, "%." + decimals + "f", value) + (comma ? "," : ""));
    }

    public void field(String name, int value, boolean comma) {
        line("\"" + name + "\": " + value + (comma ? "," : ""));
    }

    public void stringValue(String value, boolean comma) {
        line("\"" + escape(value) + "\"" + (comma ? "," : ""));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String toString() {
        return builder.toString();
    }
}
