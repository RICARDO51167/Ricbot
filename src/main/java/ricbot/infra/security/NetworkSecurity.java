package ricbot.infra.security;

import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对应 Python: network.py
 *
 * 主要目标：
 * 1. SSRF 防护
 * 2. 校验 URL 是否指向私网/内网地址
 * 3. 支持 CIDR 白名单
 * 4. 扫描命令字符串里的 URL 是否为内部地址
 *
 * 对应文件: network.py
 */
public final class NetworkSecurity {

    /**
     * 对应 Python: _URL_RE
     */
    private static final Pattern URL_RE =
            Pattern.compile("https?://[^\\s\\\"'`;|<>]+", Pattern.CASE_INSENSITIVE);

    /**
     * 对应 Python: _BLOCKED_NETWORKS
     *
     * 这里把常见 IPv4/IPv6 私网、回环、链路本地等全部列出来。
     */
    private static final List<CidrBlock> BLOCKED_NETWORKS = List.of(
            new CidrBlock("0.0.0.0/8"),
            new CidrBlock("10.0.0.0/8"),
            new CidrBlock("100.64.0.0/10"),   // carrier-grade NAT
            new CidrBlock("127.0.0.0/8"),
            new CidrBlock("169.254.0.0/16"),  // link-local / cloud metadata
            new CidrBlock("172.16.0.0/12"),
            new CidrBlock("192.168.0.0/16"),
            new CidrBlock("::1/128"),
            new CidrBlock("fc00::/7"),        // unique local
            new CidrBlock("fe80::/10")        // link-local v6
    );

    /**
     * 对应 Python: _allowed_networks
     *
     * 允许放行的 CIDR，例如 Tailscale 内网段。
     */
    private static volatile List<CidrBlock> allowedNetworks = new ArrayList<>();

    private NetworkSecurity() {
    }

    // =========================================================
    // Public API
    // =========================================================

    /**
     * 对应 Python: configure_ssrf_whitelist(cidrs)
     *
     * 配置允许绕过 SSRF 限制的白名单网段。
     */
    public static void configureSsrfWhitelist(List<String> cidrs) {
        List<CidrBlock> nets = new ArrayList<>();
        if (cidrs != null) {
            for (String cidr : cidrs) {
                try {
                    nets.add(new CidrBlock(cidr));
                } catch (Exception ignored) {
                    // 保持与 Python 一致：非法 CIDR 直接跳过
                }
            }
        }
        allowedNetworks = nets;
    }

    /**
     * 对应 Python: validate_url_target(url)
     *
     * 校验一个 URL 是否安全可访问。
     *
     * 返回:
     * - ok = true 时 message 为空
     * - ok = false 时 message 为错误原因
     */
    public static ValidationResult validateUrlTarget(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return ValidationResult.fail(e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return ValidationResult.fail("Only http/https allowed, got '" + (scheme != null ? scheme : "none") + "'");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // Java URI 对某些奇怪 URL 可能 host 为空但 authority 有值
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank()) {
                return ValidationResult.fail("Missing domain");
            }
            return ValidationResult.fail("Missing hostname");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return ValidationResult.fail("Cannot resolve hostname: " + host);
        }

        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                return ValidationResult.fail(
                        "Blocked: " + host + " resolves to private/internal address " + address.getHostAddress()
                );
            }
        }

        return ValidationResult.success();
    }

    /**
     * 对应 Python: validate_resolved_url(url)
     *
     * 用于“已经获取到跳转 URL”的场景。
     * 优先直接检查 host 是否是 IP，如果不是再做 DNS 解析。
     */
    public static ValidationResult validateResolvedUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            // 保持 Python 语义：解析失败时不拦
            return ValidationResult.success();
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ValidationResult.success();
        }

        // 先尝试把 hostname 当成裸 IP
        InetAddress directIp = parseLiteralIp(host);
        if (directIp != null) {
            if (isPrivate(directIp)) {
                return ValidationResult.fail("Redirect target is a private address: " + directIp.getHostAddress());
            }
            return ValidationResult.success();
        }

        // 否则当作域名解析
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isPrivate(address)) {
                    return ValidationResult.fail(
                            "Redirect target " + host + " resolves to private address " + address.getHostAddress()
                    );
                }
            }
        } catch (UnknownHostException ignored) {
            // 保持 Python 语义：解析失败不拦
            return ValidationResult.success();
        }

        return ValidationResult.success();
    }

    /**
     * 对应 Python: contains_internal_url(command)
     *
     * 扫描命令字符串中的 URL，只要有一个 URL 被 validateUrlTarget 判定不安全，就返回 true。
     */
    public static boolean containsInternalUrl(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }

        Matcher matcher = URL_RE.matcher(command);
        while (matcher.find()) {
            String url = matcher.group();
            ValidationResult result = validateUrlTarget(url);
            if (!result.ok()) {
                return true;
            }
        }
        return false;
    }

    // =========================================================
    // Internal helpers
    // =========================================================

    /**
     * 对应 Python: _is_private(addr)
     *
     * 若命中 allowlist，则不视为 private。
     */
    private static boolean isPrivate(InetAddress address) {
        List<CidrBlock> currentAllow = allowedNetworks;
        if (currentAllow != null && !currentAllow.isEmpty()) {
            for (CidrBlock net : currentAllow) {
                if (net.contains(address)) {
                    return false;
                }
            }
        }

        for (CidrBlock blocked : BLOCKED_NETWORKS) {
            if (blocked.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尝试把 host 解析成字面量 IP，不做 DNS。
     */
    private static InetAddress parseLiteralIp(String host) {
        try {
            // 只在看起来像 IP 时走，避免域名触发 DNS
            if (host.contains(":") || host.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                return InetAddress.getByName(host);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // =========================================================
    // DTO
    // =========================================================

    /**
     * URL 校验结果
     */
    public record ValidationResult(boolean ok, String message) {
        public static ValidationResult success() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult fail(String message) {
            return new ValidationResult(false, message != null ? message : "");
        }
    }

    // =========================================================
    // CIDR block
    // =========================================================

    /**
     * 一个轻量 CIDR 实现，支持 IPv4 / IPv6。
     *
     * 作用：
     * 1. 解析类似 192.168.0.0/16
     * 2. 判断某个 InetAddress 是否属于该网段
     */
    public static final class CidrBlock {
        private final InetAddress networkAddress;
        private final int prefixLength;
        private final byte[] networkBytes;

        public CidrBlock(String cidr) {
            if (cidr == null || !cidr.contains("/")) {
                throw new IllegalArgumentException("Invalid CIDR: " + cidr);
            }

            String[] parts = cidr.trim().split("/", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid CIDR: " + cidr);
            }

            try {
                this.networkAddress = InetAddress.getByName(parts[0]);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid CIDR address: " + cidr, e);
            }

            try {
                this.prefixLength = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR prefix: " + cidr, e);
            }

            int maxBits = this.networkAddress.getAddress().length * 8;
            if (prefixLength < 0 || prefixLength > maxBits) {
                throw new IllegalArgumentException("CIDR prefix out of range: " + cidr);
            }

            this.networkBytes = normalizeNetwork(this.networkAddress.getAddress(), prefixLength);
        }

        public boolean contains(InetAddress address) {
            if (address == null) {
                return false;
            }

            byte[] addrBytes = address.getAddress();
            byte[] netBytes = networkAddress.getAddress();

            // IPv4 CIDR 不匹配 IPv6 地址，反之亦然
            if (addrBytes.length != netBytes.length) {
                return false;
            }

            byte[] normalizedAddr = normalizeNetwork(addrBytes, prefixLength);
            return Arrays.equals(networkBytes, normalizedAddr);
        }

        private static byte[] normalizeNetwork(byte[] address, int prefixLength) {
            byte[] out = Arrays.copyOf(address, address.length);

            int fullBytes = prefixLength / 8;
            int remainBits = prefixLength % 8;

            if (fullBytes < out.length) {
                if (remainBits != 0) {
                    int mask = (0xFF << (8 - remainBits)) & 0xFF;
                    out[fullBytes] = (byte) (out[fullBytes] & mask);
                    fullBytes++;
                }
                for (int i = fullBytes; i < out.length; i++) {
                    out[i] = 0;
                }
            }

            return out;
        }

        @Override
        public String toString() {
            return networkAddress.getHostAddress() + "/" + prefixLength;
        }
    }
}
