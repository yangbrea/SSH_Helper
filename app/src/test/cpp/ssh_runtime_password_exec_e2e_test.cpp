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
        std::make_unique<sshnative::Libssh2PasswordExecOperation>(
            fd, "test", "secret", "true"));
    assert(result && "runtime must accept password exec operation");

    const auto stop = sshnative::MonoClock::now() + 5s;
    while (sshnative::MonoClock::now() < stop) {
        sshnative::RuntimeEvent event;
        if (!runtime->waitEvent(&event, 200ms)) continue;
        if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
            event.request_id != result.request_id) {
            continue;
        }
        assert(event.completion == sshnative::CompletionKind::kSucceeded);
        assert(event.payload.find("exit=0\n") == 0);
        assert(event.payload.find("native-exec-ok") != std::string::npos);
        std::cout << "runtime-password-exec-ok\n";
        runtime->shutdown();
        return 0;
    }
    std::cerr << "runtime password exec timeout\n";
    runtime->shutdown();
    return 1;
}
