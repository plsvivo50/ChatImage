package io.github.kituin.chatimage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.kituin.chatimage.command.Help;
import io.github.kituin.chatimage.command.ReloadConfig;
import io.github.kituin.chatimage.command.SendChatImage;
import io.github.kituin.chatimage.gui.ConfigScreen;
import io.github.kituin.chatimage.integration.ChatImageClientAdapter;
import io.github.kituin.chatimage.integration.ChatImageLogger;
import io.github.kituin.chatimage.network.DownloadFileChannel;
import io.github.kituin.chatimage.network.FileBackChannel;
import io.github.kituin.chatimage.network.FileChannel;
import io.github.kituin.chatimage.network.FileInfoChannel;
import io.github.kituin.ChatImageCode.ChatImageCodeInstance;
import io.github.kituin.ChatImageCode.ChatImageConfig;
import io.github.kituin.ChatImageCode.ChatImageFrame;
import io.github.kituin.ChatImageCode.ClientStorage;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraft.commands.Commands;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;

@Mod(ChatImage.MOD_ID)
public class ChatImage {
    public static final Logger LOGGER = LogManager.getLogger();

    public static final String MOD_ID = "chatimage";

    public static ChatImageConfig CONFIG;

    static {
        ChatImageCodeInstance.LOGGER = new ChatImageLogger();
    }
    public ChatImage() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        MinecraftForge.EVENT_BUS.register(this);
        modEventBus.addListener(ChatImage::init);
    }

    public static void init(FMLCommonSetupEvent event) {
        event.enqueueWork(FileChannel::register);
        event.enqueueWork(FileInfoChannel::registerMessage);
        event.enqueueWork(FileBackChannel::register);
        event.enqueueWork(DownloadFileChannel::register);
        LOGGER.info("[ChatImage]Channel Register");
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        static{
            ChatImageConfig.configFile = new File(FMLPaths.CONFIGDIR.get().toFile(), "chatimageconfig.json");
            CONFIG = ChatImageConfig.loadConfig();
            ChatImageCodeInstance.CLIENT_ADAPTER = new ChatImageClientAdapter();
        }

        @SubscribeEvent
        public static void onKeyBindRegister(RegisterKeyMappingsEvent event) {
            KeyBindings.init(event);
            LOGGER.info("KeyBindings Register");
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("[ChatImage]Client start");
            ModLoadingContext.get().registerExtensionPoint(net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class, () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory((minecraft, screen) -> new ConfigScreen(screen)));
            MinecraftForge.EVENT_BUS.addListener(ClientModEvents::onClientCommand);
            MinecraftForge.EVENT_BUS.addListener(ClientModEvents::onKeyInput);
            MinecraftForge.EVENT_BUS.addListener(ClientModEvents::onClientStaring);
            
            // ========== 新增：注册GIF动画Tick更新 ==========
            MinecraftForge.EVENT_BUS.addListener(ClientModEvents::onClientTick);
            LOGGER.info("[ChatImage]GIF动画Tick已注册");
            // ==============================================
        }
        
        // ========== 新增：客户端Tick事件，驱动GIF动画（使用反射访问ClientStorage私有字段） ==========
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                try {
                    java.lang.reflect.Field imagesField = ClientStorage.class.getDeclaredField("images");
                    imagesField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    java.util.HashMap<String, ChatImageFrame> images = 
                        (java.util.HashMap<String, ChatImageFrame>) imagesField.get(null);
                    
                    for (ChatImageFrame frame : images.values()) {
                        if (frame != null && frame.getSiblings().size() > 0) {
                            frame.gifLoop(2);
                        }
                    }
                } catch (Exception e) {
                    // 静默失败，不影响正常功能
                }
            }
        }
        // ========================================================================================

        public static void onClientCommand(RegisterClientCommandsEvent event) {
            CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher = event.getDispatcher();
            LiteralCommandNode<net.minecraft.commands.CommandSourceStack> cmd = dispatcher.register(
                    Commands.literal(MOD_ID)
                            .then(Commands.literal("send")
                                    .then(Commands.argument("name", StringArgumentType.string())
                                            .then(Commands.argument("url", greedyString())
                                                    .executes(SendChatImage.instance)
                                            )
                                    )
                            )
                            .then(Commands.literal("url")
                                    .then(Commands.argument("url", greedyString())
                                            .executes(SendChatImage.instance)
                                    )
                            )
                            .then(Commands.literal("help")
                                    .executes(Help.instance)
                            )
                            .then(Commands.literal("reload")
                                    .executes(ReloadConfig.instance)
                            )

            );
        }

        public static void onKeyInput(net.minecraftforge.client.event.InputEvent.Key event) {
            if (KeyBindings.gatherManaKeyMapping.consumeClick()) {
                Minecraft.getInstance().setScreen(new ConfigScreen(Minecraft.getInstance().screen));
            }
        }

        public static void onClientStaring(RegisterCommandsEvent event) {
        }
    }
}
