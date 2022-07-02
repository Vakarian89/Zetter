package me.dantaeusb.zetter.client.gui;

import me.dantaeusb.zetter.client.gui.painting.*;
import me.dantaeusb.zetter.client.gui.painting.tabs.AbstractTab;
import me.dantaeusb.zetter.client.gui.painting.tabs.ColorTab;
import me.dantaeusb.zetter.client.gui.painting.tabs.InventoryTab;
import me.dantaeusb.zetter.client.gui.painting.tabs.ParametersTab;
import me.dantaeusb.zetter.core.tools.Color;
import me.dantaeusb.zetter.menu.ArtistTableMenu;
import me.dantaeusb.zetter.menu.EaselContainerMenu;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import me.dantaeusb.zetter.menu.painting.tools.Brush;
import me.dantaeusb.zetter.menu.painting.tools.Bucket;
import me.dantaeusb.zetter.menu.painting.tools.Eyedropper;
import me.dantaeusb.zetter.menu.painting.tools.Pencil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class PaintingScreen extends AbstractContainerScreen<EaselContainerMenu> implements ContainerListener {
    // This is the resource location for the background image
    public static final ResourceLocation PAINTING_RESOURCE = new ResourceLocation("zetter", "textures/gui/painting-new.png");

    private final List<AbstractPaintingWidget> paintingWidgets = Lists.newArrayList();

    private HashMap<TabsWidget.Tab, AbstractTab> tabs = new HashMap<>();

    private ToolsWidget toolsWidget;
    private TabsWidget tabsWidget;
    private CanvasWidget canvasWidget;
    private PaletteWidget paletteWidget;
    private HelpWidget helpWidget;

    private final Player player;

    public PaintingScreen(EaselContainerMenu paintingContainer, Inventory playerInventory, Component title) {
        super(paintingContainer, playerInventory, title);

        this.player = playerInventory.player;

        this.imageWidth = 206;
        this.imageHeight = 238;
    }

    @Override
    protected void init() {
        super.init();

        final int CANVAS_POSITION_X = 39;
        final int CANVAS_POSITION_Y = 9;

        final int TOOLS_POSITION_X = 4;
        final int TOOLS_POSITION_Y = 4;

        final int TABS_POSITION_X = 4;
        final int TABS_POSITION_Y = 142;

        final int PALETTE_POSITION_X = 175;
        final int PALETTE_POSITION_Y = 38;

        final int HELP_POSITION_X = 199;
        final int HELP_POSITION_Y = 0;

        // Widgets

        this.canvasWidget = new CanvasWidget(this, this.getGuiLeft() + CANVAS_POSITION_X, this.getGuiTop() + CANVAS_POSITION_Y);
        this.paletteWidget = new PaletteWidget(this, this.getGuiLeft() + PALETTE_POSITION_X, this.getGuiTop() + PALETTE_POSITION_Y);
        this.toolsWidget = new ToolsWidget(this, this.getGuiLeft() + TOOLS_POSITION_X, this.getGuiTop() + TOOLS_POSITION_Y);
        this.tabsWidget = new TabsWidget(this, this.getGuiLeft() + TABS_POSITION_X, this.getGuiTop() + TABS_POSITION_Y);
        this.helpWidget = new HelpWidget(this, this.getGuiLeft() + HELP_POSITION_X, this.getGuiTop() + HELP_POSITION_Y);

        this.addPaintingWidget(this.canvasWidget);
        this.addPaintingWidget(this.paletteWidget);
        this.addPaintingWidget(this.toolsWidget);
        this.addPaintingWidget(this.tabsWidget);
        this.addPaintingWidget(this.helpWidget);

        // Tabs

        this.tabs.put(TabsWidget.Tab.COLOR, new ColorTab(this, this.getGuiLeft(), this.getGuiTop()));
        this.tabs.put(TabsWidget.Tab.PARAMETERS, new ParametersTab(this, this.getGuiLeft(), this.getGuiTop()));
        this.tabs.put(TabsWidget.Tab.INVENTORY, new InventoryTab(this, this.getGuiLeft(), this.getGuiTop()));

        // Other

        this.getMenu().setFirstLoadNotification(this::firstLoadUpdate);

        this.minecraft.keyboardHandler.setSendRepeatsToGui(true);

        this.menu.addSlotListener(this);
    }

    public void addPaintingWidget(AbstractPaintingWidget widget) {
        this.paintingWidgets.add(widget);
        this.addWidget(widget);
    }

    /**
     * Make add widget "public" so painting widgets can pipe their components to this screen
     */
    public <T extends GuiEventListener & NarratableEntry> void pipeWidget(T widget) {
        this.addWidget(widget);
    }

    public void removed() {
        super.removed();
    }

    /**
     * Expose some methods for widgets
     */

    public Font getFont() {
        return this.font;
    }

    /**
     * Get currently selected tab
     * @return
     */
    public AbstractTab getCurrentTab() {
        return this.tabs.get(this.getMenu().getCurrentTab());
    }

    public int getColorAt(int pixelIndex) {
        return this.menu.getCanvasData().getColorAt(pixelIndex);
    }

    public void firstLoadUpdate() {
        this.updateSlidersWithCurrentColor();
    }


    public void updateSlidersWithCurrentColor() {
        //this.slidersWidget.updateSlidersWithCurrentColor();
    }

    @Override
    public void render(PoseStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(matrixStack);
        super.render(matrixStack, mouseX, mouseY, partialTicks);
        this.renderTooltip(matrixStack, mouseX, mouseY);
    }

    @Override
    public void containerTick() {
        super.containerTick();

        this.getMenu().tick();
    }

    @Override
    protected void renderBg(PoseStack matrixStack, float partialTicks, int x, int y) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, PAINTING_RESOURCE);

        this.blit(matrixStack, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        this.toolsWidget.render(matrixStack);
        this.tabsWidget.render(matrixStack);
        this.canvasWidget.render(matrixStack, x, y, partialTicks);
        this.paletteWidget.render(matrixStack);
        this.helpWidget.render(matrixStack, x, y, partialTicks);

        this.getCurrentTab().render(matrixStack, x, y, partialTicks);
        // @todo: If color code goes not last, it may stop others from drawing. Is there an exception maybe?
    }

    @Override
    protected void renderTooltip(PoseStack matrixStack, int x, int y) {

        super.renderTooltip(matrixStack, x, y);

        for (AbstractPaintingWidget widget : this.paintingWidgets) {
            if (widget.isMouseOver(x, y)) {
                Component tooltip = widget.getTooltip(x, y);

                if (tooltip != null) {
                    this.renderTooltip(matrixStack, tooltip, x, y);
                }
            }
        }
    }

    /**
     * @param matrixStack
     * @param mouseX
     * @param mouseY
     */
    @Override
    protected void renderLabels(PoseStack matrixStack, int mouseX, int mouseY) {
        //final int LABEL_XPOS = 5;
        //final int LABEL_YPOS = 5;
        //this.font.draw(matrixStack, this.title, LABEL_XPOS, LABEL_YPOS, Color.darkGray.getRGB());

        final int FONT_Y_SPACING = 12;
        final int TAB_LABEL_XPOS = EaselContainerMenu.PLAYER_INVENTORY_XPOS;
        final int TAB_LABEL_YPOS = EaselContainerMenu.PLAYER_INVENTORY_YPOS - FONT_Y_SPACING;

        // draw the label for the player inventory slots
        this.font.draw(matrixStack, this.getMenu().getCurrentTab().translatableComponent,
                TAB_LABEL_XPOS, TAB_LABEL_YPOS, Color.darkGray.getRGB());
    }

    /**
     * Cancel closing screen when pressing "E", handle input properly
     * @param keyCode
     * @param scanCode
     * @param modifiers
     * @return
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE:
                this.minecraft.player.closeContainer();
                return true;
            case Pencil.HOTKEY:
                this.getMenu().setCurrentTool(Pencil.CODE);
                return true;
            case Brush.HOTKEY:
                this.getMenu().setCurrentTool(Brush.CODE);
                return true;
            case Eyedropper.HOTKEY:
                this.getMenu().setCurrentTool(Eyedropper.CODE);
                return true;
            case Bucket.HOTKEY:
                this.getMenu().setCurrentTool(Bucket.CODE);
                return true;
            default:
                return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    /**
     * Unfortunately this event is not passed to children
     * @param mouseX
     * @param mouseY
     * @param button
     * @param dragX
     * @param dragY
     * @return
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        this.canvasWidget.mouseDragged(mouseX, mouseY, button, dragX, dragY);

        this.getCurrentTab().mouseDragged(mouseX, mouseY, button, dragX, dragY);

        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /**
     * This one not passed when out of widget bounds but we need to track this event to release slider/pencil
     * @param mouseX
     * @param mouseY
     * @param button
     * @return
     */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.canvasWidget.mouseReleased(mouseX, mouseY, button);

        this.getCurrentTab().mouseReleased(mouseX, mouseY, button);

        return super.mouseReleased(mouseX, mouseY, button);
    }

    public Player getPlayer() {
        return this.player;
    }

    @Override
    public void dataChanged(AbstractContainerMenu p_150524_, int p_150525_, int p_150526_) {

    }

    /**
     * Sends the contents of an inventory slot to the client-side Container. This doesn't have to match the actual
     * contents of that slot.
     */
    public void slotChanged(AbstractContainerMenu containerToSend, int slotInd, ItemStack stack) {

    }

    /**
     * Helpers
     */

    /**
     * @todo: use this.isPointInRegion
     *
     * @param x
     * @param y
     * @param xSize
     * @param ySize
     * @param mouseX
     * @param mouseY
     * @return
     */
    // Returns true if the given x,y coordinates are within the given rectangle
    public static boolean isInRect(int x, int y, int xSize, int ySize, final int mouseX, final int mouseY){
        return ((mouseX >= x && mouseX <= x+xSize) && (mouseY >= y && mouseY <= y+ySize));
    }
}