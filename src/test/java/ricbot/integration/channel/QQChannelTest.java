package ricbot.integration.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.message.MessageBus;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class QQChannelTest {

    @Test
    void localMedia_isRestrictedToWhitelistedRoots(@TempDir Path workspace) throws Exception {
        QQChannel.QQConfig config = new QQChannel.QQConfig();
        Path mediaDir = workspace.resolve("media");
        config.setMediaDir(mediaDir.toString());

        QQChannel channel = new QQChannel(config, new MessageBus());

        Files.createDirectories(mediaDir);
        Path allowed = mediaDir.resolve("allowed.png");
        Files.writeString(allowed, "ok");

        Path outside = workspace.resolveSibling("outside.png");
        Files.writeString(outside, "nope");

        assertEquals(allowed.toAbsolutePath().normalize(), channel.resolveLocalMediaPath("allowed.png"));
        assertTrue(channel.isAllowedLocalMediaPath(allowed));
        assertFalse(channel.isAllowedLocalMediaPath(outside));
        assertFalse(channel.validateRemoteMediaUrl("http://127.0.0.1/private").ok());
        assertTrue(channel.validateRemoteMediaUrl("https://8.8.8.8/image.png").ok());
    }
}
