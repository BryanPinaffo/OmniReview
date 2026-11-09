package com.omnireview.backend;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Endereço de quem fez a requisição, para os limites de uso e o bloqueio de login.
 *
 * Em produção o acesso chega pelo Tailscale Funnel, que repassa a conexão de dentro da rede do Docker:
 * o endereço direto (remoteAddr) é sempre o do proxy. O proxy acrescenta o IP real no fim de
 * X-Forwarded-For, então só confiamos nesse cabeçalho quando a conexão vem de um endereço privado
 * (proxy da rede local / Docker / Tailscale) e usamos o ÚLTIMO valor (o que o proxy escreveu; os anteriores
 * podem ter sido inventados por quem fez a requisição).
 */
final class ClienteIp {

    private ClienteIp() {}

    static String de(HttpServletRequest req) {
        String direto = req.getRemoteAddr();
        String encaminhado = req.getHeader("X-Forwarded-For");
        if (encaminhado != null && !encaminhado.isBlank() && enderecoInterno(direto)) {
            String[] partes = encaminhado.split(",");
            String ultimo = partes[partes.length - 1].strip();
            if (!ultimo.isEmpty() && ultimo.length() <= 45 && ultimo.matches("[0-9a-fA-F:.]+")) return ultimo;
        }
        return direto == null ? "?" : direto;
    }

    /** Loopback, redes privadas (10/8, 172.16/12, 192.168/16), faixa do Tailscale (100.64/10) e IPv6 local. */
    static boolean enderecoInterno(String ip) {
        if (ip == null) return false;
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if (ip.startsWith("fc") || ip.startsWith("fd") || ip.startsWith("fe80")) return true;
        String[] o = ip.split("\\.");
        if (o.length != 4) return false;
        try {
            int a = Integer.parseInt(o[0]), b = Integer.parseInt(o[1]);
            return (a == 172 && b >= 16 && b <= 31) || (a == 100 && b >= 64 && b <= 127);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
