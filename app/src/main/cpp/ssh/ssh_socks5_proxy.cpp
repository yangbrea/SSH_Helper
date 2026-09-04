#include "ssh_socks5_proxy.h"

#include <arpa/inet.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>

namespace sshnative {

namespace {

constexpr uint8_t kSocks5Version = 0x05;
constexpr uint8_t kMethodNoAuth = 0x00;
constexpr uint8_t kMethodUserPass = 0x02;
constexpr uint8_t kCmdConnect = 0x01;
constexpr uint8_t kAtypIpv4 = 0x01;
constexpr uint8_t kAtypDomain = 0x03;
constexpr uint8_t kAtypIpv6 = 0x04;

void writeAll(int fd, const uint8_t* data, size_t size, std::chrono::steady_clock::time_point deadline) {
    size_t sent = 0;
    while (sent < size) {
        const ssize_t result = send(fd, data + sent, size - sent, MSG_NOSIGNAL);
        if (result > 0) {
            sent += static_cast<size_t>(result);
            continue;
        }
        if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now());
            if (remaining.count() <= 0) {
                throw std::runtime_error("SOCKS5 proxy write timed out");
            }
            struct pollfd pfd {};
            pfd.fd = fd;
            pfd.events = POLLOUT;
            const int poll_result = poll(&pfd, 1, static_cast<int>(remaining.count()));
            if (poll_result <= 0) {
                throw std::runtime_error(poll_result == 0 ? "SOCKS5 proxy write timed out" : "SOCKS5 proxy socket error");
            }
            continue;
        }
        throw std::runtime_error("SOCKS5 proxy write failed");
    }
}

void readExact(int fd, uint8_t* data, size_t size, std::chrono::steady_clock::time_point deadline) {
    size_t received = 0;
    while (received < size) {
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
            deadline - std::chrono::steady_clock::now());
        if (remaining.count() <= 0) {
            throw std::runtime_error("SOCKS5 proxy response timed out");
        }
        struct pollfd pfd {};
        pfd.fd = fd;
        pfd.events = POLLIN;
        const int poll_result = poll(&pfd, 1, static_cast<int>(remaining.count()));
        if (poll_result <= 0) {
            throw std::runtime_error(poll_result == 0 ? "SOCKS5 proxy response timed out" : "SOCKS5 proxy socket error");
        }
        const ssize_t count = recv(fd, data + received, size - received, 0);
        if (count == 0) {
            throw std::runtime_error("SOCKS5 proxy closed connection during handshake");
        }
        if (count < 0) {
            throw std::runtime_error("SOCKS5 proxy read failed");
        }
        received += static_cast<size_t>(count);
    }
}

std::string makeConnectRequest(const std::string& host, uint16_t port) {
    std::string request;
    request.push_back(static_cast<char>(kSocks5Version));
    request.push_back(static_cast<char>(kCmdConnect));
    request.push_back(0);

    struct in_addr ipv4 {};
    struct in6_addr ipv6 {};
    if (inet_pton(AF_INET, host.c_str(), &ipv4) == 1) {
        request.push_back(static_cast<char>(kAtypIpv4));
        request.append(reinterpret_cast<const char*>(&ipv4), sizeof(ipv4));
    } else if (inet_pton(AF_INET6, host.c_str(), &ipv6) == 1) {
        request.push_back(static_cast<char>(kAtypIpv6));
        request.append(reinterpret_cast<const char*>(&ipv6), sizeof(ipv6));
    } else {
        if (host.empty() || host.size() > 255) {
            throw std::runtime_error("SOCKS5 target host too long or empty");
        }
        request.push_back(static_cast<char>(kAtypDomain));
        request.push_back(static_cast<char>(host.size()));
        request.append(host);
    }
    request.push_back(static_cast<char>((port >> 8) & 0xFF));
    request.push_back(static_cast<char>(port & 0xFF));
    return request;
}

void consumeAddress(int fd, uint8_t atyp, std::chrono::steady_clock::time_point deadline) {
    switch (atyp) {
        case kAtypIpv4:
            {
                uint8_t address[4];
                readExact(fd, address, sizeof(address), deadline);
            }
            break;
        case kAtypIpv6:
            {
                uint8_t address[16];
                readExact(fd, address, sizeof(address), deadline);
            }
            break;
        case kAtypDomain:
            {
                uint8_t length = 0;
                readExact(fd, &length, 1, deadline);
                std::string domain(static_cast<size_t>(length), '\0');
                readExact(fd, reinterpret_cast<uint8_t*>(&domain[0]), length, deadline);
            }
            break;
        default:
            throw std::runtime_error("SOCKS5 proxy sent unknown address type");
    }
    uint8_t port[2];
    readExact(fd, port, sizeof(port), deadline);
}

} // namespace

void socks5ConnectTunnel(
    int fd,
    const std::string& target_host,
    uint16_t target_port,
    const std::string& username,
    const std::string& password,
    std::chrono::milliseconds timeout) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;

    const bool use_auth = !username.empty() || !password.empty();
    uint8_t greeting[4] = {
        kSocks5Version,
        static_cast<uint8_t>(use_auth ? 2 : 1),
        kMethodNoAuth,
        kMethodUserPass,
    };
    writeAll(fd, greeting, use_auth ? 4 : 3, deadline);

    uint8_t method_reply[2];
    readExact(fd, method_reply, sizeof(method_reply), deadline);
    if (method_reply[0] != kSocks5Version) {
        throw std::runtime_error("SOCKS5 proxy sent invalid version");
    }

    if (method_reply[1] == kMethodNoAuth && use_auth) {
        // Server selected no-auth despite us offering user/pass; the caller only
        // supplied credentials for an authenticated proxy, so fail closed.
        throw std::runtime_error("SOCKS5 proxy requested unauthenticated connection");
    }
    if (method_reply[1] == kMethodUserPass) {
        if (username.size() > 255 || password.size() > 255) {
            throw std::runtime_error("SOCKS5 credentials too long");
        }
        std::string auth;
        auth.push_back(0x01);
        auth.push_back(static_cast<char>(username.size()));
        auth.append(username);
        auth.push_back(static_cast<char>(password.size()));
        auth.append(password);
        writeAll(fd, reinterpret_cast<const uint8_t*>(auth.data()), auth.size(), deadline);

        uint8_t auth_reply[2];
        readExact(fd, auth_reply, sizeof(auth_reply), deadline);
        if (auth_reply[0] != 0x01 || auth_reply[1] != 0x00) {
            throw std::runtime_error("SOCKS5 proxy authentication failed");
        }
    } else if (method_reply[1] != kMethodNoAuth) {
        throw std::runtime_error("SOCKS5 proxy rejected all offered methods");
    }

    const std::string request = makeConnectRequest(target_host, target_port);
    writeAll(fd, reinterpret_cast<const uint8_t*>(request.data()), request.size(), deadline);

    uint8_t connect_reply[4];
    readExact(fd, connect_reply, sizeof(connect_reply), deadline);
    if (connect_reply[0] != kSocks5Version) {
        throw std::runtime_error("SOCKS5 proxy sent invalid CONNECT version");
    }
    if (connect_reply[1] != 0x00) {
        throw std::runtime_error("SOCKS5 CONNECT failed");
    }
    consumeAddress(fd, connect_reply[3], deadline);
}

} // namespace sshnative
