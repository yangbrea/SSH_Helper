#include "ssh/ssh_transport.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cassert>
#include <chrono>
#include <cstdint>
#include <string>
#include <thread>

namespace {

int startListener(uint16_t& port) {
    const int fd = socket(AF_INET, SOCK_STREAM, 0);
    assert(fd >= 0);
    sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    assert(bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
    assert(listen(fd, 1) == 0);
    socklen_t len = sizeof(addr);
    assert(getsockname(fd, reinterpret_cast<sockaddr*>(&addr), &len) == 0);
    port = ntohs(addr.sin_port);
    return fd;
}

void readExact(int fd, void* data, size_t size) {
    size_t got = 0;
    auto* bytes = static_cast<uint8_t*>(data);
    while (got < size) {
        const ssize_t count = recv(fd, bytes + got, size - got, 0);
        assert(count > 0);
        got += static_cast<size_t>(count);
    }
}

void writeExact(int fd, const void* data, size_t size) {
    const auto* bytes = static_cast<const uint8_t*>(data);
    size_t sent = 0;
    while (sent < size) {
        const ssize_t count = send(fd, bytes + sent, size - sent, MSG_NOSIGNAL);
        assert(count > 0);
        sent += static_cast<size_t>(count);
    }
}

std::string readHttpHeader(int client) {
    std::string data;
    char buffer[512];
    while (data.find("\r\n\r\n") == std::string::npos) {
        const ssize_t count = recv(client, buffer, sizeof(buffer), 0);
        assert(count > 0);
        data.append(buffer, static_cast<size_t>(count));
    }
    return data;
}

void serveHttpProxy(int listener) {
    const int client = accept(listener, nullptr, nullptr);
    assert(client >= 0);
    (void)readHttpHeader(client);
    const std::string response = "HTTP/1.1 200 Connection established\r\n\r\n";
    writeExact(client, response.data(), response.size());
    close(client);
}

void serveSocks5Proxy(int listener) {
    const int client = accept(listener, nullptr, nullptr);
    assert(client >= 0);
    uint8_t greeting[2];
    readExact(client, greeting, 2);
    uint8_t methods[8];
    readExact(client, methods, greeting[1]);
    uint8_t reply[2] = {0x05, 0x00};
    writeExact(client, reply, 2);
    uint8_t header[4];
    readExact(client, header, 4);
    assert(header[0] == 0x05 && header[1] == 0x01);
    if (header[3] == 0x03) {
        uint8_t len = 0;
        readExact(client, &len, 1);
        std::string domain(len, '\0');
        readExact(client, &domain[0], len);
    } else if (header[3] == 0x01) {
        uint8_t addr[4];
        readExact(client, addr, 4);
    } else {
        uint8_t addr[16];
        readExact(client, addr, 16);
    }
    uint8_t port[2];
    readExact(client, port, 2);
    const uint8_t success[] = {0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
    writeExact(client, success, sizeof(success));
    close(client);
}

} // namespace

int main() {
    using namespace sshnative;

    // Direct transport.
    {
        uint16_t port = 0;
        const int listener = startListener(port);
        std::thread server([listener] {
            const int client = accept(listener, nullptr, nullptr);
            assert(client >= 0);
            close(client);
        });
        const int fd = connectTransport("127.0.0.1", port, ProxySettings{}, std::chrono::seconds(2));
        assert(fd >= 0);
        close(fd);
        server.join();
        close(listener);
    }

    // HTTP CONNECT proxy selection.
    {
        uint16_t proxy_port = 0;
        const int listener = startListener(proxy_port);
        std::thread proxy([listener] { serveHttpProxy(listener); });
        ProxySettings settings;
        settings.type = ProxyType::kHttpConnect;
        settings.host = "127.0.0.1";
        settings.port = proxy_port;
        const int fd = connectTransport("example.test", 443, settings, std::chrono::seconds(2));
        assert(fd >= 0);
        close(fd);
        proxy.join();
        close(listener);
    }

    // SOCKS5 proxy selection.
    {
        uint16_t proxy_port = 0;
        const int listener = startListener(proxy_port);
        std::thread proxy([listener] { serveSocks5Proxy(listener); });
        ProxySettings settings;
        settings.type = ProxyType::kSocks5;
        settings.host = "127.0.0.1";
        settings.port = proxy_port;
        const int fd = connectTransport("example.test", 22, settings, std::chrono::seconds(2));
        assert(fd >= 0);
        close(fd);
        proxy.join();
        close(listener);
    }
}
