package com.refentse.binalert.simulator;

import com.google.gson.Gson;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BinSimulator {

    private static final String BIN_ID = "bin-001";
    private static final String TOPIC = "bins/" + BIN_ID + "/telemetry";
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        double intervalSeconds = opts.containsKey("interval")
                ? Double.parseDouble(opts.get("interval")) : 5.0;

        if (opts.containsKey("local")) {
            runLocal(intervalSeconds);
        } else {
            String endpoint = require(opts, "endpoint");
            String cert = require(opts, "cert");
            String key = require(opts, "key");
            String rootCa = require(opts, "root-ca");
            runAws(endpoint, cert, key, rootCa, intervalSeconds);
        }
    }

    private static void runLocal(double intervalSeconds) throws InterruptedException {
        System.out.println("[local] Simulating " + BIN_ID + " — Ctrl+C to stop.\n");
        BinState state = new BinState(BIN_ID);
        while (true) {
            BinReading reading = state.nextReading();
            System.out.println(GSON.toJson(reading));
            TimeUnit.MILLISECONDS.sleep((long) (intervalSeconds * 1000));
        }
    }

    private static void runAws(String endpoint, String certPath, String keyPath,
                               String rootCaPath, double intervalSeconds) throws Exception {
        try (MqttClientConnection connection = AwsIotMqttConnectionBuilder
                .newMtlsBuilderFromPath(certPath, keyPath)
                .withCertificateAuthorityFromPath(null, rootCaPath)
                .withEndpoint(endpoint)
                .withClientId(BIN_ID)
                .withCleanSession(true)
                .build()) {

            connection.connect().get();
            System.out.println("[aws] Connected. Publishing to '" + TOPIC + "' every "
                    + intervalSeconds + "s — Ctrl+C to stop.\n");

            BinState state = new BinState(BIN_ID);
            while (true) {
                BinReading reading = state.nextReading();
                String payload = GSON.toJson(reading);
                MqttMessage message = new MqttMessage(
                        TOPIC,
                        payload.getBytes(StandardCharsets.UTF_8),
                        QualityOfService.AT_LEAST_ONCE,
                        false);
                connection.publish(message).get();
                System.out.println("[aws] Published: " + payload);
                TimeUnit.MILLISECONDS.sleep((long) (intervalSeconds * 1000));
            }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--local")) {
                opts.put("local", "true");
            } else if (arg.startsWith("--")) {
                String key = arg.substring(2);
                if (i + 1 < args.length) {
                    opts.put(key, args[++i]);
                }
            }
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing required --" + key + " (or use --local for local-only mode)");
        }
        return value;
    }
}