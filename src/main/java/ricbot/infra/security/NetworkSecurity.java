package ricbot.infra.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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
     * 用于匹配字符串中 http 或 https 开头的 URL 的正则表达式。
     */
    private static final Pattern URL_RE =
            Pattern.compile("https?://[^\\s\\\"'`;|<>]+", Pattern.CASE_INSENSITIVE);

    /**
     * 对应 Python: _BLOCKED_NETWORKS
     *
     * 这里把常见 IPv4/IPv6 私网、回环、链路本地等全部列出来。
     * 这些网段被视为不安全，默认拦截。
     */
    private static final List<CidrBlock> BLOCKED_NETWORKS = List.of(
            new CidrBlock("0.0.0.0/8"),       // 当前网络
            new CidrBlock("10.0.0.0/8"),      // 私有 A 类网络
            new CidrBlock("100.64.0.0/10"),   // carrier-grade NAT (CGNAT)
            new CidrBlock("127.0.0.0/8"),     // 本地回环地址
            new CidrBlock("169.254.0.0/16"),  // 链路本地地址 / 云元数据服务常用段
            new CidrBlock("172.16.0.0/12"),   // 私有 B 类网络
            new CidrBlock("192.168.0.0/16"),  // 私有 C 类网络
            new CidrBlock("::1/128"),         // IPv6 本地回环
            new CidrBlock("fc00::/7"),        // IPv6 唯一本地地址 (ULA)
            new CidrBlock("fe80::/10")        // IPv6 链路本地地址
    );

    /**
     * 对应 Python: _allowed_networks
     *
     * 允许放行的 CIDR 白名单，例如 Tailscale 内网段。
     * 使用 volatile 保证多线程可见性。
     */
    private static volatile List<CidrBlock> allowedNetworks = new ArrayList<>();

    // 私有构造函数，防止实例化
    private NetworkSecurity() {
    }

    // =========================================================
    // Public API
    // =========================================================

    /**
     * 对应 Python: configure_ssrf_whitelist(cidrs)
     *
     * 配置允许绕过 SSRF 限制的白名单网段。
     *
     * @param cidrs CIDR 格式的网段列表，例如 ["10.0.0.0/8"]
     */
    public static void configureSsrfWhitelist(List<String> cidrs) {
        List<CidrBlock> nets = new ArrayList<>();
        if (cidrs != null) {
            for (String cidr : cidrs) {
                try {
                    // 尝试解析 CIDR，如果格式非法则跳过，保持与 Python 行为一致
                    nets.add(new CidrBlock(cidr));
                } catch (Exception ignored) {
                    // 保持与 Python 一致：非法 CIDR 直接跳过
                }
            }
        }
        // 原子性替换白名单列表
        allowedNetworks = nets;
    }

    /**
     * 对应 Python: validate_url_target(url)
     *
     * 校验一个 URL 是否安全可访问。
     *
     * @param url 待校验的 URL 字符串
     * @return ValidationResult 包含校验结果状态和错误信息
     * - ok = true 时 message 为空
     * - ok = false 时 message 为错误原因
     */
    public static ValidationResult validateUrlTarget(String url) {
        URI uri;
        try {
            // 尝试解析 URL
            uri = URI.create(url);
        } catch (Exception e) {
            // URL 格式错误，直接返回失败
            return ValidationResult.fail(e.getMessage());
        }

        // 获取协议方案
        String scheme = uri.getScheme();
        // 仅允许 http 和 https 协议
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return ValidationResult.fail("仅允许 http/https 协议，当前协议: '" + (scheme != null ? scheme : "无") + "'");
        }

        // 获取主机名
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // Java URI 对某些奇怪 URL 可能 host 为空但 authority 有值
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank()) {
                return ValidationResult.fail("缺少域名");
            }
            return ValidationResult.fail("缺少主机名");
        }

        InetAddress[] addresses;
        try {
            // 解析主机名为 IP 地址数组
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // DNS 解析失败
            return ValidationResult.fail("无法解析主机名: " + host);
        }

        // 检查所有解析出的 IP 地址
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                // 如果任一 IP 属于私有或内部地址，则拦截
                return ValidationResult.fail(
                        "已拦截: " + host + " 解析为私有/内部地址 " + address.getHostAddress()
                );
            }
        }

        // 所有检查通过
        return ValidationResult.success();
    }

    /**
     * 对应 Python: validate_resolved_url(url)
     *
     * 用于“已经获取到跳转 URL”的场景。
     * 优先直接检查 host 是否是 IP，如果不是再做 DNS 解析。
     *
     * @param url 待校验的重定向 URL
     * @return ValidationResult 校验结果
     */
    public static ValidationResult validateResolvedUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            // 保持 Python 语义：解析失败时不拦，视为成功（或由上层处理）
            return ValidationResult.success();
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ValidationResult.success();
        }

        // 先尝试把 hostname 当成裸 IP 解析，避免不必要的 DNS 查询
        InetAddress directIp = parseLiteralIp(host);
        if (directIp != null) {
            // 如果是 IP 地址，检查是否为私有地址
            if (isPrivate(directIp)) {
                return ValidationResult.fail("重定向目标是私有地址: " + directIp.getHostAddress());
            }
            return ValidationResult.success();
        }

        // 否则当作域名进行 DNS 解析
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isPrivate(address)) {
                    return ValidationResult.fail(
                            "重定向目标 " + host + " 解析为私有地址 " + address.getHostAddress()
                    );
                }
            }
        } catch (UnknownHostException ignored) {
            // 保持 Python 语义：解析失败不拦，视为成功
            return ValidationResult.success();
        }

        return ValidationResult.success();
    }

    /**
     * 对应 Python: contains_internal_url(command)
     *
     * 扫描命令字符串中的 URL，只要有一个 URL 被 validateUrlTarget 判定不安全，就返回 true。
     *
     * @param command 待扫描的命令字符串
     * @return 如果包含内部/不安全 URL 返回 true，否则返回 false
     */
    public static boolean containsInternalUrl(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }

        // 使用正则查找所有 URL
        Matcher matcher = URL_RE.matcher(command);
        while (matcher.find()) {
            String url = matcher.group();
            // 对每个找到的 URL 进行安全性校验
            ValidationResult result = validateUrlTarget(url);
            if (!result.ok()) {
                // 发现不安全 URL，立即返回 true
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
     * 判断给定的 IP 地址是否属于私有或内部网络。
     * 若命中 allowlist（白名单），则不视为 private。
     *
     * @param address 待检查的 IP 地址
     * @return 如果是私有/内部地址且不在白名单中，返回 true
     */
    private static boolean isPrivate(InetAddress address) {
        // 获取当前的白名单快照
        List<CidrBlock> currentAllow = allowedNetworks;
        if (currentAllow != null && !currentAllow.isEmpty()) {
            for (CidrBlock net : currentAllow) {
                if (net.contains(address)) {
                    // 在白名单中，视为非私有（安全）
                    return false;
                }
            }
        }

        // 检查是否在黑名单（Blocked Networks）中
        for (CidrBlock blocked : BLOCKED_NETWORKS) {
            if (blocked.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尝试把 host 解析成字面量 IP，不做 DNS。
     * 仅在 host 看起来像 IP 地址时尝试解析，避免对域名触发 DNS 查询。
     *
     * @param host 主机名字符串
     * @return 如果是合法的字面量 IP 则返回 InetAddress，否则返回 null
     */
    private static InetAddress parseLiteralIp(String host) {
        try {
            // 只在看起来像 IP 时走，避免域名触发 DNS
            // 检查是否包含 ':' (IPv6) 或匹配 IPv4 格式
            if (host.contains(":") || host.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                return InetAddress.getByName(host);
            }
        } catch (Exception ignored) {
            // 解析失败，忽略
        }
        return null;
    }

    // =========================================================
    // DTO
    // =========================================================

    /**
     * URL 校验结果记录类
     *
     * @param ok      校验是否通过
     * @param message 错误消息，ok 为 true 时通常为空
     */
    public record ValidationResult(boolean ok, String message) {
        /**
         * 创建成功的校验结果
         */
        public static ValidationResult success() {
            return new ValidationResult(true, "");
        }

        /**
         * 创建失败的校验结果
         *
         * @param message 错误描述
         */
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
     * 1. 解析类似 192.168.0.0/16 的 CIDR 表示法
     * 2. 判断某个 InetAddress 是否属于该网段
     */
    public static final class CidrBlock {
        private final InetAddress networkAddress; // 网络地址
        private final int prefixLength;           // 前缀长度
        private final byte[] networkBytes;        // 规范化后的网络字节数组

        /**
         * 构造 CIDR 块
         *
         * @param cidr CIDR 字符串，例如 "192.168.1.0/24"
         * @throws IllegalArgumentException 如果 CIDR 格式无效
         */
        public CidrBlock(String cidr) {
            if (cidr == null || !cidr.contains("/")) {
                throw new IllegalArgumentException("无效的 CIDR: " + cidr);
            }

            // 分割 IP 和前缀长度
            String[] parts = cidr.trim().split("/", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("无效的 CIDR: " + cidr);
            }

            try {
                // 解析网络地址
                this.networkAddress = InetAddress.getByName(parts[0]);
            } catch (Exception e) {
                throw new IllegalArgumentException("无效的 CIDR 地址: " + cidr, e);
            }

            try {
                // 解析前缀长度
                this.prefixLength = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("无效的 CIDR 前缀: " + cidr, e);
            }

            // 验证前缀长度是否在有效范围内 (0 到 地址总位数)
            int maxBits = this.networkAddress.getAddress().length * 8;
            if (prefixLength < 0 || prefixLength > maxBits) {
                throw new IllegalArgumentException("CIDR 前缀超出范围: " + cidr);
            }

            // 计算并存储规范化后的网络字节数组
            this.networkBytes = normalizeNetwork(this.networkAddress.getAddress(), prefixLength);
        }

        /**
         * 判断给定地址是否在此 CIDR 块内
         *
         * @param address 待检查的 IP 地址
         * @return 如果在网段内返回 true
         */
        public boolean contains(InetAddress address) {
            if (address == null) {
                return false;
            }

            byte[] addrBytes = address.getAddress();
            byte[] netBytes = networkAddress.getAddress();

            // IPv4 CIDR 不匹配 IPv6 地址，反之亦然，字节长度必须一致
            if (addrBytes.length != netBytes.length) {
                return false;
            }

            // 对待检查地址进行同样的规范化处理
            byte[] normalizedAddr = normalizeNetwork(addrBytes, prefixLength);
            // 比较规范化后的字节数组是否相等
            return Arrays.equals(networkBytes, normalizedAddr);
        }

        /**
         * 根据前缀长度规范化 IP 地址字节数组
         * 将主机位清零，只保留网络位
         *
         * @param address      IP 地址字节数组
         * @param prefixLength 前缀长度
         * @return 规范化后的字节数组
         */
        private static byte[] normalizeNetwork(byte[] address, int prefixLength) {
            // 复制原始字节数组
            byte[] out = Arrays.copyOf(address, address.length);

            int fullBytes = prefixLength / 8;   // 完整的字节数
            int remainBits = prefixLength % 8;  // 剩余位数

            if (fullBytes < out.length) {
                // 处理剩余位所在的字节
                if (remainBits != 0) {
                    // 创建掩码，例如前缀剩 3 位，则掩码为 11100000 (0xE0)
                    int mask = (0xFF << (8 - remainBits)) & 0xFF;
                    out[fullBytes] = (byte) (out[fullBytes] & mask);
                    fullBytes++;
                }
                // 将后续所有字节清零
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
