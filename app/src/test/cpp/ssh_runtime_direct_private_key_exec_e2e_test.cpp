#include "ssh/ssh_direct_operation.h"
#include "ssh/ssh_runtime.h"

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

    auto runtime = sshnative::createSession();
    auto result = runtime->submit(
        std::make_unique<sshnative::TcpPrivateKeyExecOperation>(
            "127.0.0.1",
            static_cast<uint16_t>(port),
            "test",
            key_buffer.str(),
            "secret",
            "true",
            5s));
    assert(result && "runtime must accept direct private key exec operation");

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
        std::cout << "runtime-direct-private-key-exec-ok\n";
        runtime->shutdown();
        return 0;
    }
    std::cerr << "runtime direct private key exec timeout\n";
    runtime->shutdown();
    return 1;
}
