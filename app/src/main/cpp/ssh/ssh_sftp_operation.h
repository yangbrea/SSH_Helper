#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

#include <string>

struct _LIBSSH2_SFTP;
struct _LIBSSH2_SFTP_HANDLE;

namespace sshnative {

// One-shot SFTP directory listing on the active SSH session. It initializes an
// SFTP session, opens a directory, reads all entries, and shuts SFTP down before
// completing. Completion payload is lines of "name\ttype\tsize".
class SftpListOperation final : public Operation {
public:
    explicit SftpListOperation(std::string path);
    ~SftpListOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    std::string path_;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    _LIBSSH2_SFTP_HANDLE* dir_ = nullptr;
    std::string output_;
    bool done_ = false;
};

} // namespace sshnative
