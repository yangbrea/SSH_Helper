#include "ssh_transport.h"

#include <stdexcept>

#include "ssh_http_proxy.h"
#include "ssh_socks5_proxy.h"
#include "ssh_socket.h"

namespace sshnative {

int connectTransport(
    const std::string& host,
    uint16_t port,
    const ProxySettings& proxy,
    std::chrono::milliseconds timeout) {
    if (proxy.type == ProxyType::kNone) {
        return connectTcp(host, port, timeout);
    }
    if (proxy.host.empty() || proxy.port == 0) {
        throw std::runtime_error("proxy host/port missing");
    }

    const int fd = connectTcp(proxy.host, proxy.port, timeout);
    try {
        if (proxy.type == ProxyType::kHttpConnect) {
            httpConnectTunnel(fd, host, port, proxy.username, proxy.password, timeout);
        } else if (proxy.type == ProxyType::kSocks5) {
            socks5ConnectTunnel(fd, host, port, proxy.username, proxy.password, timeout);
        } else {
            throw std::runtime_error("unknown proxy type");
        }
        return fd;
    } catch (...) {
        closeFd(fd);
        throw;
    }
}

} // namespace sshnative
