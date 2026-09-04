#include "ssh/ssh_socks5_proxy.h"
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

void replyToGreeting(int client, uint8_t method) {
    uint8_t greeting[2];
    readExact(client, greeting, 2);
    assert(greeting[0] == 0x05);
    uint8_t methods[8];
    readExact(client, methods, greeting[1]);
    uint8_t reply[2] = {0x05, method};
    writeExact(client, reply, 2);
}

void readConnectRequest(int client) {
    uint8_t header[4];
    readExact(client, header, 4);
    assert(header[0] == 0x05 && header[1] == 0x01 && header[2] == 0x00);
    if (header[3] == 0x01) {
        uint8_t address[4];
        readExact(client, address, 4);
    } else if (header[3] == 0x04) {
        uint8_t address[16];
        readExact(client, address, 16);
    } else if (header[3] == 0x03) {
        uint8_t len = 0;
        readExact(client, &len, 1);
        std::string domain(len, '\0');
        readExact(client, &domain[0], len);
    } else {
        assert(false);
    }
    uint8_t port[2];
    readExact(client, port, 2);
}

void sendSuccess(int client) {
    const uint8_t success[] = {0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
    writeExact(client, success, sizeof(success));
}

} // namespace

int main() {
    using namespace sshnative;

    // SOCKS5 no-auth CONNECT to a hostname target.
    {
        uint16_t port = 0;
        const int listener = startListener(port);
        std::thread proxy([listener] {
            const int client = accept(listener, nullptr, nullptr);
            assert(client >= 0);
            replyToGreeting(client, 0x00);
            readConnectRequest(client);
            sendSuccess(client);
            close(client);
        });
        const int fd = connectTcp("127.0.0.1", port, std::chrono::seconds(2));
        try {
            socks5ConnectTunnel(fd, "example.test", 443, "", "", std::chrono::seconds(2));
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

    // SOCKS5 username/password auth CONNECT.
    {
        uint16_t port = 0;
        const int listener = startListener(port);
        std::thread proxy([listener] {
            const int client = accept(listener, nullptr, nullptr);
            assert(client >= 0);
            replyToGreeting(client, 0x02);

            uint8_t auth[2];
            readExact(client, auth, 2);
            assert(auth[0] == 0x01);
            std::string user(auth[1], '\0');
            readExact(client, &user[0], auth[1]);
            uint8_t pass_len = 0;
            readExact(client, &pass_len, 1);
            std::string pass(pass_len, '\0');
            readExact(client, &pass[0], pass_len);
            assert(user == "user");
            assert(pass == "secret");
            uint8_t auth_reply[2] = {0x01, 0x00};
            writeExact(client, auth_reply, 2);

            readConnectRequest(client);
            sendSuccess(client);
            close(client);
        });
        const int fd = connectTcp("127.0.0.1", port, std::chrono::seconds(2));
        try {
            socks5ConnectTunnel(fd, "127.0.0.1", 22, "user", "secret", std::chrono::seconds(2));
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

    // CONNECT failure is a stable error.
    {
        uint16_t port = 0;
        const int listener = startListener(port);
        std::thread proxy([listener] {
            const int client = accept(listener, nullptr, nullptr);
            assert(client >= 0);
            replyToGreeting(client, 0x00);
            readConnectRequest(client);
            const uint8_t failure[] = {0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
            writeExact(client, failure, sizeof(failure));
            close(client);
        });
        const int fd = connectTcp("127.0.0.1", port, std::chrono::seconds(2));
        bool threw = false;
        try {
            socks5ConnectTunnel(fd, "example.test", 443, "", "", std::chrono::seconds(2));
        } catch (const std::runtime_error&) {
            threw = true;
        }
        assert(threw);
        closeFd(fd);
        proxy.join();
        close(listener);
    }
}
