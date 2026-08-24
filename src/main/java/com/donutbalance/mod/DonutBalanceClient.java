package com.donutbalance.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class DonutBalanceClient implements ClientModInitializer {
	public static final String MOD_ID = "donutbalance";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// DonutSMP API keys are 32-character hex strings. This lets us pick the
	// key out of whatever message /api prints without hardcoding its exact
	// wording (which can change).
	private static final Pattern API_KEY_PATTERN = Pattern.compile("\\b[0-9a-fA-F]{32}\\b");

	private ModConfig config;
	private final BalanceTracker tracker = new BalanceTracker();
	private final DonutApiClient api = new DonutApiClient();

	private int tickCounter = 0;
	private boolean requestInFlight = false;

	// Only used to phrase the "not found" message; actual page size varies
	// by leaderboard response and isn't guaranteed, so this is a rough guess.
	private static final int pageSizeHint = 10;

	@Override
	public void onInitializeClient() {
		this.config = ModConfig.load();

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
		ClientReceiveMessageEvents.GAME.register(this::onGameMessage);

		LOGGER.info("Donut Balance Tracker loaded. Run /api in-game once to set your key automatically, then use /moneycheck.");
	}

	private void onGameMessage(Text message, boolean overlay) {
		if (overlay) {
			return;
		}
		String content = message.getString();
		Matcher matcher = API_KEY_PATTERN.matcher(content);
		if (!matcher.find()) {
			return;
		}
		String foundKey = matcher.group();
		if (foundKey.equals(config.apiKey)) {
			return;
		}

		config.apiKey = foundKey;
		config.save();

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			client.player.sendMessage(
					Text.literal("[Donut Balance Tracker] API key detected and saved automatically. Try /moneycheck!")
							.formatted(Formatting.GREEN),
					false);
		}
		LOGGER.info("Auto-detected and saved a DonutSMP API key from chat.");
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess registryAccess) {
		dispatcher.register(literal("moneycheck")
				.executes(context -> {
					runMoneyCheck(context.getSource());
					return 1;
				})
				.then(literal("setkey")
						.then(argument("key", StringArgumentType.greedyString())
								.executes(context -> {
									String key = StringArgumentType.getString(context, "key").trim();
									config.apiKey = key;
									config.save();
									context.getSource().sendFeedback(
											Text.literal("API key saved.").formatted(Formatting.GREEN));
									return 1;
								})))
				.then(literal("clearkey")
						.executes(context -> {
							config.apiKey = "";
							config.save();
							context.getSource().sendFeedback(
									Text.literal("API key cleared.").formatted(Formatting.YELLOW));
							return 1;
						})));
	}

	private void runMoneyCheck(FabricClientCommandSource source) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (config.apiKey == null || config.apiKey.isBlank()) {
			source.sendError(Text.literal(
					"No API key yet. Just run /api in-game on DonutSMP once and it'll be saved automatically."));
			return;
		}
		if (client.getSession() == null) {
			source.sendError(Text.literal("Not logged in."));
			return;
		}

		source.sendFeedback(Text.literal("Checking DonutSMP balance...").formatted(Formatting.GRAY));

		String username = client.getSession().getUsername();
		requestInFlight = true;
		api.fetchBalance(username, config.apiKey)
				.whenComplete((balance, error) -> client.execute(() -> {
					if (error != null) {
						requestInFlight = false;
						LOGGER.warn("DonutSMP balance fetch failed: {}", error.getMessage());
						tracker.onFetchFailed(error.getMessage());
						source.sendError(Text.literal("DonutSMP: " + error.getMessage()));
						return;
					}

					tracker.onBalanceFetched(balance);
					reportBalance(source);

					api.findBaltopRank(username, config.apiKey, config.maxBaltopSearchPages)
							.whenComplete((rank, rankError) -> client.execute(() -> {
								requestInFlight = false;
								if (rankError != null) {
									LOGGER.warn("Baltop rank lookup failed: {}", rankError.getMessage());
									source.sendError(Text.literal("Baltop rank: lookup failed (" + rankError.getMessage() + ")"));
									return;
								}
								if (rank == -1) {
									source.sendFeedback(Text.literal(
											"Baltop rank: not found in top " + config.maxBaltopSearchPages * pageSizeHint
													+ " (or beyond search limit)").formatted(Formatting.GRAY));
								} else {
									source.sendFeedback(Text.literal("Baltop rank: ").formatted(Formatting.GOLD)
											.append(Text.literal("#" + rank).formatted(Formatting.AQUA)));
								}
							}));
				}));
	}

	private void reportBalance(FabricClientCommandSource source) {
		double balance = tracker.getCurrentBalance();
		double delta = tracker.getDelta();

		String balanceStr = "$" + formatExact(balance);
		Formatting deltaColor = delta > 0 ? Formatting.GREEN : delta < 0 ? Formatting.RED : Formatting.GRAY;
		String sign = delta > 0 ? "+" : delta < 0 ? "-" : "";
		String deltaStr = sign + "$" + formatExact(Math.abs(delta));

		Text message = Text.literal("Balance: ").formatted(Formatting.GOLD)
				.append(Text.literal(balanceStr).formatted(Formatting.WHITE))
				.append(Text.literal("  |  Since midnight: ").formatted(Formatting.GOLD))
				.append(Text.literal(deltaStr).formatted(deltaColor));

		source.sendFeedback(message);
	}

	/** Exact value with thousands separators, e.g. 2731400000 -> "2,731,400,000". */
	private static String formatExact(double value) {
		return String.format(Locale.US, "%,.0f", value);
	}

	private void onTick(MinecraftClient client) {
		if (requestInFlight || client.getSession() == null) {
			return;
		}
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return;
		}

		tickCounter++;
		int intervalTicks = Math.max(20, config.refreshIntervalSeconds * 20);
		if (tickCounter < intervalTicks) {
			return;
		}
		tickCounter = 0;

		String username = client.getSession().getUsername();
		requestInFlight = true;
		api.fetchBalance(username, config.apiKey)
				.whenComplete((balance, error) -> {
					requestInFlight = false;
					if (error != null) {
						LOGGER.warn("Background DonutSMP balance fetch failed: {}", error.getMessage());
						tracker.onFetchFailed(error.getMessage());
					} else {
						tracker.onBalanceFetched(balance);
					}
				});
	}
}
