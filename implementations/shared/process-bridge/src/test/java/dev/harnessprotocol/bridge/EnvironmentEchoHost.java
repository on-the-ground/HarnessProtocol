package dev.harnessprotocol.bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dependency-free child process that reports the environment built by JsonLineProcessBridge. */
public final class EnvironmentEchoHost {
    private static final Pattern REQUEST_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)");

    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null; ) {
            Matcher match = REQUEST_ID.matcher(line);
            if (!match.find()) throw new IllegalArgumentException("missing id");
            boolean hasPath = System.getenv().keySet().stream().anyMatch(key -> key.equalsIgnoreCase("PATH"));
            String sentinel = System.getenv("AHP_ENV_SENTINEL");
            String encodedSentinel = sentinel == null ? "null" : "\"" + sentinel + "\"";
            System.out.printf(
                "{\"kind\":\"response\",\"id\":%s,\"result\":{\"hasPath\":%s,\"sentinel\":%s}}%n",
                match.group(1), hasPath, encodedSentinel
            );
            System.out.flush();
        }
    }

    private EnvironmentEchoHost() {}
}
