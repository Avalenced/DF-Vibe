package dev.dfonline.codeclient.action.impl;

import dev.dfonline.codeclient.Callback;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.Utility;
import dev.dfonline.codeclient.action.Action;
import dev.dfonline.codeclient.hypercube.item.Location;
import dev.dfonline.codeclient.location.Dev;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Moves the player to a target point in the codespace (dev mode only): a server-authorized
 * location-item teleport when the target is inside the dev area, else teleport-hop movement
 * packets routed through the clear band above the code blocks.
 *
 * Uses handlePacket, sendPacket and tick.
 */
public class GoTo extends Action {
    public final Vec3d target;
    boolean doNotSuppress = false;
    boolean complete = false;
    /**
     * If not null, we are using a location item to teleport.
     */
    private ItemStack locationItem;
    private boolean active;
    private int buffer = 0;
    private int lastTickPackets = 1;
    private int thisTickPackets = 0;
    /** Where we last teleported the player, to detect the server snapping us back (rubber-band). */
    private Vec3d lastJump = null;
    /** Moves to pause after a rubber-band, letting the position settle before we try again. */
    private int rubberCooldown = 0;

    public GoTo(Vec3d target, Callback callback) {
        super(callback);
        this.target = target;
    }

    @Override
    public void init() {
        active = true;
        if (CodeClient.MC.player == null) return;
        if (CodeClient.location instanceof Dev dev && dev.isInArea(target)) {
            locationItem = new Location(dev.getPos().relativize(target)).setRotation(CodeClient.MC.player.getPitch(), CodeClient.MC.player.getYaw()).toStack();
            locationItemTeleport();
        }
    }

    @Override
    public boolean onReceivePacket(Packet<?> packet) {
        if (locationItem != null) {
            if (packet instanceof PlayerPositionLookS2CPacket tp) {
                if (CodeClient.MC.getNetworkHandler() == null || CodeClient.MC.player == null) return false;
                CodeClient.MC.getNetworkHandler().sendPacket(new TeleportConfirmC2SPacket(tp.teleportId()));
                CodeClient.MC.player.setPosition(target);
                active = false;
                callback();
                return true;
            }
            return false;
        }
        return super.onReceivePacket(packet);
    }

    @Override
    public boolean onSendPacket(Packet<?> packet) {
        if (packet instanceof PlayerPositionLookS2CPacket pos) {
            if (doNotSuppress) doNotSuppress = false;
            else return true;
        }
        return super.onSendPacket(packet);
    }

    @Override
    public void tick() {
        thisTickPackets = 0;
        if (CodeClient.MC.player == null || CodeClient.MC.getNetworkHandler() == null) {
            callback();
            return;
        }

        CodeClient.MC.player.setVelocity(0, 0, 0);
        // Complete on PROXIMITY, not exact equality. With NoClip active during placement/scan the
        // player's position is reprocessed by physics every tick (gravity, NoClip clamping), so it
        // never lands on the exact target double - exact-equals would spin forever and the caller
        // would park ("randomly stopping") until it gave up. Within ~0.5 blocks is plenty to place/scan.
        Vec3d ppos = CodeClient.MC.player.getEntityPos();
        if (ppos.squaredDistanceTo(target) < 0.25) {
            active = false;
            complete = true;
            callback();
            return;
        }
        if (locationItem != null) return;
        hackTowards();
        lastTickPackets = thisTickPackets;
    }

    /**
     * The next sub-goal on the way to {@code target}: rise/sink to the clear band just above the chests
     * (floor + 2, which is air with the next layer +5 above), then travel one straight cardinal axis at a
     * time (X, then Z), then settle onto the target. Travelling only in straight segments through the clear
     * band means we never push sideways into a code block.
     */
    private Vec3d subGoal(Vec3d pos) {
        double clearY = Math.floor(target.y / 5.0) * 5.0 + 2.0;
        double horizontal = Math.sqrt(Math.pow(target.x - pos.x, 2) + Math.pow(target.z - pos.z, 2));
        if (horizontal > 1.5) {
            if (Math.abs(pos.y - clearY) > 0.5) return new Vec3d(pos.x, clearY, pos.z); // 1: to the clear band (Y only)
            if (Math.abs(target.x - pos.x) > 0.6) return new Vec3d(target.x, clearY, pos.z); // 2: along X only
            return new Vec3d(pos.x, clearY, target.z);                                       // 3: along Z only
        }
        return target;                                                                       // 4: settle on target
    }

    private void locationItemTeleport() {
        ClientPlayerEntity player = CodeClient.MC.player;
        ClientPlayNetworkHandler net = CodeClient.MC.getNetworkHandler();
        if (player == null || net == null) return;
        ItemStack handItem = player.getMainHandStack();
        Utility.sendHandItem(locationItem);
        boolean sneaky = !player.isSneaking();
        if (sneaky) net.sendPacket(new PlayerInputC2SPacket(new PlayerInput(false, false, false, false, false, true, false)));
        net.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, player.getBlockPos(), Direction.UP));
        net.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, player.getBlockPos(), Direction.UP));
        if (sneaky) net.sendPacket(new PlayerInputC2SPacket(CodeClient.MC.player.getLastPlayerInput()));
        Utility.sendHandItem(handItem);
    }

    /**
     * Use hacky movement packets.
     */
    private void hackTowards() {
        if (CodeClient.MC.player == null || CodeClient.MC.getNetworkHandler() == null) return;

        int bufferStop = 1;
        int bufferReset = 0;

        buffer++;
        if (buffer > bufferStop) {
            if (buffer >= bufferReset) {
                buffer = 0;
            }
            return;
        }
        if (!active) return;
        if (CodeClient.MC.player == null) return;
        Vec3d pos = CodeClient.MC.player.getEntityPos();

        // Rubber-band detection: if the server snapped us far from where we set the player last
        // move, it REJECTED that movement (we pushed into a block / moved too fast). Shoving forward
        // again just oscillates - it looks like trying to clip through the block. So pause a few
        // moves to let the position settle, then resume and re-path from where we actually ended up.
        if (lastJump != null && pos.distanceTo(lastJump) > 5.0) {
            rubberCooldown = 6;
            lastJump = null;
        }
        if (rubberCooldown > 0) {
            rubberCooldown--;
            return;
        }

        // Don't travel HORIZONTALLY through code blocks - DiamondFire's anti-cheat rubber-bands that. Route
        // through the clear band in straight cardinal segments (see subGoal) - never diagonally, never
        // sideways into a block - then settle onto the target.
        Vec3d goal = subGoal(pos);
        Vec3d offset = pos.relativize(goal);
        // Hop size per MOVEMENT packet. Vanilla/DiamondFire reject a position that jumps more than
        // ~10 blocks from the last in a single movement packet ("moved too quickly") and snap the
        // player back - which trips the rubber-band cooldown above and looks like the placer
        // "randomly stopping". This is the ~9.9 the MoveToLocation helper clamps to (and MoveToSpawn
        // relies on). NOTE: the larger ~50-100 limit is for the location-item /codeteleport, a
        // different server-authorized teleport we do NOT use here - movement packets are capped lower.
        double maxLength = 9;
        double distance = offset.length();
        Vec3d jump = distance > maxLength ? pos.add(offset.normalize().multiply(maxLength)) : goal;
        if (distance > 10) {
            for (int i = 0; i < Math.min(lastTickPackets + 2, 6); i++) {
                thisTickPackets++;
                this.doNotSuppress = true;
                CodeClient.MC.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(false, true));
            }
        }
        thisTickPackets++;
        this.doNotSuppress = true;
        CodeClient.MC.player.setPos(jump.x, jump.y, jump.z);
        lastJump = jump;
    }
}
