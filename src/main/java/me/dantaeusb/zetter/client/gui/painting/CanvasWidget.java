package me.dantaeusb.zetter.client.gui.painting;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import me.dantaeusb.zetter.client.gui.PaintingScreen;
import me.dantaeusb.zetter.core.Helper;
import me.dantaeusb.zetter.painting.parameters.AbstractToolParameters;
import me.dantaeusb.zetter.painting.parameters.SizeInterface;
import me.dantaeusb.zetter.painting.tools.AbstractTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Widget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

import static org.lwjgl.glfw.GLFW.*;

public class CanvasWidget extends AbstractPaintingWidget implements Widget {
    private static final int CANVAS_SCALE_FACTOR = 6;
    private static final int SIZE = 128;

    private boolean canvasDragging = false;

    private int canvasOffsetX = 0;
    private int canvasOffsetY = 0;

    public CanvasWidget(PaintingScreen parentScreen, int x, int y) {
        super(parentScreen, x, y, SIZE, SIZE, new TranslatableComponent("container.zetter.painting.canvas"));

        if (parentScreen.getMenu().getCanvasData() != null) {
            this.canvasOffsetX = (SIZE - (parentScreen.getMenu().getCanvasData().getWidth() * CANVAS_SCALE_FACTOR)) / 2;
            this.canvasOffsetY = (SIZE - (parentScreen.getMenu().getCanvasData().getHeight() * CANVAS_SCALE_FACTOR)) / 2;
        }
    }

    @Override
    public @Nullable
    Component getTooltip(int mouseX, int mouseY) {
        return null;
    }

    /**
     * @param mouseX
     * @param mouseY
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.isMouseOver(mouseX, mouseY)) {
            this.canvasDragging = true;
            this.handleCanvasInteraction(mouseX, mouseY);
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Drag-drawing
     *
     * @param mouseX
     * @param mouseY
     * @param button
     * @param dragX
     * @param dragY
     * @return
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.isMouseOver(mouseX, mouseY)) {
            if (this.canvasDragging) {
                this.handleCanvasInteraction(mouseX, mouseY);
                return true;
            }

            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /**
     * Stop drag-drawing
     *
     * @param mouseX
     * @param mouseY
     * @param button
     * @return
     */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.canvasDragging = false;

        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Apply used tool. Could apply outside the canvas!
     * It is for tool to decide if it's going to apply interaction
     *
     * @param mouseX
     * @param mouseY
     * @return
     */
    protected boolean handleCanvasInteraction(double mouseX, double mouseY) {
        final float canvasX = (float) ((mouseX - this.x - this.canvasOffsetX) / (float) CANVAS_SCALE_FACTOR);
        final float canvasY = (float) ((mouseY - this.y - this.canvasOffsetY) / (float) CANVAS_SCALE_FACTOR);

        this.parentScreen.getMenu().useTool(canvasX, canvasY);

        return true;
    }

    public void render(PoseStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, PaintingScreen.PAINTING_RESOURCE);

        if (!this.parentScreen.getMenu().isCanvasAvailable()) {
            GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().getWindow(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            return;
        }

        // A bit dumb but avoiding direct calls
        for (int i = 0; i < Helper.getResolution().getNumeric() * Helper.getResolution().getNumeric(); i++) {
            int canvasX = i % 16;
            int canvasY = i / 16;

            /**
             * @todo: better use canvas renderer because there's a texture ready to render
             */
            int color = this.parentScreen.getColorAt(i);
            int globalX = this.x + this.canvasOffsetX + canvasX * CANVAS_SCALE_FACTOR;
            int globalY = this.y + this.canvasOffsetY + canvasY * CANVAS_SCALE_FACTOR;

            fill(matrixStack, globalX, globalY, globalX + CANVAS_SCALE_FACTOR, globalY + CANVAS_SCALE_FACTOR, color);
        }

        if (    mouseX >= this.x
                && mouseY >= this.y
                && mouseX < this.x + this.width
                && mouseY < this.y + this.height
        ) {
            GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().getWindow(), GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
            double canvasX = (mouseX - this.x - this.canvasOffsetX) / (double) CANVAS_SCALE_FACTOR;
            double canvasY = (mouseY - this.y - this.canvasOffsetY) / (double) CANVAS_SCALE_FACTOR;

            this.renderCursor(matrixStack, canvasX, canvasY);
        } else {
            GLFW.glfwSetInputMode(Minecraft.getInstance().getWindow().getWindow(), GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }
    }

    public void renderCursor(PoseStack matrixStack, double canvasX, double canvasY) {

        AbstractToolParameters toolParameters = this.parentScreen.getMenu().getCurrentToolParameters();
        AbstractTool.ToolShape shape = this.parentScreen.getMenu().getCurrentTool().getTool().getShape(toolParameters);

        if (shape == null) {
            int radius = CANVAS_SCALE_FACTOR * 2;

            if (toolParameters instanceof SizeInterface) {
                radius = Math.round(CANVAS_SCALE_FACTOR * ((SizeInterface) toolParameters).getSize());
            }

            if (radius < 4) {
                radius = 4;
            }

            int globalX = this.x + this.canvasOffsetX + (int) Math.floor(canvasX * CANVAS_SCALE_FACTOR) - 1;
            int globalY = this.y + this.canvasOffsetY + (int) Math.floor(canvasY * CANVAS_SCALE_FACTOR) - 1;

            this.hLine(matrixStack, globalX - radius, globalX - 2, globalY, 0x80808080);
            this.hLine(matrixStack, globalX + radius, globalX + 2, globalY, 0x80808080);

            this.vLine(matrixStack, globalX, globalY - radius, globalY - 2, 0x80808080);
            this.vLine(matrixStack, globalX, globalY + radius, globalY + 2, 0x80808080);
        } else {
            int globalX = this.x + this.canvasOffsetX + (int) Math.floor(canvasX) * CANVAS_SCALE_FACTOR - 1;
            int globalY = this.y + this.canvasOffsetY + (int) Math.floor(canvasY) * CANVAS_SCALE_FACTOR - 1;

            for (AbstractTool.ShapeLine line : shape.getLines()) {
                int posX = line.posX() * CANVAS_SCALE_FACTOR;
                int posY = line.posY() * CANVAS_SCALE_FACTOR;
                int length = line.length() * CANVAS_SCALE_FACTOR;

                // Add + 1 to wrap pixel around
                if (posX > 0) {
                    posX++;
                }

                if (posY > 0) {
                    posY++;
                }

                if (line.direction() == AbstractTool.ShapeLine.LineDirection.HORIZONTAL) {
                    if (posX + length > 0) {
                        length++;
                    }

                    this.hLine(matrixStack, globalX + posX, globalX + posX + length, globalY + posY, 0x80808080);
                } else {
                    if (posY + length > 0) {
                        length++;
                    }

                    this.vLine(matrixStack, globalX + posX, globalY + posY, globalY + posY + length, 0x80808080);
                }
            }
        }

        /*this.hLine(matrixStack, globalX1, globalX2, globalY1, 0x80808080);
        this.hLine(matrixStack, globalX1, globalX2, globalY2, 0x80808080);
        this.vLine(matrixStack, globalX1, globalY1, globalY2, 0x80808080);
        this.vLine(matrixStack, globalX2, globalY1, globalY2, 0x80808080);*/
    }
}
