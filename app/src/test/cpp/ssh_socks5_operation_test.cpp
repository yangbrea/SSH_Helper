#include "ssh/ssh_socks5_operation.h"
#include "ssh/ssh_runtime.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cassert>
#include <chrono>
#include <cstdint>
#include <memory>
#include <thread>

using namespace std::chrono_literals;

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

void serveSocks5Auth(int listener) {
    const int client = accept(listener, nullptr, nullptr);
    assert(client >= 0);
    uint8_t greeting[2];
    readExact(client, greeting, 2);
    assert(greeting[0] == 0x05);
    uint8_t methods[8];
    readExact(client, methods, greeting[1]);
    uint8_t reply[2] = {0x05, 0x02};
    writeExact(client, reply, 2);

    uint8_t auth_version_len[2];
    readExact(client, auth_version_len, 2);
    assert(auth_version_len[0] == 0x01);
    std::string user(auth_version_len[1], '\0');
    readExact(client, &user[0], user.size());
    uint8_t pass_len = 0;
    readExact(client, &pass_len, 1);
    std::string pass(pass_len, '\0');
    readExact(client, &pass[0], pass.size());
    assert(user == "proxyuser");
    assert(pass == "proxypass");
    uint8_t auth_ok[2] = {0x01, 0x00};
    writeExact(client, auth_ok, 2);

    uint8_t header[4];
    readExact(client, header, 4);
    assert(header[0] == 0x05 && header[1] == 0x01);
    assert(header[3] == 0x03);
    uint8_t len = 0;
    readExact(client, &len, 1);
    std::string domain(len, '\0');
    readExact(client, &domain[0], len);
    uint8_t port[2];
    readExact(client, port, 2);
    const uint8_t success[] = {0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
    writeExact(client, success, sizeof(success));
    close(client);
}

void serveSocks5(int listener) {
    const int client = accept(listener, nullptr, nullptr);
    assert(client >= 0);
    uint8_t greeting[2];
    readExact(client, greeting, 2);
    assert(greeting[0] == 0x05);
    uint8_t methods[8];
    readExact(client, methods, greeting[1]);
    uint8_t reply[2] = {0x05, 0x00};
    writeExact(client, reply, 2);

    uint8_t header[4];
    readExact(client, header, 4);
    assert(header[0] == 0x05 && header[1] == 0x01);
    assert(header[3] == 0x03);
    uint8_t len = 0;
    readExact(client, &len, 1);
    std::string domain(len, '\0');
    readExact(client, &domain[0], len);
    uint8_t port[2];
    readExact(client, port, 2);
    const uint8_t success[] = {0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
    writeExact(client, success, sizeof(success));
    close(client);
}

} // namespace

int main() {
    uint16_t port = 0;
    const int listener = startListener(port);
    std::thread proxy([listener] { serveSocks5(listener); });

    auto runtime = sshnative::createSession();
    auto result = runtime->submit(
        std::make_unique<sshnative::Socks5ProxyConnectOperation>(
            "127.0.0.1", port, "example.test", 22, "", "", 2s));
    assert(result && "runtime must accept socks5 operation");

    auto wait_connected = [](auto& runtime, auto result) {
        const auto stop = sshnative::MonoClock::now() + 3s;
        while (sshnative::MonoClock::now() < stop) {
            sshnative::RuntimeEvent event;
            if (!runtime->waitEvent(&event, 100ms)) continue;
            if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
                event.request_id != result.request_id) {
                continue;
            }
            assert(event.completion == sshnative::CompletionKind::kSucceeded);
            assert(event.payload == "connected");
            return;
        }
        assert(false && "socks5 operation timeout");
    };

    wait_connected(runtime, result);
    runtime->shutdown();
    proxy.join();
    close(listener);

    uint16_t auth_port = 0;
    const int auth_listener = startListener(auth_port);
    std::thread auth_proxy([auth_listener] { serveSocks5Auth(auth_listener); });
    auto auth_runtime = sshnative::createSession();
    auto auth_result = auth_runtime->submit(
        std::make_unique<sshnative::Socks5ProxyConnectOperation>(
            "127.0.0.1", auth_port, "example.test", 22,
            "proxyuser", "proxypass", 2s));
    assert(auth_result && "runtime must accept socks5 auth operation");
    wait_connected(auth_runtime, auth_result);
    auth_runtime->shutdown();
    auth_proxy.join();
    close(auth_listener);
    return 0;
}
