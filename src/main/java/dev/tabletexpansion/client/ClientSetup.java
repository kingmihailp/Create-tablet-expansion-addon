package dev.tabletexpansion.client;

import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import dev.tabletexpansion.client.gui.ExpandedClipboardScreen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-side setup: registers the screen replacement event.
 * When a ClipboardScreen is about to open, we swap it for our
 * ExpandedClipboardScreen which shows all pages as one scrollable view.
 */
public class ClientSetup {

    public static void init(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(ClientSetup::onScreenOpening);
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getScreen() instanceof ClipboardScreen clipboardScreen) {
            event.setNewScreen(new ExpandedClipboardScreen(clipboardScreen));
        }
    }
}
