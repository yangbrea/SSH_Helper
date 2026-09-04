#include "ssh/ssh_http_proxy.h"
#include "ssh/ssh_socket.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cassert>
#include <chrono>
#include <cstdint>
#include <stdexcept>
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

std::string readUntilHeaderEnd(int client) {
    std::string data;
    char buffer[512];
    while (data.find("\r\n\r\n") == std::string::npos) {
        const ssize_t count = recv(client, buffer, sizeof(buffer), 0);
        assert(count > 0);
        data.append(buffer, static_cast<size_t>(count));
    }
    return data;
}


} // namespace

int main() {
    using namespace sshnative;

    // Successful HTTP CONNECT without proxy auth.
    {
        uint16_t port = 0;
        const int listener = startListener(port);
        std::thread proxy([listener] {
            const int client = accept(listener, nullptr, nullptr);
            assert(client >= 0);
            const std::string request = readUntilHeaderEnd(client);
            assert(request.find("CONNECT example.test:443 HTTP/1.1") != std::string::npos);
            const std::string response = "HTTP/1.1 200 Connection established\r\n\r\n";
            assert(send(client, response.data(), response.size(), MSG_NOSIGNAL) == static_cast<ssize_t>(response.size()));
            close(client);
        });

        const int fd = connectTcp("127.0.0.1", port, std::chrono::seconds(2));
        try {
            httpConnectTunnel(fd, "example.test", 443, "", "", std::chrono::seconds(2));
        } catch (...) {
            closeFd(fd);
            proxy.join();
            close(listener);
            throw;
        }
        closeFd(fd);
        proxy.join();
        close(listener);
    }

    // Successful HTTP CONNECT with Basic auth header.
    {
        uint16_t port = 0;
        const int listener = startListener(port);
        std::thread proxy([listener] {
            const int client = accept(listener, nullptr, nullptr);
            assert(client >= 0);
            const std::string request = readUntilHeaderEnd(client);
            assert(request.find("Proxy-Authorization: Basic dXNlcjpzZWNyZXQ=") != std::string::npos);
            const std::string response = "HTTP/1.1 200 Connection established\r\n\r\n";
            assert(send(client, response.data(), response.size(), MSG_NOSIGNAL) == static_cast<ssize_t>(response.size()));
            close(client);
        });

        const int fd = connectTcp("127.0.0.1", port, std::chrono::seconds(2));
        try {
            httpConnectTunnel(fd, "example.test", 443, "user", "secret", std::chrono::seconds(2));
        } catch (...) {
            closeFd(fd);
            proxy.join();
            close(listener);
            throw;
        }
        closeFd(fd);
        proxy.join();
        close(listener);
    }

    // Non-200 response is a stable error.
    {
        uint16_t port = 0;
        const int listener = startListener(port);
        std::thread proxy([listener] {
            const int client = accept(listener, nullptr, nullptr);
            assert(client >= 0);
            (void)readUntilHeaderEnd(client);
            const std::string response = "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n";
            assert(send(client, response.data(), response.size(), MSG_NOSIGNAL) == static_cast<ssize_t>(response.size()));
            close(client);
        });

        const int fd = connectTcp("127.0.0.1", port, std::chrono::seconds(2));
        bool threw = false;
        try {
            httpConnectTunnel(fd, "example.test", 443, "", "", std::chrono::seconds(2));
        } catch (const std::runtime_error&) {
            threw = true;
        }
        assert(threw);
        closeFd(fd);
        proxy.join();
        close(listener);
    }
}
