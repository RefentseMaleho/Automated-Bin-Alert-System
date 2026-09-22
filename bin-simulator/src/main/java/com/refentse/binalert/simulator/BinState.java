package com.refentse.binalert.simulator;

import java.util.Random;

public class BinState {
    private final String binId;
    private final Random random = new Random();
    private double fill;
    private double battery;

    public BinState(String binId) {
        this.binId = binId;
        this.fill = random.nextDouble() * 20;
        this.battery = 100.0;
    }

    public BinReading nextReading() {
        // Occasionally "empty" the bin once it's mostly full
        if (fill > 85 && random.nextDouble() < 0.3) {
            fill = random.nextDouble() * 5;
        } else {
            fill = Math.min(fill + 0.5 + random.nextDouble() * 3.5, 100.0);
        }

        // Battery drains slowly, resets occasionally (simulates a swap)
        battery -= 0.01 + random.nextDouble() * 0.04;
        if (battery < 5) {
            battery = 100.0;
        }

        return new BinReading(binId, fill, battery);
    }
}
