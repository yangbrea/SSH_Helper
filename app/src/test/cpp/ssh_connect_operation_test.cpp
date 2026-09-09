#include "ssh/ssh_connect_operation.h"
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

int main() {
    const int listener = socket(AF_INET, SOCK_STREAM, 0);
    assert(listener >= 0);
    sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    assert(bind(listener, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0);
    assert(listen(listener, 1) == 0);
    socklen_t len = sizeof(addr);
    assert(getsockname(listener, reinterpret_cast<sockaddr*>(&addr), &len) == 0);
    const uint16_t port = ntohs(addr.sin_port);

    std::thread acceptor([listener] {
        const int client = accept(listener, nullptr, nullptr);
        assert(client >= 0);
        close(client);
    });

    auto runtime = sshnative::createSession();
    auto result = runtime->submit(
        std::make_unique<sshnative::TcpConnectOperation>(
            "127.0.0.1", port, 2s));
    assert(result && "runtime must accept tcp connect operation");

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
        acceptor.join();
        close(listener);
        return 0;
    }
    runtime->shutdown();
    close(listener);
    return 1;
}
