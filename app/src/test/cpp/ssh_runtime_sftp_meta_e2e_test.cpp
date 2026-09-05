#include "ssh/ssh_connect_operation.h"
#include "ssh/ssh_persistent_session.h"
#include "ssh/ssh_runtime.h"
#include "ssh/ssh_sftp_operation.h"

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
    const auto stop = sshnative::MonoClock::now() + 8s;
    while (sshnative::MonoClock::now() < stop) {
        sshnative::RuntimeEvent event;
        if (!runtime.waitEvent(&event, 100ms)) continue;
        if (event.kind != sshnative::RuntimeEventKind::kCompletion ||
            event.request_id != result.request_id) {
            continue;
        }
        *out = std::move(event);
        return true;
    }
    return false;
}

std::shared_ptr<sshnative::SshNativeSession> openSession(int port) {
    auto runtime = sshnative::createSession();
    auto connect = runtime->submit(std::make_unique<sshnative::TcpConnectOperation>(
        "127.0.0.1", static_cast<uint16_t>(port), 5s));
    sshnative::RuntimeEvent e;
    assert(waitForEvent(*runtime, connect, &e));
    assert(e.completion == sshnative::CompletionKind::kSucceeded);
    auto open = runtime->submit(std::make_unique<sshnative::OpenAuthenticatedSessionOperation>(
        "test", "secret", "", "", ""));
    assert(waitForEvent(*runtime, open, &e));
    assert(e.completion == sshnative::CompletionKind::kSucceeded);
    return runtime;
}
} // namespace

int main(int argc, char** argv) {
    if (argc != 2) return 64;
    const int port = std::atoi(argv[1]);
    sshnative::RuntimeEvent e;

    {
        auto runtime = openSession(port);
        auto real = runtime->submit(std::make_unique<sshnative::SftpRealPathOperation>("."));
        assert(waitForEvent(*runtime, real, &e));
        if (e.completion != sshnative::CompletionKind::kSucceeded ||
            e.payload.empty()) {
            std::cerr << "realpath failed: " << e.error.message << "\n";
            return 1;
        }
        runtime->shutdown();
    }

    {
        auto runtime = openSession(port);
        auto stat = runtime->submit(std::make_unique<sshnative::SftpStatOperation>(".", true));
        assert(waitForEvent(*runtime, stat, &e));
        if (e.completion != sshnative::CompletionKind::kSucceeded) {
            std::cerr << "stat failed: " << e.error.message << "\n";
            return 1;
        }
        if (e.payload.find('\t') == std::string::npos) {
            std::cerr << "stat payload unexpected: " << e.payload << "\n";
            return 1;
        }
        runtime->shutdown();
    }

    {
        auto runtime = openSession(port);
        auto read = runtime->submit(std::make_unique<sshnative::SftpReadOperation>(
            "README.md", 0, 1024));
        assert(waitForEvent(*runtime, read, &e));
        if (e.completion != sshnative::CompletionKind::kSucceeded ||
            e.payload.empty()) {
            std::cerr << "sftp read failed: " << e.error.message << "\n";
            return 1;
        }
        runtime->shutdown();
    }

    {
        auto runtime = openSession(port);
        const std::string path = "/tmp/ssh-native-sftp-write-test.txt";
        const std::string content = "sftp-native-write-ok";
        auto write = runtime->submit(std::make_unique<sshnative::SftpWriteOperation>(
            path, 0, content));
        assert(waitForEvent(*runtime, write, &e));
        if (e.completion != sshnative::CompletionKind::kSucceeded) {
            std::cerr << "sftp write failed: " << e.error.message << "\n";
            return 1;
        }
        runtime->shutdown();

        runtime = openSession(port);
        auto read = runtime->submit(std::make_unique<sshnative::SftpReadOperation>(
            path, 0, content.size() + 16));
        assert(waitForEvent(*runtime, read, &e));
        if (e.completion != sshnative::CompletionKind::kSucceeded ||
            e.payload != content) {
            std::cerr << "sftp write/read mismatch: " << e.error.message << "\n";
            return 1;
        }
        runtime->shutdown();
    }

    std::cout << "runtime-sftp-meta-ok\n";
    return 0;
}
