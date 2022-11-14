package me.dantaeusb.zetter.entity.item.state;

import com.google.common.collect.Lists;
import me.dantaeusb.zetter.Zetter;
import me.dantaeusb.zetter.canvastracker.CanvasServerTracker;
import me.dantaeusb.zetter.client.renderer.CanvasRenderer;
import me.dantaeusb.zetter.core.EaselStateListener;
import me.dantaeusb.zetter.core.Helper;
import me.dantaeusb.zetter.core.ZetterNetwork;
import me.dantaeusb.zetter.entity.item.EaselEntity;
import me.dantaeusb.zetter.entity.item.state.representation.CanvasAction;
import me.dantaeusb.zetter.entity.item.state.representation.CanvasSnapshot;
import me.dantaeusb.zetter.network.packet.CCanvasActionPacket;
import me.dantaeusb.zetter.network.packet.CCanvasHistoryActionPacket;
import me.dantaeusb.zetter.network.packet.SCanvasHistoryActionPacket;
import me.dantaeusb.zetter.network.packet.SEaselStateSync;
import me.dantaeusb.zetter.painting.Tools;
import me.dantaeusb.zetter.painting.parameters.AbstractToolParameters;
import me.dantaeusb.zetter.storage.CanvasData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.*;
import java.util.List;

/**
 * This class is responsible for all canvas interactions for easels
 * It keeps painting states at different moments (snapshots)
 * action history, processes sync signals
 *
 * @todo: [LOW] Make it capability?
 */
public class EaselState {
    public static int SNAPSHOT_HISTORY_SIZE = 10;
    public static int ACTION_HISTORY_SIZE = 200;

    // Keep history and snapshots for 30 minutes of inactivity
    public static int FREEZE_TIMEOUT = (Zetter.DEBUG_MODE ? 1 : 30) * 60 * 1000;

    private final int MAX_ACTIONS_BEFORE_SNAPSHOT = ACTION_HISTORY_SIZE / SNAPSHOT_HISTORY_SIZE;

    public static int CLIENT_SNAPSHOT_HISTORY_SIZE = 100;

    private final EaselEntity easel;
    private List<EaselStateListener> listeners;

    private int tick;

    /**
     * Frozen means that easel was not updated for a while, and
     * there's no reason to keep history and snapshots
     */
    private boolean frozen = true;
    private long lastActivity = 0L;

    /*
     * State and networking
     */

    private final ArrayList<Player> players = new ArrayList<>();
    private final ArrayList<CanvasAction> actions = new ArrayList<>(ACTION_HISTORY_SIZE + 1);
    private final HashMap<UUID, UUID> playerLastSyncedAction = new HashMap<>();

    /*
     * Saved painting states
     * Server max memory allocation:
     * 16x: 64kb x SNAPSHOT_HISTORY_SIZE = 640kb
     * 64x: 1mb x SNAPSHOT_HISTORY_SIZE = 10mb
     * Client max memory allocation
     * 16x: 64kb x CLIENT_SNAPSHOT_HISTORY_SIZE = 6.4mb
     * 64x: 1mb x CLIENT_SNAPSHOT_HISTORY_SIZE = 100mb
     * Could take 100mb but only once on client
     * But client snapshots created more often
     * Cleaned up over time by freezing mechanism
     */
    private final ArrayList<CanvasSnapshot> snapshots;

    /**
     * This flag means that the history
     * traversing was made. For that reason,
     * on new action, wiping might be needed.
     */
    private boolean historyDirty = false;

    public EaselState(EaselEntity entity) {
        this.easel = entity;

        if (entity.getLevel().isClientSide()) {
            this.snapshots = new ArrayList<>(CLIENT_SNAPSHOT_HISTORY_SIZE + 1);
        } else {
            this.snapshots = new ArrayList<>(SNAPSHOT_HISTORY_SIZE + 1);
        }
    }

    // Activity listeners

    /**
     * Add user that will be updated with history
     * and new snapshot (history sync)
     * @param player
     */
    public void addPlayer(Player player) {
        this.players.add(player);

        this.unfreeze();

        if (!this.easel.getLevel().isClientSide()) {
            this.updateSnapshots();
            this.performHistorySyncForServerPlayer(player, true);
        }
    }

    /**
     * Remove user from history sync
     * (when menu is closed)
     * @param player
     */
    public void removePlayer(Player player) {
        this.players.remove(player);

        if (this.easel.getLevel().isClientSide()) {
            this.performHistorySyncClient(true);
            this.freeze();
        } else {
            this.playerLastSyncedAction.remove(player.getUUID());
        }
    }

    public void addListener(EaselStateListener listener) {
        if (this.listeners == null) {
            this.listeners = Lists.newArrayList();
        }

        this.listeners.add(listener);
    }

    public void removeListener(EaselStateListener listener) {
        this.listeners.remove(listener);
    }

    /**
     * Drops all buffers and removes all snapshots,
     * i.e. when canvas removed
     *
     * @todo: [HIGH] Create reset packet
     */
    public void reset() {
        this.actions.clear();
        this.snapshots.clear();
        this.playerLastSyncedAction.clear();

        if (this.getCanvasData() != null) {
            //this.syncPlayerEaselState();
        }

        this.onStateChanged();
    }

    // Freezing

    protected void freeze() {
        this.reset();
        this.frozen = true;
    }

    protected void unfreeze() {
        this.frozen = false;
        this.lastActivity = System.currentTimeMillis();
    }

    // Ticking

    /**
     * Tick periodically to clean up queue
     * and do network things
     */
    public void tick() {
        if (this.frozen) {
            return;
        }

        this.tick++;

        // No one used for a while
        if (this.players.isEmpty() && System.currentTimeMillis() - this.lastActivity > FREEZE_TIMEOUT) {
            this.freeze();
        }

        if (this.easel.getLevel().isClientSide()) {
            if (this.tick % 20 == 0) {
                this.performHistorySyncClient(false);
            }
        } else {
            // No need to tick if no one's using
            if (this.players.size() == 0) {
                return;
            }

            // Every 10 seconds check if we need a snapshot
            if (this.tick % 200 == 0) {
                this.updateSnapshots();
            }
            // Every second send sync
            if (this.tick % 20 == 0) {
                this.performHistorySyncServer();
            }
        }
    }

    /**
     * Get canvas from canvas holder in container
     * @return
     */
    private CanvasData getCanvasData() {
        if (this.easel.getEaselContainer().getCanvas() == null) {
            return null;
        }

        return this.easel.getEaselContainer().getCanvas().data;
    }

    /**
     * Get canvas code from canvas holder in container
     * @return
     */
    private String getCanvasCode() {
        if (this.easel.getEaselContainer().getCanvas() == null) {
            return null;
        }

        return this.easel.getEaselContainer().getCanvas().code;
    }

    /*
     * Actions
     */

    /**
     * Apply current tool at certain position and record action if successful
     *
     * Client-only
     * @param posX
     * @param posY
     */
    public void useTool(UUID playerId, Tools tool, float posX, float posY, int color, AbstractToolParameters parameters) {
        ItemStack paletteStack = this.easel.getEaselContainer().getPaletteStack();

        // No palette or no paints left
        if (paletteStack.isEmpty() || paletteStack.getDamageValue() >= paletteStack.getMaxDamage() - 1) {
            return;
        }

        if (this.getCanvasData() == null) {
            return;
        }

        CanvasAction lastAction = this.getLastActionOfCanceledState(false);

        Float lastX = null, lastY = null;

        if (lastAction != null && lastAction.tool == tool && !lastAction.isCommitted()) {
            final CanvasAction.CanvasSubAction lastSubAction = lastAction.getLastAction();

            if (lastSubAction != null) {
                lastX = lastSubAction.posX;
                lastY = lastSubAction.posY;
            }
        }

        if (tool.getTool().shouldAddAction(this.getCanvasData(), parameters, posX, posY, lastX, lastY)) {
            this.wipeCanceledActionsAndDiscardSnapshots();

            int damage = tool.getTool().apply(this.getCanvasData(), parameters, color, posX, posY);
            this.unfreeze();

            if (tool.getTool().publishable()) {
                this.recordAction(playerId, tool, color, parameters, posX, posY);
            }

            CanvasRenderer.getInstance().updateCanvasTexture(this.getCanvasCode(), this.getCanvasData());

            this.easel.getEaselContainer().damagePalette(damage);
        }
    }

    /**
     * When new action is created, history becomes non-linear
     * All canceled actions get discarded
     *
     * Client and server
     */
    private void wipeCanceledActionsAndDiscardSnapshots() {
        if (!this.historyDirty) {
            return;
        }

        boolean foundCanceled = false;

        ListIterator<CanvasAction> actionsIterator = this.getActionsEndIterator();
        long lastCanceled = System.currentTimeMillis();

        while(actionsIterator.hasPrevious()) {
            CanvasAction paintingActionBuffer = actionsIterator.previous();

            if (paintingActionBuffer.isCanceled()) {
                foundCanceled = true;
                lastCanceled = paintingActionBuffer.getStartTime();
                actionsIterator.remove();
            }
        }

        if (!foundCanceled) {
            this.historyDirty = false;
            return;
        }

        ListIterator<CanvasSnapshot> snapshotIterator = this.getSnapshotsEndIterator();

        while(snapshotIterator.hasPrevious()) {
            CanvasSnapshot snapshot = snapshotIterator.previous();

            if (snapshot.timestamp > lastCanceled) {
                snapshotIterator.remove();
            } else {
                break;
            }
        }

        this.historyDirty = false;
    }

    /**
     * Write down the action that was successfully performed -- either to current buffer if possible,
     * or to the new buffer if cannot extend
     *
     * Client-only
     * @param playerId
     * @param tool
     * @param color
     * @param parameters
     * @param posX
     * @param posY
     */
    private void recordAction(UUID playerId, Tools tool, int color, AbstractToolParameters parameters, float posX, float posY) {
        CanvasAction lastAction = this.getLastAction();

        if (lastAction == null || lastAction.isCommitted()) {
            lastAction = this.createAction(playerId, tool, color, parameters);
        } else if (!lastAction.canContinue(playerId, tool, color, parameters)) {
            lastAction.commit();
            lastAction = this.createAction(playerId, tool, color, parameters);
        }

        lastAction.addFrame(posX, posY);
    }

    /**
     * Create new action buffer, if we can't write to existing, and
     * submit previous to the server
     *
     * Client-only
     * @param playerId
     * @param tool
     * @param color
     * @param parameters
     * @return
     */
    private CanvasAction createAction(UUID playerId, Tools tool, int color, AbstractToolParameters parameters) {
        final CanvasAction lastAction = this.getLastAction();

        if (!tool.getTool().publishable()) {
            throw new IllegalStateException("Cannot create non-publishable action");
        }

        if (lastAction != null && !lastAction.isCommitted()) {
            lastAction.commit();
        }

        final CanvasAction newAction = new CanvasAction(playerId, tool, color, parameters);
        this.actions.add(newAction);

        this.onStateChanged();

        return newAction;
    }

    /*
     * Action state
     */

    /**
     * Update "canceled" state of an action and all actions before or after (depends on state)
     * notify network and history about that change and update textures.
     *
     * @param tillAction
     * @param cancel
     * @return
     */
    private boolean applyHistoryTraversing(CanvasAction tillAction, boolean cancel) {
        boolean changedState = false;

        // Cancel all after, un-cancel all before
        if (cancel) {
            ListIterator<CanvasAction> actionsIterator = this.getActionsEndIterator();

            while(actionsIterator.hasPrevious()) {
                CanvasAction currentAction = actionsIterator.previous();

                if (!currentAction.isCanceled()) {
                    currentAction.setCanceled(true);
                    changedState = true;
                }

                if (currentAction.uuid.equals(tillAction.uuid)) {
                    break;
                }
            }
        } else {
            ListIterator<CanvasAction> actionsIterator = this.actions.listIterator();

            while(actionsIterator.hasNext()) {
                CanvasAction currentAction = actionsIterator.next();

                if (currentAction.isCanceled()) {
                    currentAction.setCanceled(false);
                    changedState = true;
                }

                if (currentAction.uuid.equals(tillAction.uuid)) {
                    break;
                }
            }
        }

        if (!changedState) {
            return false;
        }

        this.restoreSinceSnapshot();
        this.onStateChanged();

        // If tillAction is not sent, just checking flag will be enough, it will be sent with proper canceled status
        if (this.easel.getLevel().isClientSide()) {
            if (tillAction.isSent()) {
                CCanvasHistoryActionPacket historyPacket = new CCanvasHistoryActionPacket(this.easel.getId(), tillAction.uuid, cancel);
                ZetterNetwork.simpleChannel.sendToServer(historyPacket);
            }
        } else {
            for (Player player : this.players) {
                // If not sent any actions, it will send canceled already
                if (this.playerLastSyncedAction.containsKey(player.getUUID())) {
                    UUID lastSyncedActionUuid = this.playerLastSyncedAction.get(player.getUUID());
                    ListIterator<CanvasAction> actionsIterator = this.getActionsEndIterator();
                    boolean found = false;

                    // If we sent action (found last sent action before tillAction), then we need to send history packet
                    while(actionsIterator.hasPrevious()) {
                        CanvasAction action = actionsIterator.previous();

                        if (lastSyncedActionUuid.equals(tillAction.uuid)) {
                            found = true;
                            break;
                        }

                        if (action.uuid.equals(tillAction.uuid)) {
                            break;
                        }
                    }

                    if (found) {
                        SCanvasHistoryActionPacket historyPacket = new SCanvasHistoryActionPacket(this.easel.getId(), tillAction.uuid, cancel);
                        ZetterNetwork.simpleChannel.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), historyPacket);
                    }
                }
            }
        }

        this.historyDirty = true;
        this.unfreeze();

        return true;
    }

    /**
     * @param canceled
     * @return
     */
    private @Nullable CanvasAction getLastActionOfCanceledState(boolean canceled) {
        return this.getLastActionOfCanceledState(canceled, null);
    }

    private @Nullable CanvasAction getLastActionOfCanceledState(boolean canceled, @Nullable UUID playerId) {
        return this.getActionOfCanceledState(canceled, true, playerId);
    }

    /**
     * @param canceled
     * @return
     */
    private @Nullable CanvasAction getFirstActionOfCanceledState(boolean canceled) {
        return this.getFirstActionOfCanceledState(canceled, null);
    }

    private @Nullable CanvasAction getFirstActionOfCanceledState(boolean canceled, @Nullable UUID playerId) {
        return this.getActionOfCanceledState(canceled, false, playerId);
    }

    /**
     * Get the last action of canceled or non-canceled state
     * Used for undoing and redoing
     * @param canceled
     * @param fromEnd
     * @param playerId
     * @return
     */
    private @Nullable CanvasAction getActionOfCanceledState(boolean canceled, boolean fromEnd, @Nullable UUID playerId) {
        CanvasAction stateAction = null;

        if (fromEnd) {
            ListIterator<CanvasAction> actionsIterator = this.getActionsEndIterator();
            while(actionsIterator.hasPrevious()) {
                CanvasAction currentAction = actionsIterator.previous();

                final boolean differentPlayer = playerId != null && currentAction.authorId != playerId;
                if (differentPlayer || currentAction.isCanceled() != canceled) {
                    continue;
                }

                stateAction = currentAction;
                break;
            }
        } else {
            ListIterator<CanvasAction> actionsIterator = this.actions.listIterator();
            while(actionsIterator.hasNext()) {
                CanvasAction currentAction = actionsIterator.next();

                final boolean differentPlayer = playerId != null && currentAction.authorId != playerId;
                if (differentPlayer || currentAction.isCanceled() != canceled) {
                    continue;
                }

                stateAction = currentAction;
                break;
            }
        }

        return stateAction;
    }

    /*
     * State: undo-redo
     *
     * Previously we were using player id to remove only own actions
     * But now it's removed since it's excessive and super confusing.
     * It's still possible though but hard to work with.
     */

    public boolean canUndo() {
        return this.getLastActionOfCanceledState(false) != null;
    }

    public boolean canRedo() {
        return this.getLastActionOfCanceledState(true) != null;
    }

    public boolean undo() {
        CanvasAction lastNonCanceledAction = this.getLastActionOfCanceledState(false);

        if (lastNonCanceledAction == null) {
            return false;
        }

        return this.undo(lastNonCanceledAction);
    }

    public boolean undo(UUID tillActionUuid) {
        @Nullable CanvasAction tillAction = this.findAction(tillActionUuid);

        if (tillAction != null) {
            return this.undo(tillAction);
        }

        return false;
    }

    public boolean undo(CanvasAction tillAction) {
        return this.applyHistoryTraversing(tillAction, true);
    }

    public boolean redo() {
        CanvasAction firstCanceledAction = this.getFirstActionOfCanceledState(true);

        if (firstCanceledAction == null) {
            return false;
        }

        return this.redo(firstCanceledAction);
    }

    public boolean redo(UUID tillActionUuid) {
        @Nullable CanvasAction tillAction = this.findAction(tillActionUuid);

        if (tillAction != null) {
            return this.redo(tillAction);
        }

        return false;
    }

    public boolean redo(CanvasAction tillAction) {
        return this.applyHistoryTraversing(tillAction, false);
    }

    private @Nullable CanvasAction findAction(UUID actionUuid) {
        ListIterator<CanvasAction> actionsIterator = this.getActionsEndIterator();
        @Nullable CanvasAction tillAction = null;

        while(actionsIterator.hasPrevious()) {
            CanvasAction currentAction = actionsIterator.previous();

            if (currentAction.uuid.equals(actionUuid)) {
                tillAction = currentAction;
                break;
            }
        }

        return tillAction;
    }

    /*
     * Snapshots
     */

    /**
     * This action will get the latest available snapshot for the current action set
     * (might look up down the snapshot deque if the actions are canceled)
     * From that snapshot, canvas state will be restored and actions applied
     * in order
     *
     * @todo: [HIGH] When several players editing, last action can be non-canceled,
     * but some previous by another author are
     */
    public void restoreSinceSnapshot() {
        CanvasAction firstCanceledAction = this.getFirstActionOfCanceledState(true);
        CanvasSnapshot latestSnapshot;

        int latency = 0;

        if (this.easel.getLevel().isClientSide()) {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            latency = Math.max(500, connection.getPlayerInfo(Minecraft.getInstance().player.getUUID()).getLatency()) * 2;
        }

        if (firstCanceledAction != null) {
            // =
            latestSnapshot = this.getSnapshotBefore(firstCanceledAction.getStartTime() - latency);
        } else {
            // Nothing canceled - just use last snapshot!
            latestSnapshot = this.getLastSnapshot();
        }

        if (latestSnapshot == null) {
            Zetter.LOG.error("Unable to find snapshot before first canceled action");
            return;
        }

        this.applySnapshot(latestSnapshot);
        ListIterator<CanvasAction> actionBufferIterator = this.getActionsEndIterator();

        boolean foundCanceled = firstCanceledAction == null;
        boolean foundLastBeforeSnapshot = false;

        // Go back, find first canceled, then reverse iterator, then continue applying action
        while(actionBufferIterator.hasPrevious()) {
            CanvasAction action = actionBufferIterator.previous();

            // Start looking when found first canceled action if any
            if (!foundCanceled) {
                foundCanceled = action.uuid.equals(firstCanceledAction.uuid);
            }

            // And first action committed before snapshot
            if (!foundLastBeforeSnapshot) {
                foundLastBeforeSnapshot = action.isCommitted() && action.getCommitTime() < latestSnapshot.timestamp;
            }

            // When found or reached the end
            if (
                (foundCanceled && foundLastBeforeSnapshot) || !actionBufferIterator.hasPrevious()
            ) {
                // Turn around and start applying
                while(actionBufferIterator.hasNext()) {
                    action = actionBufferIterator.next();

                    // We apply non-committed and non-sent always, as server cannot have idea of the new actions that were not pushed
                    // (except if canceled, but it should never happen)
                    if (
                        !action.isCanceled() && ( // If not canceled and
                            !action.isCommitted()  // not committed
                            || !action.isSent() // or not sent
                            || action.getCommitTime() > latestSnapshot.timestamp - latency // or committed before snapshot was created
                        )
                    ) {
                        this.applyAction(action, false);
                    } else {
                        Zetter.LOG.debug("Ignoring action " + action.uuid);
                    }
                }

                break;
            }
        }

        if (this.easel.getLevel().isClientSide()) {
            CanvasRenderer.getInstance().updateCanvasTexture(this.getCanvasCode(), this.getCanvasData());
        }

        if (Zetter.DEBUG_MODE && Zetter.DEBUG_CLIENT) {
            Zetter.LOG.debug("Restored since snapshot");
        }

        this.markDesync();
    }

    /**
     * Get a snapshot that was made before certain timestamp
     * @param timestamp
     * @return
     */
    protected @Nullable CanvasSnapshot getSnapshotBefore(Long timestamp) {
        ListIterator<CanvasSnapshot> snapshotIterator = this.getSnapshotsEndIterator();

        while(snapshotIterator.hasPrevious()) {
            CanvasSnapshot snapshot = snapshotIterator.previous();

            if (snapshot.timestamp < timestamp) {
                return snapshot;
            }
        }

        return null;
    }

    /**
     * Apply color data from snapshot
     * @param snapshot
     */
    protected void applySnapshot(CanvasSnapshot snapshot) {
        this.getCanvasData().updateColorData(snapshot.colors);
    }

    /**
     * Check if we need to create a new snapshot
     * clean up older snapshots and make new if needed
     *
     * Server-only
     */
    protected void updateSnapshots() {
        if (this.getCanvasData() != null && this.needSnapshot()) {
            this.cleanupSnapshotHistory();
            this.makeSnapshot();
        }
    }

    /**
     * Should we create a new snapshot
     * we should if players made more than
     * MAX_ACTIONS_BEFORE_SNAPSHOT since last snapshot was made
     *
     * @todo: [HIGH] Add snapshots by time
     *
     * Server-only
     * @return
     */
    private boolean needSnapshot() {
        if (this.snapshots.isEmpty()) {
            return true;
        }

        CanvasSnapshot lastSnapshot = this.getLastSnapshot();
        assert lastSnapshot != null;

        int actionsSinceSnapshot = 0;

        ListIterator<CanvasAction> actionsIterator = this.getActionsEndIterator();

        while(actionsIterator.hasPrevious()) {
            CanvasAction paintingActionBuffer = actionsIterator.previous();

            if (paintingActionBuffer.getStartTime() < lastSnapshot.timestamp) {
                break;
            }

            actionsSinceSnapshot += paintingActionBuffer.countActions();
        }

        return actionsSinceSnapshot >= MAX_ACTIONS_BEFORE_SNAPSHOT;
    }

    private void makeSnapshot() {
        assert this.getCanvasData() != null;
        this.snapshots.add(CanvasSnapshot.createServerSnapshot(this.getCanvasData().getColorData()));
    }

    /**
     * Removes all older snapshots and history
     */
    private void cleanupSnapshotHistory() {
        int maxSize = SNAPSHOT_HISTORY_SIZE;

        if (this.easel.level.isClientSide) {
            maxSize = CLIENT_SNAPSHOT_HISTORY_SIZE;
        }

        if (this.snapshots.size() > maxSize) {
            int i = 0;
            ListIterator<CanvasSnapshot> canvasSnapshotIterator = this.snapshots.listIterator();

            while(canvasSnapshotIterator.hasPrevious()) {
                canvasSnapshotIterator.previous();

                if (i++ > maxSize - 1) {
                    canvasSnapshotIterator.remove();
                }
            }
        }
    }

    /*
     *
     * Networking
     *
     */

    /**
     * Checks and sends action buffer on client
     * Called locally time to time and when menu is closed to
     * force sync from client to server
     *
     * Client-only
     */
    public void performHistorySyncClient(boolean forceCommit) {
        final Queue<CanvasAction> unsentActions = new ArrayDeque<>();
        ListIterator<CanvasAction> actionsIterator = this.getActionsEndIterator();

        while(actionsIterator.hasPrevious()) {
            CanvasAction paintingActionBuffer = actionsIterator.previous();

            if (!paintingActionBuffer.isCommitted()) {
                if (forceCommit || paintingActionBuffer.shouldCommit()) {
                    paintingActionBuffer.commit();

                    this.onStateChanged();
                } else {
                    continue;
                }
            }

            if (paintingActionBuffer.isSent()) {
                break;
            }

            unsentActions.add(paintingActionBuffer);
        }

        if (!unsentActions.isEmpty()) {
            CCanvasActionPacket paintingFrameBufferPacket = new CCanvasActionPacket(this.easel.getId(), unsentActions);
            ZetterNetwork.simpleChannel.sendToServer(paintingFrameBufferPacket);

            for (CanvasAction unsentAction : unsentActions) {
                unsentAction.setSent();
            }
        }
    }

    /**
     * Send sync packet to player: latest snapshot and new
     * history created since last sync
     */
    public void performHistorySyncServer() {
        for (Player player : this.players) {
            this.performHistorySyncForServerPlayer(player, false);
        }
    }

    /**
     * Send player a history update packet,
     * including history actions and latest
     * verified snapshot
     *
     * @todo: [LOW] Force is a workaround for first snapshot sync
     *
     * @param player
     */
    public void performHistorySyncForServerPlayer(Player player, boolean force) {
        ArrayList<CanvasAction> unsyncedActions = this.getUnsyncedActionsForPlayer(player);

        if (unsyncedActions == null || unsyncedActions.isEmpty()) {
            if (!force) {
                return;
            }
        } else {
            this.playerLastSyncedAction.put(player.getUUID(), unsyncedActions.get(unsyncedActions.size() - 1).uuid);
        }

        // @todo: [HIGH] Just sync all snapshots
        CanvasAction lastCanceledAction = this.getLastActionOfCanceledState(true);
        CanvasSnapshot lastSnapshot;

        if (lastCanceledAction != null) {
            lastSnapshot = this.getSnapshotBefore(lastCanceledAction.getStartTime());
        } else {
            lastSnapshot = this.getLastSnapshot();
        }

        SEaselStateSync syncMessage = new SEaselStateSync(
                this.easel.getId(), this.getCanvasCode(), lastSnapshot, unsyncedActions
        );

        ZetterNetwork.simpleChannel.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), syncMessage);

        if (Zetter.DEBUG_MODE && Zetter.DEBUG_SERVER) {
            Zetter.LOG.debug("Sent history to player " + player.getUUID());
        }
    }

    /**
     * Get list of actions in history that was not synced with the
     * player since the last sync, to keep history consistent between
     * players
     * @param player
     * @return
     */
    private @Nullable ArrayList<CanvasAction> getUnsyncedActionsForPlayer(Player player) {
        // this.playerLastSyncedAction.put(player.getUUID(), this.actions.getLast().uuid);

        // Sync whole history if we haven't synced any actions before
        if (!this.playerLastSyncedAction.containsKey(player.getUUID())) {
            return this.actions;
        }

        final UUID lastSyncedActionUuid = this.playerLastSyncedAction.get(player.getUUID());
        final ListIterator<CanvasAction> actionBufferIterator = this.getActionsEndIterator();

        ArrayList<CanvasAction> unsyncedActions = new ArrayList<>();

        while(actionBufferIterator.hasPrevious()) {
            CanvasAction action = actionBufferIterator.previous();
            // Because we're using reverse iterator, we add to the front
            unsyncedActions.add(action);

            if (action.uuid == lastSyncedActionUuid) {
                break;
            }
        }

        Collections.reverse(unsyncedActions);

        if (unsyncedActions.size() == 1 && unsyncedActions.get(0).uuid == lastSyncedActionUuid) {
            return null;
        }

        return unsyncedActions;
    }

    /**
     * Called from network - process player's work
     * And drop canceled actions on server if needed
     *
     * Server-only
     * @param action
     */
    public void processActionServer(CanvasAction action) {
        if (!action.isCanceled()) {
            this.applyAction(action, true);
            this.wipeCanceledActionsAndDiscardSnapshots();
        }

        // @todo: [MED] Properly sort using iterators
        this.actions.add(action);

        this.markDesync();

        if (Zetter.DEBUG_MODE && Zetter.DEBUG_SERVER) {
            //Zetter.LOG.debug("Processed actions from player " + action.authorId.toString());
        }
    }

    /**
     * Apply action from history or network - don't save it
     * @param action
     */
    public void applyAction(CanvasAction action, boolean doDamage) {
        action.getSubActionStream().forEach((CanvasAction.CanvasSubAction subAction) -> {
            // Apply subAction directly
            int damage = action.tool.getTool().apply(
                    this.getCanvasData(),
                    action.parameters,
                    action.color,
                    subAction.posX,
                    subAction.posY
            );

            if (doDamage) {
                this.easel.getEaselContainer().damagePalette(damage);
            }
        });

        this.unfreeze();
    }

    /**
     * Add all newest pixels to the canvas when syncing to keep recent player's changes
     * Additionally writes state to recover history
     *
     * Client-only
     * @param canvasCode
     * @param snapshot
     * @param actions
     */
    public void processHistorySyncClient(String canvasCode, CanvasSnapshot snapshot, ArrayList<CanvasAction> actions) {
        if (!canvasCode.equals(this.getCanvasCode())) {
            Zetter.LOG.error("Different canvas code in history sync packet, ignoring");
            return;
        }

        if (this.getLastSnapshot() == null) {
            this.snapshots.add(snapshot);
        } else if (!this.getLastSnapshot().uuid.equals(snapshot.uuid)) {
            if (this.snapshots.size() >= SNAPSHOT_HISTORY_SIZE) {
                this.snapshots.remove(0);
            }

            this.snapshots.add(snapshot);

            if (Zetter.DEBUG_MODE && Zetter.DEBUG_CLIENT) {
                Zetter.LOG.debug("Processed server snapshot");
            }
        }

        if (actions == null || actions.isEmpty()) {
            this.restoreSinceSnapshot();

            if (Zetter.DEBUG_MODE && Zetter.DEBUG_CLIENT) {
                Zetter.LOG.debug("Processed actions sync from server");
            }

            return;
        }

        Iterator<CanvasAction> unsyncedIterator = actions.iterator();
        ListIterator<CanvasAction> actionsIterator = this.actions.listIterator();

        @Nullable CanvasAction clientAction = actionsIterator.hasNext() ? actionsIterator.next() : null;

        int fastForwards = 0;

        do {
            CanvasAction unsyncedAction = unsyncedIterator.next();

            // If there's no client actions saved found after synced action, just
            // add all unsynced
            if (clientAction == null) {
                this.actions.add(unsyncedAction);

                continue;
            }

            // Fast-forward client actions to the one that is made at the same
            // time or later than the first unsynced action
            if (unsyncedAction.getStartTime() > clientAction.getStartTime()) {
                if (++fastForwards > 1) {
                    Zetter.LOG.warn("Fast-forwarding actions without mark sync! Some actions were lost?");
                }

                while (actionsIterator.hasNext()) {
                    if (clientAction.getStartTime() >= unsyncedAction.getStartTime()) {
                        break;
                    }

                    clientAction = actionsIterator.next();
                }
            }

            // Mark action as sync, because we received confirmation from server
            // That server has this action
            if (clientAction.uuid.equals(unsyncedAction.uuid)) {
                clientAction.setSync();
                clientAction = actionsIterator.hasNext() ? actionsIterator.next() : null;
            } else {
                if (Zetter.DEBUG_MODE) {
                    if (this.findAction(unsyncedAction.uuid) != null) {
                        Zetter.LOG.warn("Duplicating action");
                    }
                }

                actionsIterator.add(unsyncedAction);
                clientAction = actionsIterator.hasNext() ? actionsIterator.next() : null;
            }
        } while (unsyncedIterator.hasNext());

        this.unfreeze();

        this.restoreSinceSnapshot();
        this.onStateChanged();

        if (Zetter.DEBUG_MODE && Zetter.DEBUG_CLIENT) {
            Zetter.LOG.debug("Processed actions sync from server");
        }
    }

    /**
     * Add all newest pixels to the canvas when syncing to keep recent player's changes
     * Weak snapshot is snapshot not made by server but sent as a canvas
     * update for all players in range. This is useful for quicker restoration
     * of the canvas when working with history on client.
     *
     * Client-only
     * @param canvasCode
     * @param canvasData
     * @param packetTimestamp
     */
    public void processWeakSnapshotClient(String canvasCode, CanvasData canvasData, long packetTimestamp) {
        //this.snapshots.add(CanvasSnapshot.createWeakSnapshot(canvasData.getColorData(), packetTimestamp));
        //this.restoreSinceSnapshot();

        if (Zetter.DEBUG_MODE && Zetter.DEBUG_CLIENT) {
            Zetter.LOG.debug("Processed weak snapshot");
        }
    }

    /**
     * Tell other players who are tracking canvas that canvas
     * is no longer up to date (tracker will decide when to sync)
     */
    private void markDesync() {
        if (!this.easel.getLevel().isClientSide()) {
            ((CanvasServerTracker) Helper.getWorldCanvasTracker(this.easel.getLevel())).markCanvasDesync(this.getCanvasCode());
        }
    }

    /**
     * Notify subscribers that history state was changed
     */
    protected void onStateChanged()
    {
        if (this.listeners != null) {
            for(EaselStateListener listener : this.listeners) {
                listener.stateChanged(this);
            }
        }
    }

    private ListIterator<CanvasAction> getActionsEndIterator() {
        if (this.actions.isEmpty()) {
            return this.actions.listIterator();
        } else {
            return this.actions.listIterator(this.actions.size());
        }
    }

    private @Nullable CanvasAction getLastAction() {
        if (this.actions.isEmpty()) {
            return null;
        } else {
            return this.actions.get(this.actions.size() - 1);
        }
    }

    private ListIterator<CanvasSnapshot> getSnapshotsEndIterator() {
        if (this.snapshots.isEmpty()) {
            return this.snapshots.listIterator();
        } else {
            return this.snapshots.listIterator(this.snapshots.size());
        }
    }

    private @Nullable CanvasSnapshot getLastSnapshot() {
        if (this.snapshots.isEmpty()) {
            return null;
        } else {
            return this.snapshots.get(this.snapshots.size() - 1);
        }
    }
}
