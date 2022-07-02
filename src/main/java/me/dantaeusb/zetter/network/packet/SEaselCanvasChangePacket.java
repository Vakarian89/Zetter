package me.dantaeusb.zetter.network.packet;

import me.dantaeusb.zetter.Zetter;
import me.dantaeusb.zetter.network.ClientHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.LogicalSidedProvider;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * @deprecated
 * Use Entity's SyncData instead
 * @link {SSetSlotPacket}
 */
@Deprecated()
public class SEaselCanvasChangePacket {
    private int entityId;
    private ItemStack item;

    public SEaselCanvasChangePacket(int entityId, ItemStack item) {
        this.entityId = entityId;
        this.item = item.copy();
    }

    /**
     * Reads the raw packet data from the data stream.
     */
    public static SEaselCanvasChangePacket readPacketData(FriendlyByteBuf networkBuffer) {
        try {
            final int windowId = networkBuffer.readByte();
            final ItemStack item = networkBuffer.readItem();

            return new SEaselCanvasChangePacket(windowId, item);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            Zetter.LOG.warn("Exception while reading SEaselCanvasChangePacket: " + e);
            return null;
        }
    }

    /**
     * Writes the raw packet data to the data stream.
     */
    public void writePacketData(FriendlyByteBuf networkBuffer) {
        networkBuffer.writeByte(this.entityId);
        networkBuffer.writeItem(this.item);
    }

    public int getEntityId() {
        return this.entityId;
    }

    public ItemStack getItem() {
        return this.item;
    }

    public static void handle(final SEaselCanvasChangePacket packetIn, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        LogicalSide sideReceived = ctx.getDirection().getReceptionSide();
        ctx.setPacketHandled(true);

        Optional<Level> clientWorld = LogicalSidedProvider.CLIENTWORLD.get(sideReceived);
        if (!clientWorld.isPresent()) {
            Zetter.LOG.warn("SEaselCanvasChangePacket context could not provide a ClientWorld.");
            return;
        }

        ctx.enqueueWork(() -> ClientHandler.processEaselCanvasUpdate(packetIn, clientWorld.get()));
    }

    @Override
    public String toString()
    {
        return "SEaselCanvasChangePacket[windowId=" + this.entityId + ",stack=" + this.item + "]";
    }
}