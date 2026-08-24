package com.donutbalance.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Thin wrapper around https://api.donutsmp.net/v1
 * Docs: https://api.donutsmp.net/v1/player/index.html
 * Endpoint list reference: https://github.com/SzaBee13/DonutSMP-MCP
 */
public class DonutApiClient {
	private static final String BASE_URL = "https://api.donutsmp.net/v1";
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

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
								+ "). Get a fresh key by running /api in-game again.");
					}
					if (response.statusCode() != 200) {
						throw new RuntimeException("DonutSMP API returned HTTP " + response.statusCode());
					}
					return parseBalance(response.body());
				});
	}

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

	/**
	 * Searches the /leaderboards/money/{page} pages, in order, for the given
	 * username, and returns their 1-based rank. There is no "get my rank"
	 * endpoint -- this is genuinely a page-by-page search, so it's capped at
	 * maxPages to avoid an unbounded number of requests if the player is far
	 * down the list. Returns -1 if not found within maxPages.
	 */
	public CompletableFuture<Integer> findBaltopRank(String username, String apiKey, int maxPages) {
		return findRankFromPage(username, apiKey, 1, maxPages, 0);
	}

	private CompletableFuture<Integer> findRankFromPage(String username, String apiKey, int page, int maxPages, int countSoFar) {
		if (page > maxPages) {
			return CompletableFuture.completedFuture(-1);
		}
		return fetchLeaderboardPage(page, apiKey).thenCompose(names -> {
			if (names.isEmpty()) {
				return CompletableFuture.completedFuture(-1);
			}
			for (int i = 0; i < names.size(); i++) {
				if (names.get(i).equalsIgnoreCase(username)) {
					return CompletableFuture.completedFuture(countSoFar + i + 1);
				}
			}
			return findRankFromPage(username, apiKey, page + 1, maxPages, countSoFar + names.size());
		});
	}

	private CompletableFuture<List<String>> fetchLeaderboardPage(int page, String apiKey) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/leaderboards/money/" + page))
				.header("Authorization", "Bearer " + apiKey)
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> {
					if (response.statusCode() != 200) {
						throw new RuntimeException("Leaderboard request failed (HTTP " + response.statusCode() + ")");
					}
					return parseLeaderboardNames(response.body());
				});
	}

	private List<String> parseLeaderboardNames(String body) {
		JsonElement root = JsonParser.parseString(body);
		JsonArray array;
		if (root.isJsonArray()) {
			array = root.getAsJsonArray();
		} else if (root.isJsonObject() && root.getAsJsonObject().has("result")
				&& root.getAsJsonObject().get("result").isJsonArray()) {
			array = root.getAsJsonObject().getAsJsonArray("result");
		} else {
			DonutBalanceClient.LOGGER.warn("Unrecognized leaderboard response shape: {}", body);
			return List.of();
		}

		List<String> names = new ArrayList<>();
		for (JsonElement el : array) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject obj = el.getAsJsonObject();
			for (String key : new String[] {"username", "name", "player", "player_name"}) {
				if (obj.has(key) && !obj.get(key).isJsonNull()) {
					names.add(obj.get(key).getAsString());
					break;
				}
			}
		}
		return names;
	}
}
