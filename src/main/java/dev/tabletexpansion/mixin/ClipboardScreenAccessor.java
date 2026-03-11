package dev.tabletexpansion.mixin;

import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Mixin accessor for ClipboardScreen private/package-private fields.
 * Allows ExpandedClipboardScreen to read the target slot and page data.
 */
@Mixin(value = ClipboardScreen.class, remap = false)
public interface ClipboardScreenAccessor {

    @Accessor("targetSlot")
    int getTargetSlot();

    @Accessor("pages")
    List<List<ClipboardEntry>> getPages();
}
