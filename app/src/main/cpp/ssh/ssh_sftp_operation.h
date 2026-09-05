#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

#include <cstdint>
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

// One-shot SFTP realpath on the active SSH session.
class SftpRealPathOperation final : public Operation {
public:
    explicit SftpRealPathOperation(std::string path);
    ~SftpRealPathOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    std::string path_;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    bool done_ = false;
};

// One-shot SFTP stat/lstat on the active SSH session. Completion payload is
// "type\tsize\tmodified\tpermissions\tuid\tgid".
class SftpStatOperation final : public Operation {
public:
    SftpStatOperation(std::string path, bool follow_links);
    ~SftpStatOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    std::string path_;
    bool follow_links_ = true;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    std::string output_;
    bool done_ = false;
};

// One-shot SFTP file read on the active SSH session. Completion payload is the
// raw bytes read (up to max_bytes).
class SftpReadOperation final : public Operation {
public:
    SftpReadOperation(std::string path, uint64_t offset, size_t max_bytes);
    ~SftpReadOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    std::string path_;
    uint64_t offset_ = 0;
    size_t max_bytes_ = 0;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    _LIBSSH2_SFTP_HANDLE* file_ = nullptr;
    std::string output_;
    bool done_ = false;
};

} // namespace sshnative
