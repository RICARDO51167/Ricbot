package ricbot.tool.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class WebToolSupportTest {

    @Test
    void buildClient_acceptsValidProxy() {
        assertDoesNotThrow(() -> WebToolSupport.buildClient("http://127.0.0.1:7890"));
    }

    @Test
    void buildClient_rejectsInvalidProxy() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> WebToolSupport.buildClient("http://:0")
        );

        assertTrue(error.getMessage().contains("无效的代理地址"), error.getMessage());
    }
}
