package com.cvcompass.backend.web;

import java.util.Collections;
import java.util.IdentityHashMap;

/** Bounded application source locations only; never formats a Throwable or its messages. */
public final class SafeStackLocations {
    public static final int MAX_FRAMES = 12;
    public static final int MAX_LENGTH = 2048;
    public static final int MAX_CAUSES = 3;
    private static final int MAX_INSPECTED_FRAMES = 64;
    private final String value;

    private SafeStackLocations(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SafeStackLocations capture(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var output = new StringBuilder();
        int count = 0;
        for (int depth = 0; failure != null && depth < MAX_CAUSES && seen.add(failure); depth++) {
            StackTraceElement[] frames;
            try {
                frames = failure.getStackTrace();
            } catch (RuntimeException unavailable) {
                break;
            }
            if (frames == null) break;
            for (int i = 0; i < Math.min(frames.length, MAX_INSPECTED_FRAMES); i++) {
                var frame = frames[i];
                if (frame == null) continue;
                String name = frame.getClassName(),
                        method = frame.getMethodName(),
                        file = frame.getFileName();
                if (!name.startsWith("com.cvcompass.backend.")
                        || !name.matches("[A-Za-z_$][A-Za-z0-9_.$]{0,191}")
                        || !method.matches("[A-Za-z0-9_$<>]{1,96}")) continue;
                if (file == null || !file.matches("[A-Za-z0-9_$.-]{1,96}")) file = "UnknownSource";
                String location =
                        name + "." + method + "(" + file + ":" + frame.getLineNumber() + ")";
                if (count >= MAX_FRAMES || output.length() + location.length() + 1 > MAX_LENGTH) {
                    return new SafeStackLocations(output.toString());
                }
                if (!output.isEmpty()) output.append(';');
                output.append(location);
                count++;
            }
            try {
                failure = failure.getCause();
            } catch (RuntimeException unavailable) {
                break;
            }
        }
        return new SafeStackLocations(output.toString());
    }
}
