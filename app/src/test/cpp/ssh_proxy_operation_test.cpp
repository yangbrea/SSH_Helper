#include "ssh/ssh_proxy_operation.h"
#include "ssh/ssh_runtime.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cassert>
#include <chrono>
#include <cstdint>
#include <memory>
#include <string>
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

void readRequestAndReply(int listener) {
    const int client = accept(listener, nullptr, nullptr);
    assert(client >= 0);
    std::string data;
    char buffer[512];
    while (data.find("\r\n\r\n") == std::string::npos) {
        const ssize_t count = recv(client, buffer, sizeof(buffer), 0);
        assert(count > 0);
        data.append(buffer, static_cast<size_t>(count));
    }
    assert(data.find("CONNECT example.test:22 HTTP/1.1") != std::string::npos);
    const std::string response = "HTTP/1.1 200 Connection established\r\n\r\n";
    size_t sent = 0;
    while (sent < response.size()) {
        const ssize_t count = send(client, response.data() + sent,
                                   response.size() - sent, MSG_NOSIGNAL);
        assert(count > 0);
        sent += static_cast<size_t>(count);
    }
    close(client);
}

} // namespace

int main() {
    uint16_t port = 0;
    const int listener = startListener(port);
    std::thread proxy([listener] { readRequestAndReply(listener); });

    auto runtime = sshnative::createSession();
    auto result = runtime->submit(
        std::make_unique<sshnative::HttpProxyConnectOperation>(
            "127.0.0.1", port, "example.test", 22, "", "", 2s));
    assert(result && "runtime must accept http proxy operation");

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
        runtime->shutdown();
        proxy.join();
        close(listener);
        return 0;
    }
    runtime->shutdown();
    close(listener);
    return 1;
}
