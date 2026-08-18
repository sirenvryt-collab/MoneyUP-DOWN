package com.donutbalance.mod;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * DonutSMP's API only ever gives you the CURRENT balance -- there's no
 * "balance at midnight" endpoint. So this mod remembers the first balance
 * it sees each day (local midnight, your computer's timezone) and treats
 * that as the baseline for "up or down today". The baseline is saved to
 * disk so restarting the game doesn't reset it until the next real midnight.
 */
public class BalanceTracker {
	private static final Path STATE_PATH = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("donutbalance_state.json");
	private static final Gson GSON = new Gson();

	private State state;
	private volatile double currentBalance;
	private volatile boolean hasData = false;
	private volatile String lastError = null;

	public BalanceTracker() {
		this.state = loadState();
	}

	/** Call this every time a fresh balance is fetched from the API. */
	public synchronized void onBalanceFetched(double balance) {
		LocalDate today = LocalDate.now();
		if (state.date == null || !state.date.equals(today.toString())) {
			// First reading of a new day -> this becomes today's midnight baseline.
			state.date = today.toString();
			state.baseline = balance;
			saveState();
		}
		this.currentBalance = balance;
		this.hasData = true;
		this.lastError = null;
	}

	public synchronized void onFetchFailed(String message) {
		this.lastError = message;
	}

	public boolean hasData() {
		return hasData;
	}

	public String getLastError() {
		return lastError;
	}

	public double getCurrentBalance() {
		return currentBalance;
	}

	public double getBaseline() {
		return state.baseline;
	}

	public double getDelta() {
		return currentBalance - state.baseline;
	}

	private State loadState() {
		if (Files.exists(STATE_PATH)) {
			try {
				String json = Files.readString(STATE_PATH, StandardCharsets.UTF_8);
				State loaded = GSON.fromJson(json, State.class);
				if (loaded != null) {
					return loaded;
				}
			} catch (IOException e) {
				DonutBalanceClient.LOGGER.error("Failed to read donutbalance_state.json", e);
			}
		}
		return new State();
	}

	private void saveState() {
		try {
			Files.createDirectories(STATE_PATH.getParent());
			Files.writeString(STATE_PATH, GSON.toJson(state), StandardCharsets.UTF_8);
		} catch (IOException e) {
			DonutBalanceClient.LOGGER.error("Failed to save donutbalance_state.json", e);
		}
	}

	private static class State {
		String date;
		double baseline;
	}
}
