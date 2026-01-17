package dev.igorilic;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.UUID;

@Mod(projectflattenedutilities.MOD_ID)
public class SpawnHomeCommand {
    private static final Logger LOGGER = LogManager.getLogger();
    static String NBT_HOUSE_KEY = "pf_schematics:used";

    public SpawnHomeCommand() {
        new File(FMLPaths.CONFIGDIR.get().toFile(), "pf_schematics").mkdirs();
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context, Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("spawnHouse")
                .then(Commands.argument("filename", StringArgumentType.string())
                        .executes(ctx -> new SpawnHomeCommand().spawnHouse(
                                ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "filename")
                        ))
                )
                .executes(ctx -> new SpawnHomeCommand().spawnHouse(
                        ctx.getSource().getPlayerOrException(),
                        "default_base"
                ))
        );

        dispatcher.register(
                Commands.literal("clearHouse")
                        .requires(cs ->
                                cs.isPlayer()
                                        && cs.getEntity() instanceof ServerPlayer
                                        && cs.hasPermission(2)
                        )
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(ctx -> new SpawnHomeCommand().clearHouseNBT(
                                        ctx,
                                        StringArgumentType.getString(ctx, "player")
                                ))
                        )
                        .executes(ctx -> {
                            /*int level = IntStream.iterate(4, i -> i >= 0, i -> i - 1).filter(i -> ctx.getSource().hasPermission(i)).findFirst().orElse(0);
                            ctx.getSource().sendSuccess(() ->
                                    Component.literal("Your permission level is: " + level), false);*/

                            return new SpawnHomeCommand().clearHouseNBT(
                                    ctx,
                                    ctx.getSource().getPlayer().getName().getString()
                            );
                        })
        );
    }

    private int clearHouseNBT(CommandContext<CommandSourceStack> context, String playerArg) {
        ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayerByName(playerArg);
        assert player != null;
        CompoundTag tag = player.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);

        if (tag.getBoolean(NBT_HOUSE_KEY)) {
            tag.remove(NBT_HOUSE_KEY);
        }
        player.sendSystemMessage(Component.translatable("commands.projectflattenedutilities.clear_house_success").plainCopy()
                .withStyle(ChatFormatting.GREEN));

        return 1;
    }

    private int spawnHouse(ServerPlayer player, String filename) {
        try {
            CompoundTag tag = player.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);

            UUID uuid = player.getUUID();
            List<? extends String> allowedUUIDs = Config.SPAWN_HOUSE_WHITELIST.get();

            boolean isAllowed = allowedUUIDs.isEmpty() || allowedUUIDs.contains(uuid.toString());

            if (!isAllowed) {
                player.sendSystemMessage(
                        Component.translatable("commands.projectflattenedutilities.not_allowed_to_use_command")
                                .plainCopy()
                                .withStyle(ChatFormatting.RED)
                );
                return 0;
            }

            if (tag.getBoolean(NBT_HOUSE_KEY)) {
                player.sendSystemMessage(
                        Component.translatable("commands.projectflattenedutilities.house_already_spawned_in")
                                .plainCopy()
                                .withStyle(ChatFormatting.YELLOW)
                );
                return 0;
            }

            ServerLevel level = player.serverLevel();
            BlockPos pos = player.blockPosition();

            File structureFile = new File(FMLPaths.CONFIGDIR.get().toFile(), "pf_schematics/" + filename + ".nbt");
            if (!structureFile.exists()) {
                player.sendSystemMessage(
                        Component.translatable("commands.projectflattenedutilities.file_not_found", structureFile.getAbsolutePath())
                                .plainCopy()
                                .withStyle(ChatFormatting.RED)
                );
                return 0;
            }

            CompoundTag nbt = NbtIo.readCompressed(new FileInputStream(structureFile));
            StructureTemplate template = new StructureTemplate();
            HolderGetter<Block> blockGetter = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            template.load(blockGetter, nbt);

            Vec3i size = template.getSize();
            StructurePlaceSettings settings = new StructurePlaceSettings();

            BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();

            outer:
            for (int dx = 0; dx < size.getX(); dx++) {
                for (int dy = 0; dy < size.getY(); dy++) {
                    for (int dz = 0; dz < size.getZ(); dz++) {
                        checkPos.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                        Block block = level.getBlockState(checkPos).getBlock();

                        String id = block.builtInRegistryHolder().key().location().getPath();

                        if (!id.equals("air") && !id.equals("grass_block") && !id.equals("dirt")) {
                            player.sendSystemMessage(
                                    Component.translatable("commands.projectflattenedutilities.area_not_clear_enough")
                                            .plainCopy()
                                            .withStyle(ChatFormatting.RED)
                            );
                            return 0;
                        }
                    }
                }
            }

            template.placeInWorld(level, pos, pos, settings, level.getRandom(), 2);
            player.sendSystemMessage(
                    Component.translatable("commands.projectflattenedutilities.house_spawned", filename, pos.toShortString())
                            .plainCopy()
                            .withStyle(ChatFormatting.GREEN)
            );

            tag.putBoolean(NBT_HOUSE_KEY, true);
            player.getPersistentData().put(ServerPlayer.PERSISTED_NBT_TAG, tag);

            return 1;
        } catch (Exception e) {
            e.printStackTrace();
            player.sendSystemMessage(
                    Component.translatable("commands.projectflattenedutilities.exception_error", e.getClass().getSimpleName(), e.getMessage())
            );
            return 0;
        }
    }
}
