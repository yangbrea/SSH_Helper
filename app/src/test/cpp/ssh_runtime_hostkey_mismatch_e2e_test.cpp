#include "ssh/ssh_direct_operation.h"
#include "ssh/ssh_runtime.h"

#include <chrono>
#include <cassert>
#include <cstdlib>
#include <iostream>
#include <memory>

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
            "true",
            5s,
            1024u,
            "SHA256:definitely-not-the-host-key"));
    assert(result && "runtime must accept host key mismatch operation");

    const auto stop = sshnative::MonoClock::now() + 5s;
    while (sshnative::MonoClock::now() < stop) {
        sshnative::RuntimeEvent event;
        if (!runtime->waitEvent(&event, 200ms)) continue;
        if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
            event.request_id != result.request_id) {
            continue;
        }
        assert(event.completion == sshnative::CompletionKind::kFailed);
        assert(event.error.domain == sshnative::ErrorDomain::kHostKey);
        assert(event.error.code == "host_key_mismatch");
        std::cout << "runtime-hostkey-mismatch-ok\n";
        runtime->shutdown();
        return 0;
    }
    std::cerr << "runtime host key mismatch timeout\n";
    runtime->shutdown();
    return 1;
}
