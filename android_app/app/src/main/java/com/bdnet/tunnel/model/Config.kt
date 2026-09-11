package com.bdnet.tunnel.model

import com.google.gson.annotations.SerializedName

data class TunnelConfig(
    @SerializedName("server_url") var serverUrl: String = "https://battery-twirl-designate.ngrok-free.dev",
    @SerializedName("ws_url") var wsUrl: String = "wss://battery-twirl-designate.ngrok-free.dev/ws",
    @SerializedName("vless_url") var vlessUrl: String = "wss://battery-twirl-designate.ngrok-free.dev/vless",
    @SerializedName("ssh_ws_url") var sshWsUrl: String = "wss://battery-twirl-designate.ngrok-free.dev/ssh-relay",
    @SerializedName("dns_server") var dnsServer: String = "1.1.1.1",
    @SerializedName("dns_domain") var dnsDomain: String = "t.yourdomain.com",
    @SerializedName("sni_host") var sniHost: String = "m.facebook.com",
    @SerializedName("payload") var payload: String = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]",
    @SerializedName("uuid") var uuid: String = "9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d",
    @SerializedName("local_port") var localPort: Int = 1080,
    @SerializedName("auth_token") var authToken: String = "CHANGE_THIS_NOW",
    @SerializedName("selected_method") var selectedMethod: String = "Method 2: WebSocket Relay"
)
