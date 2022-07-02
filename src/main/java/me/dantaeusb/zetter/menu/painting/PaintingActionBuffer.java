package me.dantaeusb.zetter.menu.painting;

import me.dantaeusb.zetter.menu.painting.parameters.AbstractToolParameter;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.UUID;

public class PaintingActionBuffer {
    private static final int MAX_FRAMES = 100;
    private static final long MAX_TIME = 5000L;
    private static final long MAX_INACTIVE_TIME = 1000L;

    public final UUID actionId;

    public final UUID authorId;
    public final String toolCode;

    public final HashMap<String, AbstractToolParameter> parameters;
    public final Long startTime;

    /**
     * 1 byte -- meta reserved
     * 2 bytes -- time offset (up to 32s)
     * 4+4 bytes x and y as floats
     * 8 bytes -- extra
     */
    private static final int FRAME_SIZE = 1 + 2 + 8 + 8;
    public static final int BUFFER_SIZE = FRAME_SIZE * MAX_FRAMES;

    private final ByteBuffer buffer;

    private PaintingAction lastAction;

    private boolean committed = false;

    // Could be false only on client
    private boolean sent = false;

    private boolean canceled = false;

    public PaintingActionBuffer(UUID authorId, String toolCode, HashMap<String, AbstractToolParameter> parameters) {
        this(authorId, toolCode, parameters, System.currentTimeMillis(), ByteBuffer.allocateDirect(BUFFER_SIZE));
    }

    public PaintingActionBuffer(UUID authorId, String toolCode, HashMap<String, AbstractToolParameter> parameters, Long startTime, ByteBuffer buffer) {
        this.actionId = UUID.randomUUID(); // is it too much?
        this.authorId = authorId;
        this.toolCode = toolCode;
        this.parameters = parameters;
        this.startTime = startTime;

        this.buffer = buffer;
    }

    /**
     * Can we just add frame here
     *
     * @param authorId
     * @param toolCode
     * @param parameters
     * @return
     */
    public boolean canContinue(UUID authorId, String toolCode, HashMap<String, AbstractToolParameter> parameters) {
        return !this.shouldCommit() && this.isActionCompatible(authorId, toolCode, parameters);
    }

    /**
     * Is action we're trying to extend compatible with current action
     * @param authorId
     * @param toolCode
     * @param parameters
     * @return
     */
    public boolean isActionCompatible(UUID authorId, String toolCode, HashMap<String, AbstractToolParameter> parameters) {
        return  this.authorId == authorId &&
                this.toolCode.equals(toolCode);
    }

    /**
     * Should we stop writing in this action and start new
     * @return
     */
    public boolean shouldCommit() {
        final long currentTime = System.currentTimeMillis();

        // Action started long ago
        if (currentTime - this.startTime > MAX_TIME) {
            return true;
        }

        // Did nothing for a while
        if (this.lastAction != null && (currentTime - (this.startTime + this.lastAction.time)) > MAX_INACTIVE_TIME) {
            return true;
        }

        if (!this.buffer.hasRemaining()) {
            return true;
        }

        return false;
    }

    /**
     * Add action coordinates (like path of the brush)
     * Returns true if can add, false if it should be committed
     * and new action stack prepared
     *
     * @param posX
     * @param posY
     * @return
     */
    public boolean addFrame(float posX, float posY) {
        return this.addFrame(posX, posY, new byte[8]);
    }

    public boolean addFrame(float posX, float posY, byte[] extra) {
        if (this.committed) {
            return false;
        }

        if (this.shouldCommit()) {
            this.commit();
            return false;
        }

        final long currentTime = System.currentTimeMillis();
        final int passedTime = (int) (currentTime - this.startTime);

        final PaintingAction action = new PaintingAction(passedTime, posX, posY, extra);
        action.writeToBuffer(this.buffer);

        this.lastAction = action;

        return true;
    }

    public void commit() {
        this.committed = true;
    }

    public boolean isCommitted() {
        return this.committed;
    }

    public void setSent() {
        this.commit();

        this.sent = true;
    }

    public boolean isSent() {
        return this.sent;
    }

    public void undo() {
        this.commit();

        this.canceled = true;
    }

    public void redo() {
        this.commit();

        this.canceled = false;
    }

    public @Nullable PaintingAction getLastAction() {
        return this.lastAction;
    }

    /**
     * Prepare the data for sending - flip & lock
     * @return
     */
    public ByteBuffer getBufferData() {
        this.buffer.flip();
        return this.buffer.asReadOnlyBuffer();
    }

    public static class PaintingAction {
        public final byte meta;

        public final int time;
        public final float posX;
        public final float posY;
        public final byte[] extra;

        public PaintingAction(int time, float posX, float posY, @Nullable byte[] extra) {
            this.meta = (byte) 0x1;

            if (time > 0xFFFF) {
                throw new IllegalStateException("Time offset for action is to big");
            }

            this.time = time;
            this.posX = posX;
            this.posY = posY;

            byte[] extraResult = new byte[8];

            if (extra != null) {
                System.arraycopy(extra, 0, extraResult, 0, 8);
            }

            this.extra = extraResult;
        }

        public void writeToBuffer(ByteBuffer buffer) {
            // Meta
            buffer.put((byte) 0x1);

            // Time
            buffer.put((byte) (this.time & 0xFF));
            buffer.put((byte) ((this.time >> 8) & 0xFF));

            // Position
            buffer.putFloat(this.posX);
            buffer.putFloat(this.posY);

            int iterator = 0;
            // Other
            buffer.put(this.extra);
        }
    }
}
