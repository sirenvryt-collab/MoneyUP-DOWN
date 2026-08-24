package com.donutbalance.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local, per-install config. This file lives in the Minecraft "config"
 * folder on the player's own computer -- it is never bundled into the
 * jar and never belongs in the git repo. See .gitignore.
 */
public class ModConfig {
	private static final Path PATH = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("donutbalance.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Your DonutSMP API key, from the in-game /api command. Leave blank until you set it. */
	public String apiKey = "";

	/** How often (in seconds) to poll the DonutSMP API for your balance. */
	public int refreshIntervalSeconds = 60;

	/**
	 * There's no "get my rank" endpoint -- finding your baltop position means
	 * paging through /leaderboards/money/{page} until your name shows up.
	 * This caps how many pages /moneycheck will search before giving up, so
	 * it doesn't send unbounded requests if you're far down the list.
	 */
	public int maxBaltopSearchPages = 50;

	public static ModConfig load() {
		if (Files.exists(PATH)) {
			try {
				String json = Files.readString(PATH, StandardCharsets.UTF_8);
				ModConfig config = GSON.fromJson(json, ModConfig.class);
				if (config != null) {
					return config;
				}
			} catch (IOException e) {
				DonutBalanceClient.LOGGER.error("Failed to read donutbalance.json, using defaults", e);
			}
		}
		ModConfig fresh = new ModConfig();
		fresh.save();
		return fresh;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			DonutBalanceClient.LOGGER.error("Failed to save donutbalance.json", e);
		}
	}
}
