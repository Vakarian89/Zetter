package com.dantaeusb.zetter.core;

import com.dantaeusb.zetter.Zetter;
import com.dantaeusb.zetter.block.ArtistTableBlock;
import com.dantaeusb.zetter.deprecated.block.EaselBlock;
import net.minecraft.world.level.material.Material;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

@Mod.EventBusSubscriber(modid = Zetter.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModBlocks
{
    public static final List<Block> BLOCK_ITEMS = new ArrayList<>();

    private static final List<Block> BLOCKS = new ArrayList<>();

    public static final Block ARTIST_TABLE = registerBlockItem("artist_table", new ArtistTableBlock(BlockBehaviour.Properties.of(Material.WOOD).strength(2.5F).sound(SoundType.WOOD)));
    public static final Block EASEL = register("easel", new EaselBlock(BlockBehaviour.Properties.of(Material.WOOD).strength(1.5F).sound(SoundType.WOOD).noOcclusion()));

    private static Block registerBlockItem(String name, Block block)
    {
        Block blockItem = register(name, block);
        BLOCK_ITEMS.add(blockItem);

        return blockItem;
    }

    private static Block register(String name, Block block)
    {
        block.setRegistryName(Zetter.MOD_ID, name);
        BLOCKS.add(block);

        return block;
    }

    @SubscribeEvent
    @SuppressWarnings("unused")
    public static void registerBlocks(final RegistryEvent.Register<Block> event)
    {
        BLOCKS.forEach(block -> event.getRegistry().register(block));
        BLOCKS.clear();
    }
}