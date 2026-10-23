package com.example.facetofacetranslator;

import java.net.URI;

final class GatewayProtocol {
    static String baseUrl(String value) {
        try {
            URI url = new URI(value.trim());
            String host = url.getHost();
            boolean local = "127.0.0.1".equals(host) || "localhost".equals(host) || isPrivateIp(host);
            if (host == null || url.getRawUserInfo() != null || url.getRawQuery() != null
                    || url.getRawFragment() != null || (url.getPort() != -1 && (url.getPort() < 1 || url.getPort() > 65535))
                    || !("https".equals(url.getScheme()) || local && "http".equals(url.getScheme()))
                    || !(url.getPath().isEmpty() || "/".equals(url.getPath()))) throw new IllegalArgumentException();
            String normalized = value.trim();
            return normalized.endsWith("/") ? normalized.substring(0, normalized.length()-1) : normalized;
        } catch (Exception error) { throw new IllegalArgumentException("网关需 HTTPS 域名；本地测试可用 http://127.0.0.1:8765"); }
    }
    private static boolean isPrivateIp(String host) {
        if (host == null) return false;
        String[] p = host.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            if (a == 10) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
        } catch (NumberFormatException ignored) { return false; }
        return false;
    }
    static String number(String value) {
        String clean = value.trim().replace(" ", "").replace("-", "");
        if (!clean.matches("\\+[1-9][0-9]{7,14}")) throw new IllegalArgumentException("请填写国际号码，例如 +880… 或 +60…");
        if (!clean.startsWith("+880") && !clean.startsWith("+60")) throw new IllegalArgumentException("当前仅开放孟加拉国 +880 和马来西亚 +60");
        return clean;
    }
}
