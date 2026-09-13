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
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around https://api.donutsmp.net/v1
 * Docs: https://api.donutsmp.net/v1/player/index.html
 * Endpoint list reference: https://github.com/SzaBee13/DonutSMP-MCP
 */
public class DonutApiClient {
	private static final String BASE_URL = "https://api.donutsmp.net/v1";

	// 502/503/504 mean DonutSMP's own servers are struggling (gateway/
	// overload issues), not that anything is wrong with the request. Worth
	// a few quick automatic retries before giving up.
	private static final int MAX_RETRIES = 3;
	private static final long BASE_RETRY_DELAY_MS = 1000;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	public CompletableFuture<Double> fetchBalance(String username, String apiKey) {
		return fetchBalanceAttempt(username, apiKey, 0);
	}

	private CompletableFuture<Double> fetchBalanceAttempt(String username, String apiKey, int attempt) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/stats/" + username))
				.header("Authorization", "Bearer " + apiKey)
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenCompose(response -> {
					int status = response.statusCode();

					if (isRetryableStatus(status) && attempt < MAX_RETRIES) {
						return retryAfterDelay(attempt, () -> fetchBalanceAttempt(username, apiKey, attempt + 1));
					}
					if (status == 401 || status == 403) {
						throw new RuntimeException("API key rejected (HTTP " + status
								+ "). Get a fresh key by running /api in-game again.");
					}
					if (isRetryableStatus(status)) {
						throw new RuntimeException("DonutSMP's servers are having issues (HTTP " + status
								+ "). Try again in a bit.");
					}
					if (status != 200) {
						throw new RuntimeException("DonutSMP API returned HTTP " + status);
					}
					return CompletableFuture.completedFuture(parseBalance(response.body()));
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
	 * username, and returns their rank. There is no "get my rank" endpoint --
	 * this is genuinely a page-by-page search, so it's capped at maxPages.
	 * Returns -1 if not found within maxPages.
	 */
	public CompletableFuture<Integer> findBaltopRank(String username, String apiKey, int maxPages) {
		return findRankFromPage(username, apiKey, 1, maxPages, 0);
	}

	private CompletableFuture<Integer> findRankFromPage(String username, String apiKey, int page, int maxPages, int countSoFar) {
		if (page > maxPages) {
			return CompletableFuture.completedFuture(-1);
		}
		return fetchLeaderboardPage(page, apiKey, 0).thenCompose(entries -> {
			if (entries.isEmpty()) {
				return CompletableFuture.completedFuture(-1);
			}
			for (int i = 0; i < entries.size(); i++) {
				LeaderboardEntry entry = entries.get(i);
				if (entry.name.equalsIgnoreCase(username)) {
					int rank = entry.rank != null ? entry.rank : (countSoFar + i + 1);
					return CompletableFuture.completedFuture(rank);
				}
			}
			return findRankFromPage(username, apiKey, page + 1, maxPages, countSoFar + entries.size());
		});
	}

	private CompletableFuture<List<LeaderboardEntry>> fetchLeaderboardPage(int page, String apiKey, int attempt) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/leaderboards/money/" + page))
				.header("Authorization", "Bearer " + apiKey)
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenCompose(response -> {
					int status = response.statusCode();

					if (isRetryableStatus(status) && attempt < MAX_RETRIES) {
						return retryAfterDelay(attempt, () -> fetchLeaderboardPage(page, apiKey, attempt + 1));
					}
					if (isRetryableStatus(status)) {
						throw new RuntimeException("DonutSMP's servers are having issues (HTTP " + status + ")");
					}
					if (status != 200) {
						throw new RuntimeException("Leaderboard request failed (HTTP " + status + ")");
					}
					return CompletableFuture.completedFuture(parseLeaderboardEntries(response.body()));
				});
	}

	private static boolean isRetryableStatus(int status) {
		return status == 502 || status == 503 || status == 504;
	}

	/** Waits with exponential backoff (1s, 2s, 4s, ...) then runs the retry. */
	private static <T> CompletableFuture<T> retryAfterDelay(int attempt, java.util.function.Supplier<CompletableFuture<T>> retry) {
		long delayMs = BASE_RETRY_DELAY_MS * (1L << attempt);
		DonutBalanceClient.LOGGER.warn("DonutSMP API is returning a gateway error, retrying in {}ms (attempt {})", delayMs, attempt + 1);
		return CompletableFuture
				.supplyAsync(() -> null, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
				.thenCompose(ignored -> retry.get());
	}

	private List<LeaderboardEntry> parseLeaderboardEntries(String body) {
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

		List<LeaderboardEntry> entries = new ArrayList<>();
		for (JsonElement el : array) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject obj = el.getAsJsonObject();

			String name = null;
			for (String key : new String[] {"username", "name", "player", "player_name"}) {
				if (obj.has(key) && !obj.get(key).isJsonNull()) {
					name = obj.get(key).getAsString();
					break;
				}
			}
			if (name == null) {
				continue;
			}

			Integer rank = null;
			for (String key : new String[] {"rank", "position", "place"}) {
				if (obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).getAsJsonPrimitive().isNumber()) {
					rank = obj.get(key).getAsInt();
					break;
				}
			}

			entries.add(new LeaderboardEntry(name, rank));
		}
		return entries;
	}

	private static final class LeaderboardEntry {
		final String name;
		final Integer rank;

		LeaderboardEntry(String name, Integer rank) {
			this.name = name;
			this.rank = rank;
		}
	}
}
