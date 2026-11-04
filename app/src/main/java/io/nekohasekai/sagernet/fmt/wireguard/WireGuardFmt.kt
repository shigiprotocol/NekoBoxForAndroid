package io.nekohasekai.sagernet.fmt.wireguard

import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

fun genReserved(anyStr: String): String {
    try {
        val list = anyStr.listByLineOrComma()
        val ba = ByteArray(3)
        if (list.size == 3) {
            list.forEachIndexed { index, s ->
                val i = s
                    .replace("[", "")
                    .replace("]", "")
                    .replace(" ", "")
                    .toIntOrNull() ?: return anyStr
                ba[index] = i.toByte()
            }
            return Util.b64EncodeOneLine(ba)
        } else {
            return anyStr
        }
    } catch (e: Exception) {
        return anyStr
    }
}

fun buildSingBoxOutboundWireguardBean(bean: WireGuardBean): SingBoxOptions.Outbound_WireGuardOptions {
    return SingBoxOptions.Outbound_WireGuardOptions().apply {
        type = "wireguard"
        server = bean.serverAddress
        server_port = bean.serverPort
        local_address = bean.localAddress.listByLineOrComma()
        private_key = bean.privateKey
        peer_public_key = bean.peerPublicKey
        pre_shared_key = bean.peerPreSharedKey
        mtu = bean.mtu
        if (bean.reserved.isNotBlank()) reserved = genReserved(bean.reserved)
    }
}

// ---------------------------------------------------------------- STRIDER: wireguard:// links

/** Percent-decoding that keeps '+' (base64 keys contain it; URLDecoder would turn it into a space). */
private fun wgDecode(s: String): String = try {
    java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
} catch (_: Exception) {
    s
}

/** Splits "host:port" / "[ipv6]:port" (brackets removed from the host). */
fun splitWireGuardEndpoint(endpoint: String): Pair<String, Int>? {
    val e = endpoint.trim()
    val host: String
    val port: String
    if (e.startsWith("[")) {
        val end = e.indexOf(']')
        if (end < 0) return null
        host = e.substring(1, end)
        port = e.substring(end + 1).removePrefix(":")
    } else {
        if (!e.contains(":")) return null
        host = e.substringBeforeLast(":")
        port = e.substringAfterLast(":")
    }
    val p = port.trim().toIntOrNull() ?: return null
    if (host.isBlank() || p !in 1..65535) return null
    return host to p
}

/**
 * wireguard://<private key>@<host>:<port>?publickey=…&address=…&presharedkey=…&mtu=…&reserved=…#name
 * (3x-ui, v2rayN, Hiddify, ...; "wg://" too)
 */
fun parseWireGuardLink(link: String): WireGuardBean {
    val rest = link.trim().substringAfter("://")
    val name = wgDecode(rest.substringAfter('#', ""))
    val noFragment = rest.substringBefore('#')
    val query = noFragment.substringAfter('?', "")
    val authority = noFragment.substringBefore('?').trimEnd('/')
    val at = authority.lastIndexOf('@')
    if (at <= 0) error("WireGuard link without a private key")
    val (host, port) = splitWireGuardEndpoint(authority.substring(at + 1))
        ?: error("WireGuard link without a valid server address / port")

    val params = HashMap<String, String>()
    for (pair in query.split('&')) {
        if (pair.isBlank()) continue
        val k = wgDecode(pair.substringBefore('=')).lowercase()
        val v = wgDecode(pair.substringAfter('=', ""))
        if (v.isNotBlank()) params[k] = v.trim()
    }
    fun p(vararg keys: String) = keys.firstNotNullOfOrNull { params[it] }

    val bean = WireGuardBean().applyDefaultValues()
    bean.serverAddress = host
    bean.serverPort = port
    bean.privateKey = wgDecode(authority.substring(0, at)).trim()
    bean.peerPublicKey = p("publickey", "public_key", "peer_public_key", "peerpublickey", "pbk") ?: ""
    bean.peerPreSharedKey = p("presharedkey", "pre_shared_key", "psk", "preshared_key") ?: ""
    bean.localAddress = (p("address", "ip", "local_address", "localaddress") ?: "")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    p("mtu")?.toIntOrNull()?.takeIf { it in 576..65535 }?.let { bean.mtu = it }
    p("reserved")?.let { bean.reserved = it }
    if (name.isNotBlank()) bean.name = name

    if (bean.privateKey.isNullOrBlank()) error("WireGuard link without a private key")
    if (bean.peerPublicKey.isNullOrBlank()) error("WireGuard link without the server public key")
    if (bean.localAddress.isNullOrBlank()) error("WireGuard link without an address")
    return bean
}
