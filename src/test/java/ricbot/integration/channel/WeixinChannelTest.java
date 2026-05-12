package ricbot.integration.channel;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.domain.message.InboundMessage;
import ricbot.domain.message.MessageBus;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeixinChannelTest {

    @Test
    void start_loadsStateAndPollsInboundTextMessage(@TempDir Path workspace) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        CountDownLatch polled = new CountDownLatch(1);
        server.createContext("/getupdates", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "errcode": 0,
                      "buf": "buf-2",
                      "messages": [
                        {
                          "message_id": "msg-1",
                          "message_type": 1,
                          "from_user_id": "user-1",
                          "items": [
                            {"item_type": 1, "content": "hello"}
                          ]
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            polled.countDown();
        });
        server.start();

        WeixinChannel channel = null;
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Path stateDir = workspace.resolve("state");
            Files.createDirectories(stateDir);
            Files.writeString(stateDir.resolve("account.json"), """
                    {
                      "token": "state-token",
                      "get_updates_buf": "buf-1",
                      "base_url": "%s",
                      "typing_tickets": {"user-1": {"ticket": "typing-ticket"}}
                    }
                    """.formatted(baseUrl));

            WeixinChannel.WeixinConfig config = new WeixinChannel.WeixinConfig();
            config.setAllowFrom(List.of("*"));
            config.setStateDir(stateDir.toString());

            MessageBus bus = new MessageBus();
            channel = new WeixinChannel(config, bus);
            channel.start();

            InboundMessage inbound = bus.consumeInbound(2, TimeUnit.SECONDS);
            assertNotNull(inbound);
            assertTrue(polled.await(1, TimeUnit.SECONDS));
            assertEquals("weixin", inbound.getChannel());
            assertEquals("user-1", inbound.getSenderId());
            assertEquals("user-1", inbound.getChatId());
            assertEquals("hello", inbound.getContent());
            assertEquals("msg-1", inbound.getMetadata().get("weixin_message_id"));
            assertEquals("Bearer state-token", authorization.get());
            assertTrue(requestBody.get().contains("\"buf\":\"buf-1\""));
        } finally {
            if (channel != null) {
                channel.stop();
            }
            server.stop(0);
        }
    }

    @Test
    void start_respectsAllowFrom(@TempDir Path workspace) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch polled = new CountDownLatch(1);
        server.createContext("/getupdates", exchange -> {
            byte[] response = """
                    {
                      "errcode": 0,
                      "messages": [
                        {
                          "message_id": "msg-blocked",
                          "message_type": 1,
                          "from_user_id": "blocked-user",
                          "items": [
                            {"item_type": 1, "content": "blocked"}
                          ]
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            polled.countDown();
        });
        server.start();

        WeixinChannel channel = null;
        try {
            WeixinChannel.WeixinConfig config = new WeixinChannel.WeixinConfig();
            config.setAllowFrom(List.of("allowed-user"));
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            config.setToken("token");
            config.setStateDir(workspace.resolve("state").toString());

            MessageBus bus = new MessageBus();
            channel = new WeixinChannel(config, bus);
            channel.start();

            assertTrue(polled.await(2, TimeUnit.SECONDS));
            assertNull(bus.consumeInbound(200, TimeUnit.MILLISECONDS));
        } finally {
            if (channel != null) {
                channel.stop();
            }
            server.stop(0);
        }
    }
}
