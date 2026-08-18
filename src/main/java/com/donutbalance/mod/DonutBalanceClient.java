package com.donutbalance.mod;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class DonutBalanceClient implements ClientModInitializer {
	public static final String MOD_ID = "donutbalance";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private ModConfig config;
	private final BalanceTracker tracker = new BalanceTracker();
	private final DonutApiClient api = new DonutApiClient();

	private int tickCounter = 0;
	private boolean requestInFlight = false;

	@Override
	public void onInitializeClient() {
		this.config = ModConfig.load();

		// Keep the midnight baseline current in the background, even if the
		// player never types the command, so /moneycheck is accurate the
		// moment they use it.
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

		ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);

		LOGGER.info("Donut Balance Tracker loaded. Edit config/donutbalance.json to set your API key. Use /moneycheck in-game.");
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess registryAccess) {
		dispatcher.register(literal("moneycheck")
				.executes(context -> {
					runMoneyCheck(context.getSource());
					return 1;
				}));
	}

	private void runMoneyCheck(FabricClientCommandSource source) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (config.apiKey == null || config.apiKey.isBlank()) {
			source.sendError(Text.literal(
					"No API key set. Get one with /api in-game, then put it in config/donutbalance.json"));
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
					requestInFlight = false;
					if (error != null) {
						LOGGER.warn("DonutSMP balance fetch failed: {}", error.getMessage());
						tracker.onFetchFailed(error.getMessage());
						source.sendError(Text.literal("DonutSMP: " + error.getMessage()));
						return;
					}

					tracker.onBalanceFetched(balance);
					reportBalance(source);
				}));
	}

	private void reportBalance(FabricClientCommandSource source) {
		double balance = tracker.getCurrentBalance();
		double delta = tracker.getDelta();

		String balanceStr = String.format(Locale.US, "$%,.0f", balance);
		Formatting deltaColor = delta > 0 ? Formatting.GREEN : delta < 0 ? Formatting.RED : Formatting.GRAY;
		String sign = delta > 0 ? "+" : delta < 0 ? "-" : "";
		String deltaStr = String.format(Locale.US, "%s$%,.0f", sign, Math.abs(delta));

		Text message = Text.literal("Balance: ").formatted(Formatting.GOLD)
				.append(Text.literal(balanceStr).formatted(Formatting.WHITE))
				.append(Text.literal("  |  Since midnight: ").formatted(Formatting.GOLD))
				.append(Text.literal(deltaStr).formatted(deltaColor));

		source.sendFeedback(message);
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
