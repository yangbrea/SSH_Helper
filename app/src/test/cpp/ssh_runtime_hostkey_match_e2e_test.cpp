#include "ssh/ssh_direct_operation.h"
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

namespace {

bool waitForEvent(sshnative::SshNativeSession& runtime,
                  sshnative::SubmitResult result,
                  sshnative::RuntimeEvent* out) {
    const auto stop = sshnative::MonoClock::now() + 5s;
    while (sshnative::MonoClock::now() < stop) {
        sshnative::RuntimeEvent event;
        if (!runtime.waitEvent(&event, 200ms)) continue;
        if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
            event.request_id != result.request_id) {
            continue;
        }
        *out = std::move(event);
        return true;
    }
    return false;
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 2) return 64;
    const int port = std::atoi(argv[1]);

    // Obtain the actual host key fingerprint via a runtime handshake operation.
    const int fd = sshnative::connectTcp(
        "127.0.0.1", static_cast<uint16_t>(port), std::chrono::seconds(5));
    auto handshake_runtime = sshnative::createSession();
    auto handshake_result = handshake_runtime->submit(
        std::make_unique<sshnative::Libssh2HandshakeOperation>(fd));
    assert(handshake_result);
    sshnative::RuntimeEvent handshake_event;
    assert(waitForEvent(*handshake_runtime, handshake_result, &handshake_event));
    assert(handshake_event.completion == sshnative::CompletionKind::kSucceeded);
    const std::string prefix = "fingerprint=";
    const auto pos = handshake_event.payload.find(prefix);
    assert(pos != std::string::npos);
    const auto newline = handshake_event.payload.find('\n', pos + prefix.size());
    const std::string fingerprint =
        handshake_event.payload.substr(pos + prefix.size(),
                                       newline == std::string::npos
                                           ? std::string::npos
                                           : newline - pos - prefix.size());
    handshake_runtime->shutdown();

    // Use that fingerprint as the expected value; exec should succeed.
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
            fingerprint));
    assert(result);
    sshnative::RuntimeEvent event;
    assert(waitForEvent(*runtime, result, &event));
    assert(event.completion == sshnative::CompletionKind::kSucceeded);
    assert(event.payload.find("exit=0\n") == 0);
    std::cout << "runtime-hostkey-match-ok\n";
    runtime->shutdown();
    return 0;
}
