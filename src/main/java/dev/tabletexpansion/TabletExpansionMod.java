package dev.tabletexpansion;

import dev.tabletexpansion.client.ClientSetup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(TabletExpansionMod.MOD_ID)
public class TabletExpansionMod {

    public static final String MOD_ID = "tabletexpansion";

    public TabletExpansionMod(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modEventBus);
        }
    }
}
