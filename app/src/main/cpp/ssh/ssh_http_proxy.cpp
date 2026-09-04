#include "ssh_http_proxy.h"

#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <string>

namespace sshnative {

namespace {

constexpr size_t kMaxResponseBytes = 16 * 1024;

std::string base64Encode(const std::string& input) {
    static constexpr char kTable[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string output;
    output.reserve(((input.size() + 2) / 3) * 4);
    size_t i = 0;
    while (i + 2 < input.size()) {
        const uint32_t value = (static_cast<uint8_t>(input[i]) << 16) |
            (static_cast<uint8_t>(input[i + 1]) << 8) |
            static_cast<uint8_t>(input[i + 2]);
        output.push_back(kTable[(value >> 18) & 0x3F]);
        output.push_back(kTable[(value >> 12) & 0x3F]);
        output.push_back(kTable[(value >> 6) & 0x3F]);
        output.push_back(kTable[value & 0x3F]);
        i += 3;
    }
    const size_t remaining = input.size() - i;
    if (remaining == 1) {
        const uint32_t value = static_cast<uint8_t>(input[i]) << 16;
        output.push_back(kTable[(value >> 18) & 0x3F]);
        output.push_back(kTable[(value >> 12) & 0x3F]);
        output.push_back('=');
        output.push_back('=');
    } else if (remaining == 2) {
        const uint32_t value = (static_cast<uint8_t>(input[i]) << 16) |
            (static_cast<uint8_t>(input[i + 1]) << 8);
        output.push_back(kTable[(value >> 18) & 0x3F]);
        output.push_back(kTable[(value >> 12) & 0x3F]);
        output.push_back(kTable[(value >> 6) & 0x3F]);
        output.push_back('=');
    }
    return output;
}

void writeAll(int fd, const std::string& data, std::chrono::steady_clock::time_point deadline) {
    size_t sent = 0;
    while (sent < data.size()) {
        const ssize_t result = send(fd, data.data() + sent, data.size() - sent, MSG_NOSIGNAL);
        if (result > 0) {
            sent += static_cast<size_t>(result);
            continue;
        }
        if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now());
            if (remaining.count() <= 0) {
                throw std::runtime_error("HTTP proxy write timed out");
            }
            struct pollfd pfd {};
            pfd.fd = fd;
            pfd.events = POLLOUT;
            const int poll_result = poll(&pfd, 1, static_cast<int>(remaining.count()));
            if (poll_result <= 0) {
                throw std::runtime_error(poll_result == 0 ? "HTTP proxy write timed out" : "HTTP proxy socket error");
            }
            continue;
        }
        throw std::runtime_error("HTTP proxy write failed");
    }
}

std::string readResponse(int fd, std::chrono::steady_clock::time_point deadline) {
    std::string response;
    char buffer[1024];
    while (response.find("\r\n\r\n") == std::string::npos) {
        if (response.size() >= kMaxResponseBytes) {
            throw std::runtime_error("HTTP proxy response header too large");
        }
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
            deadline - std::chrono::steady_clock::now());
        if (remaining.count() <= 0) {
            throw std::runtime_error("HTTP proxy response timed out");
        }
        struct pollfd pfd {};
        pfd.fd = fd;
        pfd.events = POLLIN;
        const int poll_result = poll(&pfd, 1, static_cast<int>(remaining.count()));
        if (poll_result <= 0) {
            throw std::runtime_error(poll_result == 0 ? "HTTP proxy response timed out" : "HTTP proxy socket error");
        }
        const ssize_t count = recv(fd, buffer, sizeof(buffer), 0);
        if (count == 0) {
            throw std::runtime_error("HTTP proxy closed connection during handshake");
        }
        if (count < 0) {
            throw std::runtime_error("HTTP proxy read failed");
        }
        response.append(buffer, static_cast<size_t>(count));
    }
    return response;
}

} // namespace

void httpConnectTunnel(
    int fd,
    const std::string& target_host,
    uint16_t target_port,
    const std::string& proxy_username,
    const std::string& proxy_password,
    std::chrono::milliseconds timeout) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    const std::string authority = target_host + ":" + std::to_string(target_port);

    std::string request = "CONNECT " + authority + " HTTP/1.1\r\n";
    request += "Host: " + authority + "\r\n";
    request += "Proxy-Connection: keep-alive\r\n";
    if (!proxy_username.empty() || !proxy_password.empty()) {
        const std::string credentials = proxy_username + ":" + proxy_password;
        request += "Proxy-Authorization: Basic " + base64Encode(credentials) + "\r\n";
    }
    request += "\r\n";

    writeAll(fd, request, deadline);
    const std::string response = readResponse(fd, deadline);

    const size_t space1 = response.find(' ');
    const size_t space2 = space1 == std::string::npos ? std::string::npos : response.find(' ', space1 + 1);
    if (space1 == std::string::npos || space2 == std::string::npos) {
        throw std::runtime_error("HTTP proxy sent malformed status line");
    }
    const std::string code_string = response.substr(space1 + 1, space2 - space1 - 1);
    char* end = nullptr;
    const long code = std::strtol(code_string.c_str(), &end, 10);
    if (end == code_string.c_str() || *end != '\0') {
        throw std::runtime_error("HTTP proxy sent non-numeric status code");
    }
    if (code != 200) {
        // Only the first status line is exposed; it does not contain credentials.
        const std::string line = response.substr(0, response.find("\r\n"));
        throw std::runtime_error("HTTP CONNECT failed: " + line);
    }
}

} // namespace sshnative
