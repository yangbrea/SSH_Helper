#include "ssh/ssh_operations.h"
#include "ssh/ssh_runtime.h"
#include "ssh/ssh_socket.h"

#include <chrono>
#include <cassert>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <string>

using namespace std::chrono_literals;

int main(int argc, char** argv) {
    if (argc != 2) return 64;
    const int port = std::atoi(argv[1]);

    const int fd = sshnative::connectTcp(
        "127.0.0.1", static_cast<uint16_t>(port), std::chrono::seconds(5));

    auto runtime = sshnative::createSession();
    auto result = runtime->submit(
        std::make_unique<sshnative::Libssh2HandshakeOperation>(fd));
    assert(result && "runtime must accept handshake operation");

    const auto stop = sshnative::MonoClock::now() + 5s;
    while (sshnative::MonoClock::now() < stop) {
        sshnative::RuntimeEvent event;
        if (!runtime->waitEvent(&event, 200ms)) continue;
        if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
            event.request_id != result.request_id) {
            continue;
        }
        assert(event.completion == sshnative::CompletionKind::kSucceeded);
        assert(event.payload.find("fingerprint=SHA256:") != std::string::npos);
        assert(event.payload.find("keyType=ssh-ed25519") != std::string::npos);
        std::cout << "runtime-handshake-ok\n";
        runtime->shutdown();
        return 0;
    }
    std::cerr << "runtime handshake timeout\n";
    runtime->shutdown();
    return 1;
}
