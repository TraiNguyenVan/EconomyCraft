package com.reazip.economycraft.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** One message block in the ordered login MOTD sequence. */
public class MotdBlock {
    public static final int DEFAULT_NEXT_DELAY_SECONDS = 30;
    public static final int MIN_NEXT_DELAY_SECONDS = 0;
    public static final int MAX_NEXT_DELAY_SECONDS = 3600;

    @SerializedName("lines")
    public List<String> lines = new ArrayList<>();

    @SerializedName("next_delay_seconds")
    public int nextDelaySeconds = DEFAULT_NEXT_DELAY_SECONDS;

    public MotdBlock() {}

    public MotdBlock(List<String> lines) {
        this.lines = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
    }
}
