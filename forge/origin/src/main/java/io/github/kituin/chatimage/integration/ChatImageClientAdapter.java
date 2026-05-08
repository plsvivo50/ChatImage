package io.github.kituin.chatimage.integration;

import io.github.kituin.ChatImageCode.ChatImageFrame;
import io.github.kituin.ChatImageCode.IClientAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static io.github.kituin.chatimage.ChatImage.CONFIG;
import static io.github.kituin.chatimage.ChatImage.MOD_ID;
import static io.github.kituin.chatimage.network.ChatImagePacket.loadFromServer;
import static io.github.kituin.chatimage.network.ChatImagePacket.sendFilePackets;
import static io.github.kituin.ChatImageCode.NetworkHelper.createFilePacket;
import static io.github.kituin.chatimage.tool.SimpleUtil.createTranslatableComponent;

public class ChatImageClientAdapter implements IClientAdapter {
    @Override
    public int getTimeOut() {
        return CONFIG.timeout;
    }

    @Override
    public ChatImageFrame.TextureReader<net.minecraft.resources.ResourceLocation> loadTexture(InputStream image) throws IOException {
        com.mojang.blaze3d.platform.NativeImage nativeImage = loadNativeImage(image);
        if (nativeImage == null) {
            throw new IOException("Failed to load image into NativeImage");
        }
        return new ChatImageFrame.TextureReader<>(
                Minecraft.getInstance().getTextureManager().register(MOD_ID + "/chatimage",
                        new DynamicTexture(nativeImage)),
                nativeImage.getWidth(),
                nativeImage.getHeight()
        );
    }

    /**
     * 兼容加载各种格式的图片为 NativeImage
     */
    private static com.mojang.blaze3d.platform.NativeImage loadNativeImage(InputStream image) throws IOException {
        com.mojang.blaze3d.platform.NativeImage nativeImage = null;

        // 先尝试标准 NativeImage.read()
        try {
            nativeImage = com.mojang.blaze3d.platform.NativeImage.read(image);
            if (nativeImage != null) return nativeImage;
        } catch (Exception ignored) {
        }

        // 失败时尝试用 ImageIO 中转转换
        try {
            image.reset();
            java.awt.image.BufferedImage bufImage = javax.imageio.ImageIO.read(image);
            if (bufImage == null) return null;

            // 转换为标准 ARGB 格式
            java.awt.image.BufferedImage rgbImage = new java.awt.image.BufferedImage(
                    bufImage.getWidth(), bufImage.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = rgbImage.createGraphics();
            g.drawImage(bufImage, 0, 0, null);
            g.dispose();

            // 写出为 PNG 再读入 NativeImage
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            if (javax.imageio.ImageIO.write(rgbImage, "png", baos)) {
                nativeImage = com.mojang.blaze3d.platform.NativeImage.read(
                        new ByteArrayInputStream(baos.toByteArray()));
            }
        } catch (Exception e) {
            return nativeImage;
        }

        return nativeImage;
    }

    @Override
    public void sendToServer(String url, File file, boolean isToServer) {
        if (isToServer) {
            List<String> bufs = createFilePacket(url, file);
            sendFilePackets(bufs);
        } else {
            loadFromServer(url);
        }
    }

    @Override
    public void checkCachePath() {
        File folder = new File(CONFIG.cachePath);
        if (!folder.exists()) {
            folder.mkdirs();
        }
    }

    @Override
    public int getMaxFileSize() {
        return CONFIG.MaxFileSize;
    }

    @Override
    public net.minecraft.network.chat.MutableComponent getProcessMessage(int i)  {
        return createTranslatableComponent("process.chatimage.message", i);
    }
}
