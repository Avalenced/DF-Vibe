package dev.dfonline.codeclient.websocket;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.dfonline.codeclient.Callback;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.Utility;
import dev.dfonline.codeclient.action.None;
import dev.dfonline.codeclient.action.impl.*;
import dev.dfonline.codeclient.config.Config;
import dev.dfonline.codeclient.location.Plot;
import dev.dfonline.codeclient.websocket.scope.AuthScope;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.inventory.StackWithSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.java_websocket.WebSocket;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.*;

public class SocketHandler {
    public static final int PORT = 31375;
    /** How many ports above {@link #PORT} to try if it's busy, so several MC instances (different accounts,
     *  each scanning its own codespace) can run side by side instead of fighting over one port. */
    private static final int PORT_TRIES = 16;
    /** The port THIS instance actually bound (= {@link #PORT} for the first instance, the next free one for the
     *  rest). The in-process DF Vibe client connects here so it always reaches its OWN API, never another
     *  instance's. Read via {@code SocketHandler.boundPort}. */
    public static volatile int boundPort = PORT;
    private final ArrayList<Action> actionQueue = new ArrayList<>();
    // Current connection
    @Nullable
    private WebSocket connection = null;
    // Default scopes
    private static final List<AuthScope> defaultAuthScopes = List.of(AuthScope.DEFAULT);
    // Unapproved scopes that the user needs to approve
    private List<AuthScope> unapprovedAuthScopes = List.of();
    // Approved scopes the application has access to
    private List<AuthScope> authScopes = defaultAuthScopes;
    // Map of tokens and their scopes
    private final HashMap<String, List<AuthScope>> tokenMap = new HashMap<>();
    // The active token, empty if none
    private String activeToken = null;
    private SocketServer websocket;

    public void start() {
        String bindIP = Config.getConfig().apiBindIP;
        Exception last = null;
        for (int p = PORT; p < PORT + PORT_TRIES; p++) {
            try {
                // Probe first: a free port means we can bind it. This is what lets a second/third MC instance
                // start its own API on 31376, 31377, ... instead of crashing on "address already in use".
                try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
                    probe.setReuseAddress(true);
                    probe.bind(new InetSocketAddress(bindIP, p));
                }
                SocketServer server = new SocketServer(new InetSocketAddress(bindIP, p), this);
                server.setReuseAddr(true);
                Thread socketThread = new Thread(server, "CodeClient-API");
                socketThread.start();
                websocket = server;
                boundPort = p;
                if (p != PORT) CodeClient.LOGGER.info("CodeClient API: port {} busy, bound {} instead", PORT, p);
                CodeClient.LOGGER.info("Socket opened on port {}", p);
                return;
            } catch (Exception e) {
                last = e; // port busy / bind failed - try the next one
            }
        }
        throw new RuntimeException("CodeClient API: no free port in " + PORT + ".." + (PORT + PORT_TRIES - 1), last);
    }

    public void stop() {
        try {
            websocket.stop();
        } catch (Exception ignored) {
        }
    }

    public void setAcceptedScopes(boolean accepted) {
        if(connection == null) return;
        actionQueue.clear();
        if (accepted) {
            // Add the unapproved scopes & the default scopes. Build a fresh mutable list
            // (unapprovedAuthScopes may be an immutable List.of(), e.g. when /auth is run
            // manually) so addAll never throws UnsupportedOperationException.
            List<AuthScope> merged = new ArrayList<>(unapprovedAuthScopes);
            for (AuthScope s : defaultAuthScopes) if (!merged.contains(s)) merged.add(s);
            authScopes = merged;
            connection.send("auth");
        } else {
            // Set to default scopes
            authScopes = defaultAuthScopes;
            connection.send("removed");
        }
        unapprovedAuthScopes = List.of();
    }

    public void setConnection(WebSocket socket) {
        if (socket != null) actionQueue.clear();
        if (connection != null) {
            connection.close(); // Close the old connection
            if (activeToken != null) {
                // If we were using a token, save the scopes
                tokenMap.put(activeToken, authScopes);
            }
            // Reset the active token and scopes
            activeToken = null;
            authScopes = defaultAuthScopes;
            unapprovedAuthScopes = List.of();
        }
        connection = socket;
    }


    public void onMessage(String message) {
        assert connection != null;
        String[] arguments = message.split(" ");
        Action topAction = getTopAction();
        if (arguments[0] == null) return;
        String content = arguments.length > 1 ? message.substring(arguments[0].length() + 1) : "";
        if (topAction != null && arguments.length > 1 && Objects.equals(topAction.name, arguments[0])) {
            topAction.message(connection, content);
            return;
        }
        switch (arguments[0]) {
            case "scopes" -> handleScopeRequest(arguments);
            case "token" -> handleTokenRequest(arguments);
            case "clear" -> assertScopeLevel(new Clear());
            case "spawn" -> assertScopeLevel(new Spawn());
            case "size" -> assertScopeLevel(new Size());
            case "scan" -> assertScopeLevel(new Scan(false, content.contains("save"), false));
            case "scandeep" -> assertScopeLevel(new Scan(true, content.contains("save"), false)); // -verify/-chests: rebuild from blocks
            case "scanclear" -> assertScopeLevel(new Scan(false, false, true)); // no-owner deploy: read every line (backup) AND break it
            case "capture" -> assertScopeLevel(new Capture()); // /df schematic save: read a region's blocks + block-entity data
            case "place" -> assertScopeLevel(new Place());
            case "placepos" -> assertScopeLevel(new PlacePos());
            case "inv" -> assertScopeLevel(new SendInventory());
            case "setinv" -> assertScopeLevel(new SetInventory(content));
            case "give" -> assertScopeLevel(new Give(content));
            case "mode" -> assertScopeLevel(new Mode(content));
            default -> connection.send("invalid");
        }
        topAction = getTopAction();
        if (topAction != null && arguments.length > 1 && Objects.equals(topAction.name, arguments[0])) {
            topAction.message(connection, content);
        }
        if (actionQueue.isEmpty()) return;
        Action firstAction = actionQueue.get(0);
        if (firstAction == null) return;
        if (firstAction.active) return;
        next();
    }

    private Action getTopAction() {
        if (actionQueue.isEmpty()) return null;
        return actionQueue.get(actionQueue.size() - 1);
    }

    private void handleScopeRequest(String[] arguments) {
        assert connection != null;
        List<String> args = Arrays.asList(arguments).subList(1, arguments.length);

        // Send the currently approved scopes if no args are provided
        if (args.isEmpty()) {
            String scopesString = String.join(" ", authScopes.stream().map(authScope -> authScope.translationKey).toArray(String[]::new));
            connection.send(scopesString);
            return;
        }

        List<AuthScope> scopes = new ArrayList<>();
        List<String> invalidScopes = new ArrayList<>();

        // Add scopes to the list if they are valid
        for (String arg : args) {
            AuthScope scope;
            try {
                scope = AuthScope.valueOf(arg.toUpperCase());
            } catch (IllegalArgumentException e) {
                invalidScopes.add(arg);
                continue;
            }
            scopes.add(scope);
        }

        // Send the invalid scopes if any are found, and alert the user
        if (!invalidScopes.isEmpty()) {
            connection.send("invalid scope " + String.join(" ", invalidScopes));
            Utility.sendMessage(Text.translatable("codeclient.api.scope.invalid"));
            return;
        }

        // DF VIBE fork: auto-approve every requested scope — no /auth prompt, ever.
        // setAcceptedScopes() merges in the default scopes, activates them, and
        // replies "auth" to the client, exactly as if the user had run /auth.
        unapprovedAuthScopes = scopes;
        setAcceptedScopes(true);
    }

    private void handleTokenRequest(String[] arguments) {
        assert connection != null;
        List<String> args = Arrays.asList(arguments).subList(1, arguments.length);

        if (args.isEmpty()) {
            // Send the active token if one is active, otherwise regenerate it.
            String token = activeToken == null ? Utility.genAuthToken() : activeToken;
            connection.send("token " + token);
            tokenMap.put(token, authScopes);
            activeToken = token;
        } else {
            String token = args.get(0);
            if (!tokenMap.containsKey(token)) {
                connection.send("invalid token");
                return;
            }
            // Restore the scopes
            authScopes = tokenMap.get(token);
            connection.send("auth");
            activeToken = token;
        }
    }

    private void assertScopeLevel(SocketHandler.Action commandClass) {
        if (!authScopes.contains(commandClass.authScope)) {
            if(connection != null) connection.send("unauthed");
            return;
        }
        actionQueue.add(commandClass);
    }

    private void promptUserAcceptScopes() {
        if (unapprovedAuthScopes.isEmpty()) return;
        if (CodeClient.MC.player == null) return;

        CodeClient.MC.executeSync(() -> {
            ClientPlayerEntity player = CodeClient.MC.player;

            // Send the user the scopes to approve
            Utility.sendMessage(Text.translatable("codeclient.api.scope.prompt"));
            for (AuthScope scope : unapprovedAuthScopes) {
                Utility.sendMessage(
                    Text.empty()
                        .append(
                            Text.literal("- ")
                                .formatted(Formatting.DARK_GRAY)
                        )
                        .append(
                            Text.translatable("codeclient.api.scope.type." + scope.translationKey)
                                .formatted(Formatting.WHITE)
                                .append(" ")
                                .append(
                                    Text.literal("(")
                                        .append(Text.translatable("codeclient.api.danger." + scope.dangerLevel.translationKey))
                                        .append(Text.literal(")"))
                                        .formatted(scope.dangerLevel.color, Formatting.ITALIC)
                                )
                        )
                        .setStyle(Style.EMPTY.withHoverEvent(
                            new HoverEvent.ShowText(Text.translatable("codeclient.api.danger." + scope.dangerLevel.translationKey + ".description"))
                        ))
                );
            }
            Utility.sendMessage(Text.translatable("codeclient.api.run_auth"));
        });
    }

    private void next() {
        if (actionQueue.isEmpty()) return;
        Action firstAction = actionQueue.get(0);
        if (firstAction == null) return;
        if (firstAction.active) {
            actionQueue.remove(0);
            CodeClient.LOGGER.info(firstAction.name + " done");
            next();
            return;
        }
        CodeClient.LOGGER.info("starting " + firstAction.name);
        firstAction.active = true;
        firstAction.start(connection);
    }

    public void abort() {
        actionQueue.clear();
        if(connection != null) connection.send("aborted");
    }

    private abstract static class Action {
        public final String name;
        public final AuthScope authScope;
        boolean active = false;

        Action(String name, AuthScope authScope) {
            this.name = name;
            this.authScope = authScope;
        }

        /**
         * When the action is added to the queue
         */
        public abstract void set(WebSocket responder);

        /**
         * When the queue reaches the action
         */
        public abstract void start(WebSocket responder);

        /**
         * If a message is sent when this is the last set
         */
        public abstract void message(WebSocket responder, String message);
    }

    private class Clear extends SocketHandler.Action {
        Clear() {
            super("clear", AuthScope.CLEAR_PLOT);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            // Report whether /plot clear actually cleared (we're the owner) or was refused (not owner) so the
            // client can fall back to clearing by breaking lines. The old API sent nothing on success.
            final ClearPlot[] cp = new ClearPlot[1];
            cp[0] = new ClearPlot(() -> {
                try { if (responder.isOpen()) responder.send(cp[0].clearedByOwner ? "clear ok" : "clear notowner"); }
                catch (Throwable ignored) {}
                SocketHandler.this.next();
            });
            CodeClient.currentAction = cp[0];
            cp[0].init();
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class Spawn extends SocketHandler.Action {
        Spawn() {
            super("spawn", AuthScope.MOVEMENT);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            CodeClient.currentAction = new MoveToSpawn(SocketHandler.this::next);
            CodeClient.currentAction.init();
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class Size extends SocketHandler.Action {
        Size() {
            super("size", AuthScope.READ_PLOT);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (!(CodeClient.location instanceof Plot plot)) {
                if (responder.isOpen()) responder.send("none");
                next();
                return;
            }
            if (plot.getSize() != null) {
                if (responder.isOpen()) responder.send(plot.getSize().name());
                next();
            } else {
                CodeClient.currentAction = new GetPlotSize(() -> {
                    Plot.Size sz = plot.getSize();
                    if (responder.isOpen()) responder.send(sz != null ? sz.name() : "unknown");
                    next();
                });
            }
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class Scan extends SocketHandler.Action {
        private final boolean deep;
        private final boolean save;
        private final boolean clear; // no-owner deploy: break each line after reading it (needs CLEAR_PLOT)

        Scan(boolean deep, boolean save, boolean clear) {
            super("scan", clear ? AuthScope.CLEAR_PLOT : AuthScope.READ_PLOT);
            this.deep = deep;
            this.save = save;
            this.clear = clear;
        }

        @Override
        public void set(WebSocket responder) {

        }

        @Override
        public void start(WebSocket responder) {
            ArrayList<ItemStack> items = new ArrayList<>();
            dev.dfonline.codeclient.action.impl.ScanPlot.FORCE_SALVAGE = deep; // calibration: rebuild every line from blocks
            dev.dfonline.codeclient.action.impl.ScanPlot.BREAK_AFTER_READ = clear; // no-owner deploy: clear by breaking each read line
            // Heartbeat: keep this socket alive through a long scan by relaying periodic progress pings.
            dev.dfonline.codeclient.action.impl.ScanPlot.HEARTBEAT = msg -> {
                try { if (responder.isOpen()) responder.send(msg); } catch (Throwable ignored) {}
            };
            // Save-as-you-go (-save): stream each line's token the instant it's grabbed so the pull can
            // checkpoint to disk. Off otherwise (the final batched result is sent on completion as always).
            dev.dfonline.codeclient.action.impl.ScanPlot.LINE_SINK = save ? token -> {
                try { if (responder.isOpen()) responder.send("scan-line " + token); } catch (Throwable ignored) {}
            } : null;
            CodeClient.currentAction = new ScanPlot(() -> {
                dev.dfonline.codeclient.action.impl.ScanPlot.FORCE_SALVAGE = false; // never leak into the next scan
                dev.dfonline.codeclient.action.impl.ScanPlot.BREAK_AFTER_READ = false;
                dev.dfonline.codeclient.action.impl.ScanPlot.HEARTBEAT = null;
                dev.dfonline.codeclient.action.impl.ScanPlot.LINE_SINK = null;
                // A long scan (esp. -chests) can outlast the client's 4-min WS poll - by the time it
                // finishes, the socket may be closed. Sending to a dead socket throws on the render thread
                // and CRASHES the game, so guard every send: if the socket's gone, just drop the result
                // (the pull already gave up and reported a timeout).
                try {
                    if (items.isEmpty()) {
                        if (responder.isOpen()) responder.send("empty");
                    } else {
                        var builder = new StringBuilder();
                        for (var item : items) {
                            var data = Utility.templateDataItem(item);
                            if (data == null) continue;
                            builder.append(data).append("\n");
                        }
                        if (builder.length() > 0) builder.deleteCharAt(builder.length() - 1);
                        if (responder.isOpen()) responder.send(builder.toString());
                    }
                } catch (Throwable t) {
                    CodeClient.LOGGER.warn("scan result send failed (socket closed?): " + t);
                }
                CodeClient.currentAction = new dev.dfonline.codeclient.action.None();
                next();
            }, items);
            CodeClient.currentAction.init();
        }

        @Override
        public void message(WebSocket responder, String message) {

        }
    }

    private class Capture extends SocketHandler.Action {
        Capture() { super("capture", AuthScope.READ_PLOT); }

        @Override
        public void set(WebSocket responder) {

        }

        @Override
        public void start(WebSocket responder) {
            // Keep the socket alive through a long region capture by relaying periodic progress pings.
            dev.dfonline.codeclient.action.impl.CaptureRegion.HEARTBEAT = msg -> {
                try { if (responder.isOpen()) responder.send(msg); } catch (Throwable ignored) {}
            };
            CodeClient.currentAction = new dev.dfonline.codeclient.action.impl.CaptureRegion(() -> {
                dev.dfonline.codeclient.action.impl.CaptureRegion.HEARTBEAT = null;
                try {
                    if (responder.isOpen())
                        responder.send("capture done " + dev.dfonline.codeclient.action.impl.CaptureRegion.SUMMARY);
                } catch (Throwable t) {
                    CodeClient.LOGGER.warn("capture result send failed (socket closed?): " + t);
                }
                CodeClient.currentAction = new dev.dfonline.codeclient.action.None();
                next();
            });
            CodeClient.currentAction.init();
        }

        @Override
        public void message(WebSocket responder, String message) {

        }
    }

    private class Place extends SocketHandler.Action {
        private final ArrayList<ItemStack> templates = new ArrayList<>();
        public boolean ready = false;
        private Method method = Method.DEFAULT;
        Place() {
            super("place", AuthScope.WRITE_CODE);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (!ready) return;
            var placer = method.createPlacer.run(templates, SocketHandler.this::next, responder);
            if (placer == null) return;
            CodeClient.currentAction = placer;
            CodeClient.currentAction.init();
        }

        @Override
        public void message(WebSocket responder, String message) {
            switch (message) {
                case "compact" -> {
                    this.method = Method.COMPACT;
                    return;
                }
                case "swap" -> {
                    this.method = Method.SWAP;
                    return;
                }
                case "go" -> {
                    this.ready = true;
                    if (Objects.equals(actionQueue.get(0), this)) {
                        this.start(responder);
                    }
                    return;
                }
            }
            templates.add(Utility.makeTemplate(message));
        }

        private enum Method {
            DEFAULT((ArrayList<ItemStack> templates, Callback next, WebSocket responder) -> PlaceTemplates.createPlacer(templates, () -> {
                CodeClient.currentAction = new None();
                if (responder.isOpen()) responder.send(PlaceTemplates.doneMessage("place done"));
                next.run();
            })),
            COMPACT((ArrayList<ItemStack> templates, Callback next, WebSocket responder) -> PlaceTemplates.createPlacer(templates, () -> {
                CodeClient.currentAction = new None();
                if (responder.isOpen()) responder.send(PlaceTemplates.doneMessage("place done"));
                next.run();
            }, true)),
            SWAP((ArrayList<ItemStack> templates, Callback next, WebSocket responder) -> PlaceTemplates.createSwapper(templates, () -> {
                CodeClient.currentAction = new None();
                if (responder.isOpen()) responder.send(PlaceTemplates.doneMessage("place done"));
                next.run();
            }).swap()),
            ;

            public final CreatePlacer createPlacer;

            Method(CreatePlacer createPlacer) {
                this.createPlacer = createPlacer;
            }
        }

        private interface CreatePlacer {
            PlaceTemplates run(ArrayList<ItemStack> templates, Callback next, WebSocket responder);
        }
    }

    /**
     * Place templates at PRECOMPUTED absolute positions (swap mode). The DF VIBE mod computes the whole
     * deploy layout up front and sends "placepos &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;token&gt;" per line, then
     * "placepos go". Because every position is fixed by the caller, chunking a big deploy across several
     * placepos calls can't make the chunks overlap (unlike "place", which re-derives free spots each call).
     */
    private class PlacePos extends SocketHandler.Action {
        private final HashMap<BlockPos, ItemStack> map = new HashMap<>();
        public boolean ready = false;

        PlacePos() {
            super("placepos", AuthScope.WRITE_CODE);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (!ready) return;
            PlaceTemplates placer = new PlaceTemplates(map, map.keySet(), () -> {
                CodeClient.currentAction = new None();
                if (responder.isOpen()) responder.send(PlaceTemplates.doneMessage("placepos done"));
                next();
            }).swap();
            CodeClient.currentAction = placer;
            CodeClient.currentAction.init();
        }

        @Override
        public void message(WebSocket responder, String message) {
            if (message.equals("go")) {
                this.ready = true;
                if (Objects.equals(actionQueue.get(0), this)) {
                    this.start(responder);
                }
                return;
            }
            // "<x> <y> <z> <base64token>" - token has no spaces, so a 4-way split is safe.
            String[] p = message.split(" ", 4);
            if (p.length < 4) return;
            try {
                int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
                map.put(new BlockPos(x, y, z), Utility.makeTemplate(p[3]));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private class SendInventory extends SocketHandler.Action {
        SendInventory() {
            super("inv", AuthScope.INVENTORY);
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            var view = NbtWriteView.create(null, CodeClient.MC.player.getRegistryManager());
            CodeClient.MC.player.getInventory().writeData(view.getListAppender("Inventory", StackWithSlot.CODEC));
            responder.send(String.valueOf(view.getNbt().get("Inventory")));
            next();
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class SetInventory extends SocketHandler.Action {
        private final String content;

        SetInventory(String content) {
            super("setinv", AuthScope.INVENTORY);
            this.content = content;
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (CodeClient.MC.player == null) return;
            if (!CodeClient.MC.player.isCreative()) {
                responder.send("not creative mode");
                next();
                return;
            }
            try {
                CodeClient.MC.player.getInventory().readData(
                        NbtReadView.create(null, CodeClient.MC.player.getRegistryManager(), StringNbtReader.readCompound("{Inventory:" + content + "}")).getTypedListView("Inventory", StackWithSlot.CODEC)
                );
                Utility.sendInventory();
            } catch (CommandSyntaxException e) {
                responder.send("invalid nbt");
            } finally {
                next();
            }
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class Give extends SocketHandler.Action {
        private final String content;

        Give(String content) {
            super("give", AuthScope.DEFAULT);
            this.content = content;
        }

        @Override
        public void set(WebSocket responder) {
        }

        @Override
        public void start(WebSocket responder) {
            if (CodeClient.MC.player == null) return;
            if (!CodeClient.MC.player.isCreative()) {
                responder.send("not creative mode");
                next();
                return;
            }
            try {
                if (CodeClient.MC.world == null) return;
                Optional<ItemStack> itemStack = ItemStack.CODEC.parse(CodeClient.MC.player.getRegistryManager().getOps(NbtOps.INSTANCE), StringNbtReader.readCompound(content)).result();
                if (itemStack.isEmpty()) return;
                CodeClient.MC.player.giveItemStack(itemStack.get());
                Utility.sendInventory();
            } catch (CommandSyntaxException e) {
                responder.send("invalid nbt");
            } finally {
                next();
            }
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }

    private class Mode extends SocketHandler.Action {
        private static final List<String> commands = List.of("play", "build", "code", "dev");
        private final String command;

        Mode(String command) {
            super("mode", AuthScope.MOVEMENT);
            this.command = commands.contains(command) ? command : "";
        }

        @Override
        public void set(WebSocket responder) {

        }

        @Override
        public void start(WebSocket responder) {
            if (CodeClient.location instanceof Plot && !command.isEmpty() && CodeClient.MC.getNetworkHandler() != null) {
                CodeClient.MC.getNetworkHandler().sendChatCommand(command);
            } else {
                responder.send(CodeClient.location.name());
            }
            next();
        }

        @Override
        public void message(WebSocket responder, String message) {
        }
    }
}
