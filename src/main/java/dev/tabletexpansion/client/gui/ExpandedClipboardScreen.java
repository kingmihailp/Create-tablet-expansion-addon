package dev.tabletexpansion.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEditPacket;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.utility.CreateLang;
import dev.tabletexpansion.mixin.ClipboardScreenAccessor;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Expanded clipboard screen that shows all entries from all pages
 * on a single large scrollable page.
 *
 * <p>Visual style preserves Create's clipboard aesthetic:
 * same fonts, colours, icons, and checkbox mechanics.
 */
@OnlyIn(Dist.CLIENT)
public class ExpandedClipboardScreen extends AbstractSimiScreen {

    // -----------------------------------------------------------------------
    // Constants — colours taken directly from Create's clipboard palette
    // -----------------------------------------------------------------------

    /** Dark-brown parchment text colour (same as ClipboardScreen). */
    private static final int COLOR_TEXT          = 0x311A00;
    /** Green for checked/completed entries. */
    private static final int COLOR_CHECKED_TEXT  = 0x31B25D;
    /** Strikethrough / inactive alpha-blend shade. */
    private static final int COLOR_INACTIVE      = 0x668D7F6B;

    // Background & border colours reverse-engineered from clipboard.png palette
    private static final int COLOR_BG_OUTER      = 0xFF6B5338; // dark wooden frame
    private static final int COLOR_BG_MID        = 0xFF8B6C4A; // medium wood ring
    private static final int COLOR_BG_PARCHMENT  = 0xFFECDEB0; // warm parchment fill
    private static final int COLOR_BG_CONTENT    = 0xFFF5EDD4; // lighter inner content
    private static final int COLOR_RULE           = 0xFFCDBD96; // subtle rule line
    private static final int COLOR_SCROLLBAR_BG  = 0xFF9E8462;
    private static final int COLOR_SCROLLBAR_FG  = 0xFF5C3D1E;
    private static final int COLOR_CLIP_METAL    = 0xFFA09080; // clipboard clip
    private static final int COLOR_CLIP_LIGHT    = 0xFFBAAFA8;
    private static final int COLOR_TITLE_TEXT    = 0xFF43332A;

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

    private static final int WIN_WIDTH         = 256;
    /** Minimum content area height (pixels). */
    private static final int MIN_CONTENT_H     = 200;
    /** Horizontal padding from window left to the checkbox column. */
    private static final int CONTENT_PAD_LEFT  = 24;
    /** Width available for text (entry text wraps within this). */
    private static final int TEXT_WRAP_WIDTH   = 170;
    /** Left edge of the text column relative to guiLeft. */
    private static final int TEXT_COL          = 46;
    /** Y coordinate of the first entry row relative to guiTop. */
    private static final int CONTENT_START_Y   = 38;
    /** Scrollbar width in pixels. */
    private static final int SCROLLBAR_W       = 8;
    /** Right padding so scrollbar doesn't touch the border. */
    private static final int SCROLLBAR_MARGIN  = 5;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /** All entries from every page, flattened. */
    private final List<ClipboardEntry> entries = new ArrayList<>();
    private ClipboardContent content;
    private final int targetSlot;
    private final BlockPos targetedBlock;
    private final boolean readOnly;

    // Editing
    private int editingIndex = -1;
    private int frameTick;
    private long lastClickTime;
    private int lastClickedIndex = -1;
    private TextFieldHelper editContext;
    private DisplayCache displayCache;

    // Hover
    private int hoveredEntry  = -1;
    private boolean hoveredCheck = false;

    // Scroll (in pixels)
    private int scrollOffset = 0;
    /** Total pixel height of all rendered entries. Updated in tick(). */
    private int totalContentHeight = 0;

    // Computed window height (set in init)
    private int windowHeight;
    /** Height of the scrollable content area. */
    private int contentAreaHeight;

    // Buttons
    private IconButton closeBtn;
    private IconButton clearBtn;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public ExpandedClipboardScreen(ClipboardScreen original) {
        ClipboardScreenAccessor acc = (ClipboardScreenAccessor) original;
        this.targetSlot    = acc.getTargetSlot();
        this.targetedBlock = original.targetedBlock;
        this.content       = original.content != null ? original.content : ClipboardContent.EMPTY;
        this.readOnly      = this.content.readOnly();

        // Flatten all pages into one list
        for (List<ClipboardEntry> page : acc.getPages()) {
            for (ClipboardEntry e : page) {
                // Defensive copy so we don't alias into the original screen
                entries.add(new ClipboardEntry(e.checked, e.text.copy()));
            }
        }
        if (entries.isEmpty()) {
            entries.add(new ClipboardEntry(false, Component.empty()));
        }

        resetEditContext();
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void init() {
        // Dynamic height: fill most of the screen height, capped at 400
        int screenH = minecraft.getWindow().getGuiScaledHeight();
        windowHeight = Math.min(screenH - 40, 400);
        contentAreaHeight = windowHeight - CONTENT_START_Y - 16;

        setWindowSize(WIN_WIDTH, windowHeight);
        super.init();
        clearDisplayCache();

        int x = guiLeft;
        int y = guiTop;

        clearWidgets();

        // Close button – bottom-right
        closeBtn = new IconButton(x + WIN_WIDTH - 20, y + windowHeight - 22, AllIcons.I_PRIORITY_VERY_LOW)
                .withCallback(() -> minecraft.setScreen(null));
        closeBtn.setToolTip(CreateLang.translateDirect("station.close"));
        addRenderableWidget(closeBtn);

        // Clear-checked button – just left of close
        if (!readOnly) {
            clearBtn = new IconButton(x + WIN_WIDTH - 38, y + windowHeight - 22, AllIcons.I_CLEAR_CHECKED)
                    .withCallback(this::clearChecked);
            clearBtn.setToolTip(CreateLang.translateDirect("gui.clipboard.erase_checked"));
            addRenderableWidget(clearBtn);
        }
    }

    private void clearChecked() {
        editingIndex = -1;
        entries.removeIf(e -> e.checked);
        if (entries.isEmpty()) {
            entries.add(new ClipboardEntry(false, Component.empty()));
        }
        clampScroll();
        send();
    }

    @Override
    public void tick() {
        super.tick();
        frameTick++;

        // Proximity check when interacting with clipboard block
        if (targetedBlock != null && minecraft.player != null && minecraft.level != null) {
            if (!minecraft.player.blockPosition().closerThan(targetedBlock, 10)) {
                removed();
                return;
            }
        }

        updateHover();
    }

    @Override
    public void removed() {
        // Strip blank entries and send
        entries.removeIf(e -> e.text.getString().isBlank());
        if (entries.isEmpty()) {
            entries.add(new ClipboardEntry(false, Component.empty()));
        }
        send();
        super.removed();
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    @Override
    protected void renderWindow(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        int x = guiLeft;
        int y = guiTop;

        drawBackground(g, x, y);
        drawTitle(g, x, y);
        drawScrollbar(g, x, y);

        // Scissor: clip entries to the content area
        int scissorX   = x + CONTENT_PAD_LEFT;
        int scissorY   = y + CONTENT_START_Y;
        int scissorW   = WIN_WIDTH - CONTENT_PAD_LEFT - SCROLLBAR_W - SCROLLBAR_MARGIN - 4;
        int scissorH   = contentAreaHeight;

        // Scale for scissor
        double scale   = minecraft.getWindow().getGuiScale();
        int sX = (int) (scissorX * scale);
        int sY = (int) ((minecraft.getWindow().getGuiScaledHeight() - (scissorY + scissorH)) * scale);
        int sW = (int) (scissorW * scale);
        int sH = (int) (scissorH * scale);
        RenderSystem.enableScissor(sX, sY, sW, sH);

        drawEntries(g, x, y);
        drawEditCursor(g, x, y);

        RenderSystem.disableScissor();
    }

    /**
     * Draw the parchment background with a wooden frame — matching Create's
     * clipboard colour palette without needing an extra texture file.
     */
    private void drawBackground(GuiGraphics g, int x, int y) {
        int w = WIN_WIDTH;
        int h = windowHeight;

        // Outer dark-wood frame
        g.fill(x,         y,         x + w,     y + h,     COLOR_BG_OUTER);
        // Medium wood ring (1 px inset)
        g.fill(x + 1,     y + 1,     x + w - 1, y + h - 1, COLOR_BG_MID);
        // Parchment body (3 px inset)
        g.fill(x + 3,     y + 3,     x + w - 3, y + h - 3, COLOR_BG_PARCHMENT);
        // Lighter content area
        g.fill(x + CONTENT_PAD_LEFT - 2,
               y + CONTENT_START_Y - 2,
               x + WIN_WIDTH - SCROLLBAR_W - SCROLLBAR_MARGIN - 3,
               y + CONTENT_START_Y + contentAreaHeight + 2,
               COLOR_BG_CONTENT);

        // Metal clipboard clip at top-centre
        int clipX = x + WIN_WIDTH / 2 - 18;
        int clipY = y;
        g.fill(clipX,     clipY,     clipX + 36, clipY + 12, COLOR_CLIP_METAL);
        g.fill(clipX + 1, clipY + 1, clipX + 35, clipY + 11, COLOR_CLIP_LIGHT);
        g.fill(clipX + 6, clipY + 8, clipX + 30, clipY + 18, COLOR_CLIP_METAL);
        g.fill(clipX + 8, clipY + 9, clipX + 28, clipY + 17, COLOR_BG_PARCHMENT);

        // Separator under title
        g.fill(x + 8, y + CONTENT_START_Y - 4, x + WIN_WIDTH - 8, y + CONTENT_START_Y - 3, COLOR_RULE);

        // Bottom button strip separator
        g.fill(x + 8, y + h - 28, x + WIN_WIDTH - 8, y + h - 27, COLOR_RULE);

        // Subtle horizontal rules in the content area (every ~18 px)
        for (int ry = CONTENT_START_Y + 9; ry < CONTENT_START_Y + contentAreaHeight - 4; ry += 18) {
            g.fill(x + CONTENT_PAD_LEFT, y + ry, x + WIN_WIDTH - SCROLLBAR_W - SCROLLBAR_MARGIN - 4, y + ry + 1, COLOR_RULE);
        }
    }

    private void drawTitle(GuiGraphics g, int x, int y) {
        Component title = Component.translatable("gui.tabletexpansion.expanded_clipboard");
        int titleX = x + WIN_WIDTH / 2 - font.width(title) / 2;
        g.drawString(font, title, titleX, y + 22, COLOR_TITLE_TEXT, false);

        // Entry count indicator (top-right corner)
        int nonBlank = (int) entries.stream().filter(e -> !e.text.getString().isBlank()).count();
        Component counter = Component.literal(nonBlank + " entries");
        g.drawString(font, counter, x + WIN_WIDTH - 8 - font.width(counter), y + 22, COLOR_INACTIVE, false);
    }

    private void drawEntries(GuiGraphics g, int x, int y) {
        int drawY = y + CONTENT_START_Y - scrollOffset;
        totalContentHeight = 0;

        for (int i = 0; i < entries.size(); i++) {
            ClipboardEntry entry = entries.get(i);
            boolean checked = entry.checked;
            boolean isEditing = (i == editingIndex);

            String raw   = entry.text.getString();
            boolean isAddress = raw.startsWith("#") && !raw.substring(1).isBlank();

            int iconOffset = entry.icon.isEmpty() ? 0 : 18;
            int wrapW  = TEXT_WRAP_WIDTH - iconOffset;
            MutableComponent displayText = isAddress
                    ? Component.literal(raw.substring(1).stripLeading())
                    : entry.text;

            List<FormattedCharSequence> lines = font.split(displayText, wrapW);
            int lineCount   = Math.max(1, lines.size());
            int entryHeight = lineCount * 9 + 3;
            totalContentHeight += entryHeight;

            // Skip entries that are fully above the visible area
            if (drawY + entryHeight < y + CONTENT_START_Y) {
                drawY += entryHeight;
                continue;
            }
            // Stop rendering once below the visible area
            if (drawY > y + CONTENT_START_Y + contentAreaHeight) {
                drawY += entryHeight;
                continue;
            }

            // --- Checkbox / address icon ---
            int checkX = x + CONTENT_PAD_LEFT - 2;
            int checkY = drawY;

            if (isAddress) {
                RenderSystem.enableBlend();
                (checked ? AllGuiTextures.CLIPBOARD_ADDRESS_INACTIVE : AllGuiTextures.CLIPBOARD_ADDRESS)
                        .render(g, checkX - 8, checkY - 5);
            } else {
                g.drawString(font, "\u25A1", checkX, checkY, checked ? COLOR_INACTIVE : COLOR_TEXT, false);
                if (checked) {
                    g.drawString(font, "\u2714", checkX, checkY, COLOR_CHECKED_TEXT, false);
                }
            }

            // --- Item icon (if present) ---
            if (!entry.icon.isEmpty()) {
                g.renderItem(entry.icon, x + TEXT_COL - 4, drawY - 1);
            }

            // --- Text lines (skip rendering if currently editing — cursor/highlight handles it) ---
            if (!isEditing) {
                for (FormattedCharSequence seq : lines) {
                    int textColor = checked
                            ? (isAddress ? COLOR_INACTIVE : COLOR_CHECKED_TEXT)
                            : COLOR_TEXT;
                    g.drawString(font, seq, x + TEXT_COL + iconOffset, drawY, textColor, false);
                    drawY += 9;
                }
                drawY += 3;
            } else {
                // Reserve the space; actual text drawn by drawEditCursor
                drawY += entryHeight;
            }
        }

        // If not editing, update totalContentHeight for the scroll calculation
        clampScroll();
    }

    private void drawEditCursor(GuiGraphics g, int x, int y) {
        if (editingIndex < 0 || editingIndex >= entries.size()) return;

        setFocused(null);
        DisplayCache cache = getDisplayCache();

        for (LineInfo line : cache.lines) {
            g.drawString(font, line.asComponent, line.x, line.y, COLOR_TEXT, false);
        }

        renderHighlight(cache.selection);
        renderCursor(g, cache.cursor, cache.cursorAtEnd);
    }

    private void drawScrollbar(GuiGraphics g, int x, int y) {
        int sbX = x + WIN_WIDTH - SCROLLBAR_W - SCROLLBAR_MARGIN;
        int sbY = y + CONTENT_START_Y;
        int sbH = contentAreaHeight;

        // Track
        g.fill(sbX, sbY, sbX + SCROLLBAR_W, sbY + sbH, COLOR_SCROLLBAR_BG);

        // Thumb
        int maxScroll = Math.max(0, totalContentHeight - contentAreaHeight);
        if (maxScroll > 0) {
            int thumbH = Math.max(20, sbH * contentAreaHeight / Math.max(totalContentHeight, 1));
            int thumbY = sbY + (sbH - thumbH) * scrollOffset / maxScroll;
            g.fill(sbX + 1, thumbY + 1, sbX + SCROLLBAR_W - 1, thumbY + thumbH - 1, COLOR_SCROLLBAR_FG);
        }
    }

    // -----------------------------------------------------------------------
    // Hover detection
    // -----------------------------------------------------------------------

    private void updateHover() {
        hoveredCheck = false;
        hoveredEntry = -1;

        if (minecraft == null) return;
        double mx = minecraft.mouseHandler.xpos()
                * minecraft.getWindow().getGuiScaledWidth()
                / minecraft.getWindow().getScreenWidth();
        double my = minecraft.mouseHandler.ypos()
                * minecraft.getWindow().getGuiScaledHeight()
                / minecraft.getWindow().getScreenHeight();

        double relX = mx - (guiLeft + CONTENT_PAD_LEFT - 2);
        double relY = my - (guiTop + CONTENT_START_Y) + scrollOffset;

        if (relX < 0 || relX > WIN_WIDTH - CONTENT_PAD_LEFT - SCROLLBAR_W - SCROLLBAR_MARGIN - 4
                || relY < 0) {
            return;
        }

        hoveredCheck = relX < 18;

        int runningY = 0;
        for (int i = 0; i < entries.size(); i++) {
            ClipboardEntry e = entries.get(i);
            int wrapW = TEXT_WRAP_WIDTH - (e.icon.isEmpty() ? 0 : 18);
            int lineCount = Math.max(1, font.split(e.text, wrapW).size());
            int entryH = lineCount * 9 + 3;
            runningY += entryH;
            if (relY < runningY) {
                hoveredEntry = i;
                return;
            }
        }
        hoveredEntry = entries.size(); // click below last entry
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll((int) (-dy * 12));
        return true;
    }

    private void scroll(int delta) {
        scrollOffset = Mth.clamp(scrollOffset + delta, 0, Math.max(0, totalContentHeight - contentAreaHeight));
    }

    private void clampScroll() {
        int max = Math.max(0, totalContentHeight - contentAreaHeight);
        scrollOffset = Mth.clamp(scrollOffset, 0, max);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return true;

        if (hoveredEntry != -1) {
            if (hoveredCheck) {
                // Toggle checkbox
                editingIndex = -1;
                if (hoveredEntry < entries.size()) {
                    entries.get(hoveredEntry).checked ^= true;
                    boolean nowChecked = entries.get(hoveredEntry).checked;
                    Minecraft.getInstance().getSoundManager().play(
                            SimpleSoundInstance.forUI(
                                    (nowChecked
                                            ? AllSoundEvents.CLIPBOARD_CHECKMARK
                                            : AllSoundEvents.CLIPBOARD_ERASE).getMainEvent(),
                                    0.93f + (float) Math.random() * 0.07f
                            )
                    );
                    sendIfBlock();
                }
                return true;
            }

            if (!readOnly && hoveredEntry != editingIndex) {
                editingIndex = hoveredEntry;
                if (hoveredEntry >= entries.size()) {
                    entries.add(new ClipboardEntry(false, Component.empty()));
                }
                clearDisplayCacheAfterChange();
            }
        } else if (editingIndex != -1) {
            // Click outside entry area → stop editing
            if (mx < guiLeft + CONTENT_PAD_LEFT - 4 || mx > guiLeft + WIN_WIDTH - SCROLLBAR_W - SCROLLBAR_MARGIN - 4
                    || my < guiTop + CONTENT_START_Y || my > guiTop + CONTENT_START_Y + contentAreaHeight) {
                editingIndex = -1;
                clearDisplayCache();
            }
        }

        if (editingIndex == -1) return false;

        // Position cursor within the text
        long now = Util.getMillis();
        DisplayCache cache = getDisplayCache();
        Pos2i local = convertScreenToLocal(new Pos2i((int) mx, (int) my));
        int idx = cache.getIndexAtPosition(font, local);
        if (idx >= 0) {
            if (idx == lastClickedIndex && now - lastClickTime < 250L) {
                if (!editContext.isSelecting()) selectWord(idx);
                else editContext.selectAll();
            } else {
                editContext.setCursorPos(idx, Screen.hasShiftDown());
            }
            clearDisplayCache();
        }
        lastClickedIndex = idx;
        lastClickTime    = now;
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (super.mouseDragged(mx, my, button, dx, dy)) return true;
        if (button != 0 || editingIndex == -1) return false;

        DisplayCache cache = getDisplayCache();
        int idx = cache.getIndexAtPosition(font, convertScreenToLocal(new Pos2i((int) mx, (int) my)));
        editContext.setCursorPos(idx, true);
        clearDisplayCache();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (editingIndex != -1 && key != 256) {
            keyPressedWhileEditing(key, scan, mods);
            clearDisplayCache();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char ch, int mods) {
        if (super.charTyped(ch, mods)) return true;
        if (!StringUtil.isAllowedChatCharacter(ch)) return false;
        if (editingIndex == -1) return false;
        editContext.insertText(Character.toString(ch));
        clearDisplayCache();
        return true;
    }

    private void keyPressedWhileEditing(int key, int scan, int mods) {
        if (Screen.isSelectAll(key)) { editContext.selectAll(); return; }
        if (Screen.isCopy(key))      { editContext.copy();      return; }
        if (Screen.isPaste(key))     { editContext.paste();     return; }
        if (Screen.isCut(key))       { editContext.cut();       return; }

        switch (key) {
            // Enter / numpad-enter
            case 257, 335 -> {
                if (Screen.hasShiftDown()) {
                    editContext.insertText("\n");
                } else if (!Screen.hasControlDown()) {
                    // Insert new entry below
                    if (editingIndex + 1 >= entries.size()
                            || !entries.get(editingIndex + 1).text.getString().isEmpty()) {
                        entries.add(editingIndex + 1, new ClipboardEntry(false, Component.empty()));
                    }
                    editingIndex++;
                    editContext.setCursorToEnd();
                } else {
                    editingIndex = -1;
                }
            }
            // Backspace
            case 259 -> {
                if (entries.get(editingIndex).text.getString().isEmpty() && entries.size() > 1) {
                    entries.remove(editingIndex);
                    editingIndex = Math.max(0, editingIndex - 1);
                    editContext.setCursorToEnd();
                } else if (Screen.hasControlDown()) {
                    int prev = editContext.getCursorPos();
                    editContext.moveByWords(-1);
                    if (prev != editContext.getCursorPos())
                        editContext.removeCharsFromCursor(prev - editContext.getCursorPos());
                } else {
                    editContext.removeCharsFromCursor(-1);
                }
            }
            // Delete
            case 261 -> {
                if (Screen.hasControlDown()) {
                    int prev = editContext.getCursorPos();
                    editContext.moveByWords(1);
                    if (prev != editContext.getCursorPos())
                        editContext.removeCharsFromCursor(prev - editContext.getCursorPos());
                } else {
                    editContext.removeCharsFromCursor(1);
                }
            }
            case 262 -> { // Right arrow
                if (Screen.hasControlDown()) editContext.moveByWords(1, Screen.hasShiftDown());
                else editContext.moveByChars(1, Screen.hasShiftDown());
            }
            case 263 -> { // Left arrow
                if (Screen.hasControlDown()) editContext.moveByWords(-1, Screen.hasShiftDown());
                else editContext.moveByChars(-1, Screen.hasShiftDown());
            }
            case 264 -> changeLine(1);   // Down
            case 265 -> changeLine(-1);  // Up
            case 268 -> keyHome();
            case 269 -> keyEnd();
        }
    }

    private void changeLine(int dir) {
        int idx = editContext.getCursorPos();
        int next = getDisplayCache().changeLine(idx, dir);
        editContext.setCursorPos(next, Screen.hasShiftDown());
    }

    private void keyHome() {
        int idx = editContext.getCursorPos();
        editContext.setCursorPos(getDisplayCache().findLineStart(idx), Screen.hasShiftDown());
    }

    private void keyEnd() {
        int idx = editContext.getCursorPos();
        editContext.setCursorPos(getDisplayCache().findLineEnd(idx), Screen.hasShiftDown());
    }

    private void selectWord(int idx) {
        String s = getCurrentEntryText();
        editContext.setSelectionRange(
                StringSplitter.getWordPosition(s, -1, idx, false),
                StringSplitter.getWordPosition(s, 1,  idx, false));
    }

    // -----------------------------------------------------------------------
    // TextFieldHelper callbacks
    // -----------------------------------------------------------------------

    private String getCurrentEntryText() {
        if (editingIndex < 0 || editingIndex >= entries.size()) return "";
        return entries.get(editingIndex).text.getString();
    }

    private void setCurrentEntryText(String text) {
        if (editingIndex < 0 || editingIndex >= entries.size()) return;
        entries.get(editingIndex).text = Component.literal(text);
        sendIfBlock();
    }

    private String getClipboardText() {
        return minecraft != null ? TextFieldHelper.getClipboardContents(minecraft) : "";
    }

    private void setClipboardText(String text) {
        if (minecraft != null) TextFieldHelper.setClipboardContents(minecraft, text);
    }

    private boolean validateText(String candidate) {
        // Simple length guard: reject if would exceed a reasonable character count
        int total = 0;
        for (int i = 0; i < entries.size(); i++) {
            String t = (i == editingIndex) ? candidate : entries.get(i).text.getString();
            total += font.split(Component.literal(t), TEXT_WRAP_WIDTH).size() * 9 + 3;
        }
        return total < 10_000; // generous limit for expanded view
    }

    private void resetEditContext() {
        editContext = new TextFieldHelper(
                this::getCurrentEntryText,
                this::setCurrentEntryText,
                this::getClipboardText,
                this::setClipboardText,
                this::validateText
        );
    }

    // -----------------------------------------------------------------------
    // Network
    // -----------------------------------------------------------------------

    private void sendIfBlock() {
        if (minecraft != null && minecraft.player != null
                && minecraft.player.connection.getOnlinePlayers().size() > 1
                && targetedBlock != null) {
            send();
        }
    }

    private void send() {
        // Pack all entries into a single page
        List<List<ClipboardEntry>> pages = new ArrayList<>();
        // Split flattened entries into pages of 50 to stay within Create's limit
        int pageSize = 50;
        for (int start = 0; start < entries.size(); start += pageSize) {
            pages.add(new ArrayList<>(entries.subList(start, Math.min(start + pageSize, entries.size()))));
        }
        if (pages.isEmpty()) {
            pages.add(new ArrayList<>());
        }

        ClipboardContent updated = content
                .setPages(pages)
                .setType(ClipboardType.WRITTEN)
                .setPreviouslyOpenedPage(0);

        CatnipServices.NETWORK.sendToServer(new ClipboardEditPacket(targetSlot, updated, targetedBlock));
    }

    // -----------------------------------------------------------------------
    // Cursor & selection rendering
    // -----------------------------------------------------------------------

    private void renderCursor(GuiGraphics g, Pos2i pos, boolean atEnd) {
        if (frameTick / 6 % 2 != 0) return;
        Pos2i screen = convertLocalToScreen(pos);
        if (!atEnd) {
            g.fill(screen.x, screen.y - 1, screen.x + 1, screen.y + 9, 0xFF000000);
        } else {
            g.drawString(font, "_", screen.x, screen.y, 0, false);
        }
    }

    private void renderHighlight(Rect2i[] selection) {
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        RenderSystem.setShader(GameRenderer::getPositionShader);
        RenderSystem.setShaderColor(0f, 0f, 255f, 255f);
        RenderSystem.enableColorLogicOp();
        RenderSystem.logicOp(GlStateManager.LogicOp.OR_REVERSE);

        for (Rect2i r : selection) {
            int x0 = r.getX(), y0 = r.getY();
            int x1 = x0 + r.getWidth(), y1 = y0 + r.getHeight();
            buf.addVertex(x0, y1, 0);
            buf.addVertex(x1, y1, 0);
            buf.addVertex(x1, y0, 0);
            buf.addVertex(x0, y0, 0);
        }

        @Nullable MeshData mesh = buf.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        RenderSystem.disableColorLogicOp();
    }

    // -----------------------------------------------------------------------
    // Coordinate helpers for text editing
    // -----------------------------------------------------------------------

    /** Y offset within the editing entry's text, accounting for scroll. */
    private int yOffsetOfEditingEntry() {
        int total = 0;
        for (int i = 0; i < Math.min(editingIndex, entries.size()); i++) {
            ClipboardEntry e = entries.get(i);
            total += Math.max(12, font.split(e.text, TEXT_WRAP_WIDTH).size() * 9 + 3);
        }
        return total;
    }

    private Pos2i convertScreenToLocal(Pos2i screen) {
        int baseX = guiLeft + TEXT_COL;
        int baseY = guiTop + CONTENT_START_Y + yOffsetOfEditingEntry() - scrollOffset;
        return new Pos2i(screen.x - baseX, screen.y - baseY);
    }

    private Pos2i convertLocalToScreen(Pos2i local) {
        int baseX = guiLeft + TEXT_COL;
        int baseY = guiTop + CONTENT_START_Y + yOffsetOfEditingEntry() - scrollOffset;
        return new Pos2i(local.x + baseX, local.y + baseY);
    }

    // -----------------------------------------------------------------------
    // DisplayCache management
    // -----------------------------------------------------------------------

    private DisplayCache getDisplayCache() {
        if (displayCache == null) displayCache = rebuildDisplayCache();
        return displayCache;
    }

    private void clearDisplayCache() {
        displayCache = null;
    }

    private void clearDisplayCacheAfterChange() {
        editContext.setCursorToEnd();
        clearDisplayCache();
    }

    private DisplayCache rebuildDisplayCache() {
        if (editingIndex < 0 || editingIndex >= entries.size()) return DisplayCache.EMPTY;

        String current = getCurrentEntryText();
        boolean address = current.startsWith("#") && !current.substring(1).isBlank();
        int offset = 0;
        if (address) {
            String stripped = current.substring(1).stripLeading();
            offset = current.length() - stripped.length();
            current = stripped;
        }
        if (current.isEmpty()) return DisplayCache.EMPTY;

        String s = current;
        int cursorPos = Mth.clamp(editContext.getCursorPos() - offset, 0, s.length());
        int selPos    = Mth.clamp(editContext.getSelectionPos() - offset, 0, s.length());

        IntList lineStarts = new IntArrayList();
        List<LineInfo> lineInfos = new ArrayList<>();
        MutableInt lineIdx = new MutableInt();
        MutableBoolean endsNewline = new MutableBoolean();

        font.getSplitter().splitLines(s, TEXT_WRAP_WIDTH, Style.EMPTY, true,
                (style, start, end) -> {
                    int ln = lineIdx.getAndIncrement();
                    String seg = s.substring(start, end);
                    endsNewline.setValue(seg.endsWith("\n"));
                    String stripped = StringUtils.stripEnd(seg, " \n");
                    Pos2i scr = convertLocalToScreen(new Pos2i(0, ln * 9));
                    lineStarts.add(start);
                    lineInfos.add(new LineInfo(style, stripped, scr.x, scr.y));
                });

        int[] starts = lineStarts.toIntArray();
        boolean atEnd = (cursorPos == s.length());
        Pos2i cursor;
        if (atEnd && endsNewline.isTrue()) {
            cursor = new Pos2i(0, lineInfos.size() * 9);
        } else {
            int ln = findLineFromPos(starts, cursorPos);
            int w  = font.width(s.substring(starts[ln], cursorPos));
            cursor = new Pos2i(w, ln * 9);
        }

        List<Rect2i> highlights = new ArrayList<>();
        if (cursorPos != selPos) {
            int lo = Math.min(cursorPos, selPos);
            int hi = Math.max(cursorPos, selPos);
            int l0 = findLineFromPos(starts, lo);
            int l1 = findLineFromPos(starts, hi);
            StringSplitter splitter = font.getSplitter();
            if (l0 == l1) {
                highlights.add(createPartialSel(s, splitter, lo, hi, l0 * 9, starts[l0]));
            } else {
                int nextStart = (l0 + 1 < starts.length) ? starts[l0 + 1] : s.length();
                highlights.add(createPartialSel(s, splitter, lo, nextStart, l0 * 9, starts[l0]));
                for (int li = l0 + 1; li < l1; li++) {
                    int segEnd = (li + 1 < starts.length) ? starts[li + 1] : s.length();
                    int w2 = (int) splitter.stringWidth(s.substring(starts[li], segEnd));
                    highlights.add(createSel(new Pos2i(0, li * 9), new Pos2i(w2, li * 9 + 9)));
                }
                highlights.add(createPartialSel(s, splitter, starts[l1], hi, l1 * 9, starts[l1]));
            }
        }

        return new DisplayCache(s, cursor, atEnd, starts,
                lineInfos.toArray(new LineInfo[0]), highlights.toArray(new Rect2i[0]));
    }

    private Rect2i createPartialSel(String s, StringSplitter spl, int a, int b, int lineY, int lineStart) {
        Pos2i p1 = new Pos2i((int) spl.stringWidth(s.substring(lineStart, a)), lineY);
        Pos2i p2 = new Pos2i((int) spl.stringWidth(s.substring(lineStart, b)), lineY + 9);
        return createSel(p1, p2);
    }

    private Rect2i createSel(Pos2i a, Pos2i b) {
        Pos2i sa = convertLocalToScreen(a);
        Pos2i sb = convertLocalToScreen(b);
        int x0 = Math.min(sa.x, sb.x), x1 = Math.max(sa.x, sb.x);
        int y0 = Math.min(sa.y, sb.y), y1 = Math.max(sa.y, sb.y);
        return new Rect2i(x0, y0, x1 - x0, y1 - y0);
    }

    static int findLineFromPos(int[] starts, int pos) {
        int i = Arrays.binarySearch(starts, pos);
        return i < 0 ? -(i + 2) : i;
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    @OnlyIn(Dist.CLIENT)
    static class DisplayCache {
        static final DisplayCache EMPTY = new DisplayCache(
                "", new Pos2i(0, 0), true, new int[]{0},
                new LineInfo[]{new LineInfo(Style.EMPTY, "", 0, 0)}, new Rect2i[0]);

        final String fullText;
        final Pos2i cursor;
        final boolean cursorAtEnd;
        private final int[] lineStarts;
        final LineInfo[] lines;
        final Rect2i[] selection;

        DisplayCache(String text, Pos2i cursor, boolean atEnd, int[] lineStarts,
                LineInfo[] lines, Rect2i[] selection) {
            this.fullText   = text;
            this.cursor     = cursor;
            this.cursorAtEnd = atEnd;
            this.lineStarts = lineStarts;
            this.lines      = lines;
            this.selection  = selection;
        }

        int getIndexAtPosition(Font font, Pos2i pos) {
            int line = pos.y() / 9;
            if (line < 0) return 0;
            if (line >= lines.length) return fullText.length();
            LineInfo li = lines[line];
            return lineStarts[line] + font.getSplitter().plainIndexAtWidth(li.contents, pos.x(), li.style);
        }

        int changeLine(int cursorIdx, int dir) {
            int ln = findLineFromPos(lineStarts, cursorIdx);
            int newLn = Mth.clamp(ln + dir, 0, lines.length - 1);
            if (ln == newLn) return cursorIdx;
            int xOff = font_width_of_prefix(ln, cursorIdx);
            return lineStarts[newLn] + xOff;
        }

        /** Approximate horizontal offset within the line as a character count. */
        private int font_width_of_prefix(int ln, int pos) {
            // Just return characters from line start to pos (approximate)
            return pos - lineStarts[ln];
        }

        int findLineStart(int pos) {
            return lineStarts[findLineFromPos(lineStarts, pos)];
        }

        int findLineEnd(int pos) {
            int ln = findLineFromPos(lineStarts, pos);
            return lineStarts[ln] + lines[ln].contents.length();
        }
    }

    @OnlyIn(Dist.CLIENT)
    static class LineInfo {
        final Style style;
        final String contents;
        final Component asComponent;
        final int x, y;

        LineInfo(Style style, String contents, int x, int y) {
            this.style      = style;
            this.contents   = contents;
            this.x          = x;
            this.y          = y;
            this.asComponent = Component.literal(contents).setStyle(style);
        }
    }

    @OnlyIn(Dist.CLIENT)
    record Pos2i(int x, int y) {}
}
