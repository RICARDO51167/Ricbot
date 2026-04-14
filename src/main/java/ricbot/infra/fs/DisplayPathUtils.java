package ricbot.infra.fs;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public final class DisplayPathUtils {

    private DisplayPathUtils() {
    }

    public static String abbreviatePath(String path, int maxLen) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (maxLen <= 0) {
            maxLen = 40;
        }

        if (path.startsWith("http://") || path.startsWith("https://")) {
            return abbreviateUrl(path, maxLen);
        }

        String normalized = path.replace("\\", "/");
        String home = System.getProperty("user.home").replace("\\", "/");
        if (normalized.startsWith(home + "/")) {
            normalized = "~" + normalized.substring(home.length());
        } else if (normalized.equals(home)) {
            normalized = "~";
        }

        if (normalized.length() <= maxLen) {
            return normalized;
        }

        String[] parts = normalized.replaceAll("/+$", "").split("/");
        if (parts.length <= 1) {
            return normalized.substring(0, Math.max(0, maxLen - 1)) + "…";
        }

        String basename = parts[parts.length - 1];
        int budget = maxLen - basename.length() - 3; // …/ + /

        List<String> kept = new ArrayList<>();
        for (int i = parts.length - 2; i >= 0; i--) {
            String seg = parts[i];
            int needed = seg.length() + 1;
            if (budget >= needed) {
                kept.add(0, seg);
                budget -= needed;
            } else {
                break;
            }
        }

        if (!kept.isEmpty()) {
            return "…/" + String.join("/", kept) + "/" + basename;
        }
        return "…/" + basename;
    }

    private static String abbreviateUrl(String url, int maxLen) {
        if (url.length() <= maxLen) {
            return url;
        }
        try {
            URI uri = URI.create(url);
            String domain = uri.getHost() != null ? uri.getHost() : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            String[] parts = path.split("/");
            String filename = parts.length > 0 ? parts[parts.length - 1] : "";

            String prefix = uri.getScheme() + "://" + domain + "/";
            String out = prefix + filename;
            if (out.length() <= maxLen) {
                return out;
            }
            if (prefix.length() + 2 < maxLen) {
                return prefix + "…" + filename.substring(Math.max(0, filename.length() - (maxLen - prefix.length() - 1)));
            }
            return url.substring(0, Math.max(0, maxLen - 1)) + "…";
        } catch (Exception e) {
            return url.substring(0, Math.max(0, maxLen - 1)) + "…";
        }
    }
}