package com.donutbalance.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Thin wrapper around https://api.donutsmp.net/v1
 * Docs: https://api.donutsmp.net/v1/player/index.html
 */
public class DonutApiClient {
	private static final String BASE_URL = "https://api.donutsmp.net/v1";
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	/**
	 * Fetches the given player's current balance.
	 * Returns a failed future with a readable message on any error.
	 */
	public CompletableFuture<Double> fetchBalance(String username, String apiKey) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/stats/" + username))
				.header("Authorization", "Bearer " + apiKey)
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> {
					if (response.statusCode() == 401 || response.statusCode() == 403) {
						throw new RuntimeException("API key rejected (HTTP " + response.statusCode()
								+ "). Get a fresh key with /api in-game and put it in config/donutbalance.json");
					}
					if (response.statusCode() != 200) {
						throw new RuntimeException("DonutSMP API returned HTTP " + response.statusCode());
					}
					return parseBalance(response.body());
				});
	}

	/**
	 * The API's exact field name for balance isn't publicly documented in
	 * detail, so this checks the couple of names it's known to use.
	 * If DonutSMP changes their schema, this is the only place to fix it --
	 * run the game with this mod and check the log for the raw JSON if the
	 * balance ever reads as "unknown".
	 */
	private double parseBalance(String body) {
		JsonObject root = JsonParser.parseString(body).getAsJsonObject();
		JsonObject result = root.has("result") && root.get("result").isJsonObject()
				? root.getAsJsonObject("result")
				: root;

		for (String key : new String[] {"money", "balance", "bank"}) {
			if (result.has(key) && !result.get(key).isJsonNull()) {
				return result.get(key).getAsDouble();
			}
		}

		DonutBalanceClient.LOGGER.warn("Could not find a balance field in DonutSMP response: {}", body);
		throw new RuntimeException("Unrecognized response shape from DonutSMP API");
	}
}
