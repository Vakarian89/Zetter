package me.dantaeusb.zetter.canvastracker;

import me.dantaeusb.zetter.storage.AbstractCanvasData;
import me.dantaeusb.zetter.storage.DummyCanvasData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

public class CanvasDefaultTracker implements ICanvasTracker {
    public CanvasDefaultTracker() {
        //this.registerCanvasData(Helper.FALLBACK_CANVAS_CODE, DummyCanvasData.createDummy());
    }

    public Level getWorld() {
        return null;
    }

    public int getNextCanvasId() {
        return 0;
    }

    public int getLastCanvasId() {
        return 0;
    }

    public void setLastCanvasId(int id) {}

    public int getNextPaintingId() {
        return 0;
    }

    public int getLastPaintingId() {
        return 0;
    }

    public void setLastPaintingId(int id) {}

    @Nullable
    public <T extends AbstractCanvasData> T getCanvasData(String canvasCode, @Nullable Class<T> type) {
        return (T) DummyCanvasData.createDummy();
    }

    public void registerCanvasData(String canvasCode, AbstractCanvasData canvasData) {

    }

    public void unregisterCanvasData(String canvasCode) {

    }

    public Tag serializeNBT() {
        CompoundTag compound = new CompoundTag();
        compound.putInt(NBT_TAG_LAST_CANVAS_ID, this.getLastCanvasId());
        compound.putInt(NBT_TAG_LAST_PAINTING_ID, this.getLastPaintingId());
        return compound;
    }

    public void deserializeNBT(Tag nbt) {
        this.setLastCanvasId(0);

        if (nbt.getType() == CompoundTag.TYPE) {
            CompoundTag castedNBT = (CompoundTag) nbt;
            this.setLastCanvasId(castedNBT.getInt(NBT_TAG_LAST_CANVAS_ID));
            this.setLastPaintingId(castedNBT.getInt(NBT_TAG_LAST_PAINTING_ID));
        }
    }
}