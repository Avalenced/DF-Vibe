package dev.dfonline.codeclient.action.impl;

import dev.dfonline.codeclient.Callback;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.action.Action;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;

import java.util.List;

public class ClearPlot extends Action {
    public Step currentStep = Step.WAIT_FOR_OPTIONS;
    /** False once we've learned we are NOT the plot owner (DF answered "Only the plot owner can use this
     *  command!" to /plot clear, or the clear GUI never opened). The caller (the deploy) then falls back to
     *  clearing the codespace by BREAKING every line, which any builder can do. */
    public boolean clearedByOwner = true;
    /** Ticks since /plot clear was sent, to bail out if the clear GUI never opens (e.g. silent permission fail)
     *  instead of hanging the deploy forever. */
    private int ticks = 0;
    private static final int OPEN_TIMEOUT = 80; // ~4s for the clear GUI to open before we assume "not owner"

    public ClearPlot(Callback callback) {
        super(callback);
    }

    @Override
    public void init() {
        currentStep = Step.WAIT_FOR_OPTIONS;
        clearedByOwner = true;
        ticks = 0;
        CodeClient.MC.getNetworkHandler().sendChatCommand("plot clear");
    }

    @Override
    public void tick() {
        // If the clear GUI hasn't opened in a reasonable time, /plot clear was (silently) refused - treat it as
        // "not owner" so the deploy falls back to breaking. The break path works for owners too, so this is safe.
        if (currentStep == Step.WAIT_FOR_OPTIONS && ++ticks > OPEN_TIMEOUT) {
            clearedByOwner = false;
            currentStep = Step.DONE;
            this.callback();
        }
    }

    @Override
    public boolean onReceivePacket(Packet<?> packet) {
        // DF refuses /plot clear for a non-owner with a chat error. Catch it (without consuming the message, so
        // the player still sees it) and report "not owner" so the deploy clears by breaking instead.
        if (currentStep != Step.DONE && packet instanceof net.minecraft.network.packet.s2c.play.GameMessageS2CPacket msg) {
            String text = msg.content().getString().toLowerCase();
            if (text.contains("only the plot owner")) {
                clearedByOwner = false;
                currentStep = Step.DONE;
                this.callback();
                return false; // let the message render
            }
        }
        if (packet instanceof InventoryS2CPacket inventoryS2CPacket) {

            if (CodeClient.MC.getNetworkHandler() == null) {
                currentStep = Step.DONE;
                this.callback();
                return true;
            }
            var hasher = CodeClient.MC.getNetworkHandler().getComponentHasher();

            if (currentStep == Step.WAIT_FOR_OPTIONS) {
                for (int slot : List.of(9, 14, 15, 44)) {
                    ItemStackHash stack = ItemStackHash.fromItemStack(inventoryS2CPacket.contents().get(slot),hasher);
                    Int2ObjectMap<ItemStackHash> modified = Int2ObjectMaps.singleton(slot, ItemStackHash.fromItemStack(Items.AIR.getDefaultStack(), hasher));
                    CodeClient.MC.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(inventoryS2CPacket.syncId(),inventoryS2CPacket.revision(),(short)slot,(byte)0,SlotActionType.PICKUP,modified,stack));
                }
                currentStep = Step.WAIT_FOR_CONFIRM;
                return true;
            }
            if (currentStep == Step.WAIT_FOR_CONFIRM) {
                short slot = 11;
                ItemStack itemStack = inventoryS2CPacket.contents().get(slot);
                if (itemStack.getItem().equals(Items.GREEN_CONCRETE)) {
                    var air = ItemStackHash.fromItemStack(Items.AIR.getDefaultStack(), hasher);
                    Int2ObjectMap<ItemStackHash> modified = Int2ObjectMaps.singleton(slot, air);
                    CodeClient.MC.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(inventoryS2CPacket.syncId(),inventoryS2CPacket.revision(),slot,(byte)0,SlotActionType.PICKUP,modified,air));
                    currentStep = Step.DONE;
                    this.callback();
                    return true;
                }
            }
        }
        if (currentStep != Step.DONE) {
            if (packet instanceof InventoryS2CPacket) return true;
            if (packet instanceof PlaySoundS2CPacket) return true;
            if (packet instanceof OpenScreenS2CPacket) return true;
            if (packet instanceof ScreenHandlerSlotUpdateS2CPacket) return true;
        }
        return super.onReceivePacket(packet);
    }

    @Override
    public boolean onSendPacket(Packet<?> packet) {
        return super.onSendPacket(packet);
    }

    enum Step {
        WAIT_FOR_OPTIONS,
        WAIT_FOR_CONFIRM,
        DONE
    }
}
