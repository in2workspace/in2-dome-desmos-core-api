package es.in2.desmos.domain.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.annotation.Nonnull;

public record Version(String original, int major, int minor, int patch) implements Comparable<Version> {

    @JsonCreator
    public static Version from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parse(raw);
    }

    @JsonValue
    public String toJson() {
        return original;
    }

    public static Version parse(String raw) {
        String clean = normalize(raw);

        String[] parts = clean.split("\\.", -1);

        if (parts.length > 3) {
            throw new IllegalArgumentException("Invalid version format: " + raw);
        }

        try {
            int major = parsePart(parts, 0);
            int minor = parsePart(parts, 1);
            int patch = parsePart(parts, 2);

            return new Version(raw, major, minor, patch);

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric version: " + raw, e);
        }
    }

    private static String normalize(String raw) {
        String clean = raw.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }
        return clean;
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length || parts[index].isBlank()) {
            return 0;
        }
        return Integer.parseInt(parts[index]);
    }

    @Override
    public int compareTo(@Nonnull Version other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Version other)) return false;
        return major == other.major && minor == other.minor && patch == other.patch;
    }

    @Override
    public int hashCode() {
        int result = major;
        result = 31 * result + minor;
        result = 31 * result + patch;
        return result;
    }
}

