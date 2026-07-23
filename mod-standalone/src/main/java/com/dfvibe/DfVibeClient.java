package com.dfvibe;

import com.dfvibe.commands.DfCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/** Client-side entrypoint: load config, register the /df command tree. */
public class DfVibeClient implements ClientModInitializer {
	public static final String MOD_ID = "df-vibe";
	public static Config config;

	@Override
	public void onInitializeClient() {
		config = Config.load();
		PlotDetector.install();
		RuntimeLog.install();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> DfCommand.register(dispatcher));
		System.out.println("[df-vibe] initialized. Use /df help in-game.");
	}
}
