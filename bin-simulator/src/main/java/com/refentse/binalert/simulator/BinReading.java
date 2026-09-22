package com.refentse.binalert.simulator;

import java.time.Instant;

public class BinReading {
    private final String binId;
    private final String timestamp;
    private final double fillLevel;
    private final double battery;

    public BinReading(String binId, double fillLevel, double battery) {
        this.binId = binId;
        this.timestamp = Instant.now().toString();
        this.fillLevel = fillLevel;
        this.battery = battery;
    }

    public String getBinId() {
        return binId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public double getFillLevel() {
        return fillLevel;
    }

    public double getBattery() {
        return battery;
    }
}
