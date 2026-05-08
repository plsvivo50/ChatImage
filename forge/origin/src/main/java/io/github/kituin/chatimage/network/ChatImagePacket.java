package io.github.kituin.chatimage.network;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import io.github.kituin.ChatImageCode.ChatImageFrame;
import io.github.kituin.ChatImageCode.ChatImageIndex;
import io.github.kituin.ChatImageCode.NetworkHelper;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.kituin.ChatImageCode.ChatImageCodeInstance.LOGGER;
import static io.github.kituin.ChatImageCode.ClientStorage.AddImageError;
import static io.github.kituin.ChatImageCode.ClientStorage.CLIENT_CACHE_MAP;
import static io.github.kituin.ChatImageCode.NetworkHelper.mergeFileBlocks;
import static io.github.kituin.ChatImageCode.ServerStorage.*;

public class ChatImagePacket {

    public static Gson gson = new Gson();

    /**
     * 发送给服务器一连串网络包(异步)
     * 文件频道
     *
     * @param bufs 网络包数据列表
     */
    public static void sendFilePackets(List<String> bufs) {
        for (String buf : bufs) {
            FileChannel.sendToServer(new FileChannelPacket(buf));
        }
    }

    /**
     * 尝试从服务器获取图片
     *
     * @param url 图片url
     */
    public static void loadFromServer(String url) {
        if (Minecraft.getInstance().player != null) {
            FileInfoChannel.sendToServer(new FileInfoChannelPacket(url));
            LOGGER.info("[try get from server]" + url);
        } else {
            AddImageError(url, ChatImageFrame.FrameError.FILE_NOT_FOUND);
        }
    }

    /**
     * 服务端接收 图片文件分块 的处理
     *
     * @param player net.minecraft.server.level.ServerPlayer
     * @param res    String
     */
    public static void serverFileChannelReceived(#ServerPlayer# player, String res) {
        ChatImageIndex title = gson.fromJson(res, ChatImageIndex.class);
        HashMap<Integer, String> blocks = SERVER_BLOCK_CACHE.createBlock(title, res);
        LOGGER.info("[FileChannel->Server:" + title.index + "/" + title.total + "]" + title.url);
        if (title.total == blocks.size()) {
            List<String> names = SERVER_BLOCK_CACHE.getUsers(title.url);
            if (names == null) return;
            // 通知之前请求但是没图片的客户端
            for (String uuid : names) {
                FileBackChannel.sendToPlayer(new FileInfoChannelPacket("true->" + title.url), player.server.getPlayerList().getPlayer(UUID.fromString(uuid)));
                LOGGER.info("[echo to client(" + uuid + ")]" + title.url);
            }
            LOGGER.info("[FileChannel->Server]" + title.url);
        }
    }

    /**
     * 客户端接收 下载文件分块 处理
     *
     * @param res String
     */
    public static void clientDownloadFileChannelReceived(String res) {
        ChatImageIndex title = gson.fromJson(res, ChatImageIndex.class);
        HashMap<Integer, ChatImageIndex> blocks = CLIENT_CACHE_MAP.containsKey(title.url) ? CLIENT_CACHE_MAP.get(title.url) : new HashMap<>();
        blocks.put(title.index, title);
        CLIENT_CACHE_MAP.put(title.url, blocks);
        LOGGER.info("[DownloadFile(" + title.index + "/" + title.total + ")]" + title.url);
        if (blocks.size() == title.total) {
            mergeFileBlocks(title.url, blocks);
            LOGGER.info("[DownloadFileChannel-Merge]" + title.url);
        }
    }

    public static void clientFileInfoChannelReceived(String data) {
        String url = data.substring(6);
        LOGGER.info(url);
        if (data.startsWith("null")) {
            LOGGER.info("[GetFileChannel-NULL]{}", url);
            AddImageError(url, ChatImageFrame.FrameError.FILE_NOT_FOUND);
        } else if (data.startsWith("true")) {
            LOGGER.info("[GetFileChannel-Retry]{}", url);
            loadFromServer(url);
        }
    }

    public static void serverFileInfoChannelReceived(#ServerPlayer# player, String url) {
        HashMap<Integer, String> list = SERVER_BLOCK_CACHE.getBlock(url);

        // ========== 修复：同时支持 file:// URI 和本地绝对路径 ==========
        if (list == null) {
            boolean isLocalPath = url.startsWith("file://")
                    || (url.length() > 1 && url.charAt(1) == ':')   // Windows: D:...
                    || url.startsWith("/");                          // Linux: /home/...
            if (isLocalPath) {
                list = loadLocalFileToCache(url);
            }
        }
        // ============================================================

        if (list != null) {
            for (Map.Entry<Integer, String> entry : list.entrySet()) {
                LOGGER.debug("[GetFileChannel->Client:{}/{}]{}", entry.getKey(), list.size() - 1, url);
                DownloadFileChannel.sendToPlayer(new DownloadFileChannelPacket(entry.getValue()), player);
            }
            LOGGER.info("[GetFileChannel->Client]{}", url);
            return;
        }
        // 通知客户端无文件
        FileBackChannel.sendToPlayer(new FileInfoChannelPacket("null->" + url), player);
        LOGGER.error("[GetFileChannel]not found in server:{}", url);
        if (player != null) {
            SERVER_BLOCK_CACHE.tryAddUser(url, player.getStringUUID());
        }
        LOGGER.info("[GetFileChannel]记录uuid:{}", player.getStringUUID());
        LOGGER.info("[not found in server]{}", url);
    }

    /**
     * 读取本地文件（支持 file:// URI 和纯本地路径），使用 ChatImage 标准分包逻辑塞入 ServerBlockCache
     */
    private static HashMap<Integer, String> loadLocalFileToCache(String fileUrl) {
        try {
            java.io.File file;
            
            // 区分 file:// URI 和纯本地路径
            if (fileUrl.startsWith("file://")) {
                java.net.URI uri = new java.net.URI(fileUrl);
                java.nio.file.Path path = java.nio.file.Paths.get(uri);
                file = path.toFile();
            } else {
                file = new java.io.File(fileUrl);
            }

            if (!file.exists()) {
                LOGGER.error("[FileFallback]本地文件不存在:{}", file.getAbsolutePath());
                return null;
            }

            // 使用 NetworkHelper 的标准分包逻辑（自动处理大文件分包）
            List<String> packets = NetworkHelper.createFilePacket(fileUrl, file);
            if (packets.isEmpty()) {
                LOGGER.error("[FileFallback]文件分包失败:{}", fileUrl);
                return null;
            }

            // 将分包塞入 ServerBlockCache
            for (String packet : packets) {
                ChatImageIndex index = gson.fromJson(packet, ChatImageIndex.class);
                SERVER_BLOCK_CACHE.createBlock(index, packet);
            }

            LOGGER.info("[FileFallback]本地文件已加载到缓存:{} ({} bytes, {} packets)",
                    fileUrl, file.length(), packets.size());

            // 返回完整的块列表
            return SERVER_BLOCK_CACHE.getBlock(fileUrl);

        } catch (Exception e) {
            LOGGER.error("[FileFallback]加载本地文件失败:{} - {}", fileUrl, e.getLocalizedMessage());
            return null;
        }
    }
}
