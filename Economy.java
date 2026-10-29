package net.joeyscobblemon.gts;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Talks to CobbleDollars through its own /cd command (give, remove, query), so it works with whatever
 * version is installed. Command output is captured to read balances.
 */
public final class Economy {
    private Economy() {}

    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*)");

    private static final class Capture implements CommandSource {
        final StringBuilder out = new StringBuilder();
        @Override public void sendSystemMessage(Component c) { out.append(c.getString()).append('\n'); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }

    public static final class Result {
        public String out = "";
        public int code = Integer.MIN_VALUE;
        public String error = "";
    }

    public static Result runFull(MinecraftServer server, String command) {
        Capture cap = new Capture();
        Result r = new Result();
        CommandSourceStack src = new CommandSourceStack(cap, Vec3.ZERO, Vec2.ZERO, server.overworld(), 4, "GTS", Component.literal("GTS"), server, null);
        try {
            r.code = server.getCommands().getDispatcher().execute(command, src);
        } catch (Throwable t) {
            r.error = String.valueOf(t.getMessage());
            GtsMod.LOG.warn("[GTS] Economy command '{}' failed: {}", command, t.toString());
        }
        r.out = cap.out.toString();
        return r;
    }

    private static String run(MinecraftServer server, String command) {
        return runFull(server, command).out;
    }

    /** Returns the player's CobbleDollars, or -1 if it could not be read. */
    public static long balance(ServerPlayer p) {
        Result r = runFull(p.server, "cd query " + p.getGameProfile().getName());
        String text = r.out.replace(p.getGameProfile().getName(), "");
        try {
            String rx = GtsConfig.INSTANCE.balanceRegex;
            if (rx != null && !rx.isBlank()) {
                Matcher m = Pattern.compile(rx).matcher(text);
                if (m.find()) return Long.parseLong(m.group(1).replace(",", ""));
            } else {
                Matcher m = NUMBER.matcher(text);
                String last = null;
                while (m.find()) last = m.group(1);
                if (last != null) return Long.parseLong(last.replace(",", ""));
            }
        } catch (Exception ignored) {}
        // Many commands return the value itself as their result.
        if (r.code != Integer.MIN_VALUE && r.code > 1) return r.code;
        GtsMod.LOG.warn("[GTS] Could not read a balance. Command output: '{}', return value: {}, error: '{}'. Set balanceRegex in config/jcl_gts.json.", r.out.trim(), r.code, r.error);
        return -1;
    }

    public static boolean give(ServerPlayer p, long amount) {
        if (amount <= 0) return true;
        run(p.server, "cd give " + p.getGameProfile().getName() + " " + amount);
        return true;
    }

    /** Takes money and checks the balance really dropped. */
    public static boolean take(ServerPlayer p, long amount) {
        if (amount <= 0) return true;
        long before = balance(p);
        if (before < 0 || before < amount) return false;
        run(p.server, "cd remove " + p.getGameProfile().getName() + " " + amount);
        long after = balance(p);
        if (after < 0) return false;
        long removed = before - after;
        if (removed >= amount) return true;
        if (removed > 0) give(p, removed); // partial removal: put it back
        return false;
    }
}
