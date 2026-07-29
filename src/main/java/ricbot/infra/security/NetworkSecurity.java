package ricbot.infra.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络安全工具类，提供 SSRF 防护和 URL 校验。
 */
public final class NetworkSecurity {

    private static final Pattern URL_RE =
            Pattern.compile("https?://[^\\s\\\"'`;|<>]+", Pattern.CASE_INSENSITIVE);

    private static final List<CidrBlock> BLOCKED_NETWORKS = List.of(
            new CidrBlock("0.0.0.0/8"),
            new CidrBlock("10.0.0.0/8"),
            new CidrBlock("100.64.0.0/10"),
            new CidrBlock("127.0.0.0/8"),
            new CidrBlock("169.254.0.0/16"),
            new CidrBlock("172.16.0.0/12"),
            new CidrBlock("192.168.0.0/16"),
            new CidrBlock("::1/128"),
            new CidrBlock("fc00::/7"),
            new CidrBlock("fe80::/10")
    );

    private NetworkSecurity() {
    }

    public static ValidationResult validateUrlTarget(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return ValidationResult.fail(e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return ValidationResult.fail("仅允许 http/https 协议，当前协议: '" + (scheme != null ? scheme : "无") + "'");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank()) {
                return ValidationResult.fail("缺少域名");
            }
            return ValidationResult.fail("缺少主机名");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return ValidationResult.fail("无法解析主机名: " + host);
        }

        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                return ValidationResult.fail(
                        "已拦截: " + host + " 解析为私有/内部地址 " + address.getHostAddress()
                );
            }
        }

        return ValidationResult.success();
    }

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

    private static boolean isPrivate(InetAddress address) {
        for (CidrBlock blocked : BLOCKED_NETWORKS) {
            if (blocked.contains(address)) {
                return true;
            }
        }
        return false;
    }

    public record ValidationResult(boolean ok, String message) {
        public static ValidationResult success() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult fail(String message) {
            return new ValidationResult(false, message != null ? message : "");
        }
    }

    public static final class CidrBlock {
        private final InetAddress networkAddress;
        private final int prefixLength;
        private final byte[] networkBytes;

        public CidrBlock(String cidr) {
            if (cidr == null || !cidr.contains("/")) {
                throw new IllegalArgumentException("无效的 CIDR: " + cidr);
            }

            String[] parts = cidr.trim().split("/", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("无效的 CIDR: " + cidr);
            }

            try {
                this.networkAddress = InetAddress.getByName(parts[0]);
            } catch (Exception e) {
                throw new IllegalArgumentException("无效的 CIDR 地址: " + cidr, e);
            }

            try {
                this.prefixLength = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("无效的 CIDR 前缀: " + cidr, e);
            }

            int maxBits = this.networkAddress.getAddress().length * 8;
            if (prefixLength < 0 || prefixLength > maxBits) {
                throw new IllegalArgumentException("CIDR 前缀超出范围: " + cidr);
            }

            this.networkBytes = normalizeNetwork(this.networkAddress.getAddress(), prefixLength);
        }

        public boolean contains(InetAddress address) {
            if (address == null) {
                return false;
            }

            byte[] addrBytes = address.getAddress();
            byte[] netBytes = networkAddress.getAddress();

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
