#include "ssh/ssh_handshake_operation.h"
#include "ssh/ssh_runtime.h"

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

    auto runtime = sshnative::createSession();
    auto result = runtime->submit(
        std::make_unique<sshnative::TcpHandshakeOperation>(
            "127.0.0.1", static_cast<uint16_t>(port), 5s));
    assert(result);

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
        std::cout << "runtime-tcp-handshake-ok\n";
        runtime->shutdown();
        return 0;
    }
    std::cerr << "runtime tcp handshake timeout\n";
    runtime->shutdown();
    return 1;
}
