#include "ssh/ssh_direct_operation.h"
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
        std::make_unique<sshnative::TcpPasswordExecOperation>(
            "127.0.0.1",
            static_cast<uint16_t>(port),
            "test",
            "secret",
            "big-output:10000",
            5s,
            4096u));
    assert(result && "runtime must accept direct output-limit operation");

    const auto stop = sshnative::MonoClock::now() + 5s;
    while (sshnative::MonoClock::now() < stop) {
        sshnative::RuntimeEvent event;
        if (!runtime->waitEvent(&event, 200ms)) continue;
        if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
            event.request_id != result.request_id) {
            continue;
        }
        assert(event.completion == sshnative::CompletionKind::kSucceeded);
        assert(event.payload.find("exit=125\n") == 0);
        std::cout << "runtime-direct-output-limit-ok\n";
        runtime->shutdown();
        return 0;
    }
    std::cerr << "runtime direct output limit timeout\n";
    runtime->shutdown();
    return 1;
}
