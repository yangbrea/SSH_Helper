#include "ssh/ssh_operations.h"
#include "ssh/ssh_runtime.h"
#include "ssh/ssh_socket.h"

#include <chrono>
#include <cassert>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>

using namespace std::chrono_literals;

int main(int argc, char** argv) {
    if (argc != 3) return 64;
    const int port = std::atoi(argv[1]);
    std::ifstream key_file(argv[2], std::ios::binary);
    if (!key_file) return 65;
    std::ostringstream key_buffer;
    key_buffer << key_file.rdbuf();

    const int fd = sshnative::connectTcp(
        "127.0.0.1", static_cast<uint16_t>(port), std::chrono::seconds(5));

    auto runtime = sshnative::createSession();
    auto result = runtime->submit(
        std::make_unique<sshnative::Libssh2PrivateKeyAuthOperation>(
            fd, "test", key_buffer.str(), "secret"));
    assert(result && "runtime must accept private key auth operation");

    const auto stop = sshnative::MonoClock::now() + 5s;
    while (sshnative::MonoClock::now() < stop) {
        sshnative::RuntimeEvent event;
        if (!runtime->waitEvent(&event, 200ms)) continue;
        if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
            event.request_id != result.request_id) {
            continue;
        }
        assert(event.completion == sshnative::CompletionKind::kSucceeded);
        assert(event.payload == "auth=ok");
        std::cout << "runtime-private-key-auth-ok\n";
        runtime->shutdown();
        return 0;
    }
    std::cerr << "runtime private key auth timeout\n";
    runtime->shutdown();
    return 1;
}
