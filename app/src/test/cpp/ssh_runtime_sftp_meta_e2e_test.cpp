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
#include <unistd.h>

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

    {
        auto runtime = openSession(port);
        const std::string base = "/tmp/ssh-native-sftp-lifecycle-" +
            std::to_string(static_cast<long long>(getpid()));
        const std::string original = base + "/source.bin";
        const std::string renamed = base + "/renamed.bin";
        const std::string link = base + "/link.bin";
        const std::string sparse = base + "/sparse.bin";
        const std::string content("stream\0payload", 14);

        auto open_client = runtime->submit(
            std::make_unique<sshnative::OpenSftpClientOperation>());
        assert(waitForEvent(*runtime, open_client, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        const auto client_handle = static_cast<sshnative::ResourceId>(
            std::stoull(e.payload.substr(std::string("handle=").size())));

        auto mkdir_result = runtime->submit(std::make_unique<sshnative::SftpMkdirOperation>(
            base, 0755, client_handle));
        assert(waitForEvent(*runtime, mkdir_result, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);

        auto open_write = runtime->submit(std::make_unique<sshnative::SftpOpenFileOperation>(
            original, 0, true, true, client_handle));
        assert(waitForEvent(*runtime, open_write, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        const auto handle_line = e.payload.substr(0, e.payload.find('\n'));
        const auto write_handle = static_cast<sshnative::ResourceId>(
            std::stoull(handle_line.substr(std::string("handle=").size())));

        auto stream_write = runtime->submit(std::make_unique<sshnative::SftpHandleWriteOperation>(
            write_handle, content));
        assert(waitForEvent(*runtime, stream_write, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        auto close_write = runtime->submit(
            std::make_unique<sshnative::SftpCloseHandleOperation>(write_handle));
        assert(waitForEvent(*runtime, close_write, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);

        auto open_read = runtime->submit(std::make_unique<sshnative::SftpOpenFileOperation>(
            original, 3, false, false, client_handle));
        assert(waitForEvent(*runtime, open_read, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        const auto read_handle_line = e.payload.substr(0, e.payload.find('\n'));
        const auto read_handle = static_cast<sshnative::ResourceId>(
            std::stoull(read_handle_line.substr(std::string("handle=").size())));
        assert(e.payload.find("size=14") != std::string::npos);

        auto stream_read = runtime->submit(std::make_unique<sshnative::SftpHandleReadOperation>(
            read_handle, 64));
        assert(waitForEvent(*runtime, stream_read, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        assert(e.payload == content.substr(3));
        auto eof_read = runtime->submit(std::make_unique<sshnative::SftpHandleReadOperation>(
            read_handle, 64));
        assert(waitForEvent(*runtime, eof_read, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        assert(e.payload.empty());
        auto close_read = runtime->submit(
            std::make_unique<sshnative::SftpCloseHandleOperation>(read_handle));
        assert(waitForEvent(*runtime, close_read, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        auto close_again = runtime->submit(
            std::make_unique<sshnative::SftpCloseHandleOperation>(read_handle));
        assert(waitForEvent(*runtime, close_again, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);

        auto chmod_result = runtime->submit(std::make_unique<sshnative::SftpCommandOperation>(
            sshnative::SftpCommand::kChmod, original, "", 0600, client_handle));
        assert(waitForEvent(*runtime, chmod_result, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        auto rename_result = runtime->submit(std::make_unique<sshnative::SftpCommandOperation>(
            sshnative::SftpCommand::kRename, original, renamed, 0, client_handle));
        assert(waitForEvent(*runtime, rename_result, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        auto symlink_result = runtime->submit(std::make_unique<sshnative::SftpCommandOperation>(
            sshnative::SftpCommand::kSymlink, "renamed.bin", link, 0, client_handle));
        assert(waitForEvent(*runtime, symlink_result, &e));
        if (e.completion != sshnative::CompletionKind::kSucceeded) {
            std::cerr << "symlink failed: " << e.error.code << " "
                      << e.error.message << "\n";
        }
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        auto readlink_result = runtime->submit(std::make_unique<sshnative::SftpCommandOperation>(
            sshnative::SftpCommand::kReadlink, link, "", 0, client_handle));
        assert(waitForEvent(*runtime, readlink_result, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        if (e.payload != "renamed.bin") {
            std::cerr << "readlink mismatch: expected=renamed.bin"
                      << " actual=" << e.payload << "\n";
        }
        assert(e.payload == "renamed.bin");
        auto statvfs_result = runtime->submit(std::make_unique<sshnative::SftpCommandOperation>(
            sshnative::SftpCommand::kStatVfs, base, "", 0, client_handle));
        assert(waitForEvent(*runtime, statvfs_result, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        assert(e.payload.find('\t') != std::string::npos);

        constexpr uint64_t sparse_offset = (uint64_t{1} << 31) + 17;
        auto open_sparse = runtime->submit(std::make_unique<sshnative::SftpOpenFileOperation>(
            sparse, sparse_offset, true, true, client_handle));
        assert(waitForEvent(*runtime, open_sparse, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        const auto sparse_handle = static_cast<sshnative::ResourceId>(std::stoull(
            e.payload.substr(std::string("handle=").size(),
                e.payload.find('\n') - std::string("handle=").size())));
        auto sparse_write = runtime->submit(std::make_unique<sshnative::SftpHandleWriteOperation>(
            sparse_handle, "x"));
        assert(waitForEvent(*runtime, sparse_write, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        auto sparse_close = runtime->submit(
            std::make_unique<sshnative::SftpCloseHandleOperation>(sparse_handle));
        assert(waitForEvent(*runtime, sparse_close, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        auto sparse_stat = runtime->submit(std::make_unique<sshnative::SftpStatOperation>(
            sparse, true, client_handle));
        assert(waitForEvent(*runtime, sparse_stat, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        assert(e.payload.find(std::to_string(sparse_offset + 1)) != std::string::npos);

        for (const std::string& path : {link, renamed, sparse}) {
            auto unlink_result = runtime->submit(std::make_unique<sshnative::SftpCommandOperation>(
                sshnative::SftpCommand::kUnlink, path, "", 0, client_handle));
            assert(waitForEvent(*runtime, unlink_result, &e));
            assert(e.completion == sshnative::CompletionKind::kSucceeded);
        }
        auto rmdir_result = runtime->submit(std::make_unique<sshnative::SftpCommandOperation>(
            sshnative::SftpCommand::kRmdir, base, "", 0, client_handle));
        assert(waitForEvent(*runtime, rmdir_result, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        auto missing = runtime->submit(std::make_unique<sshnative::SftpStatOperation>(
            base, false, client_handle));
        assert(waitForEvent(*runtime, missing, &e));
        assert(e.completion == sshnative::CompletionKind::kFailed);
        assert(e.error.code == "sftp_no_such_file");
        auto close_client = runtime->submit(
            std::make_unique<sshnative::CloseSftpClientOperation>(client_handle));
        assert(waitForEvent(*runtime, close_client, &e));
        assert(e.completion == sshnative::CompletionKind::kSucceeded);
        runtime->shutdown();
    }

    std::cout << "runtime-sftp-meta-ok\n";
    return 0;
}
