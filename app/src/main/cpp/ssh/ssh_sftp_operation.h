#pragma once

#include "ssh_runtime.h"
#include "ssh_libssh2.h"

#include <cstdint>
#include <memory>
#include <string>

struct _LIBSSH2_SFTP;
struct _LIBSSH2_SFTP_HANDLE;

namespace sshnative {

class SftpClientResource final : public RuntimeResource {
public:
    SftpClientResource(_LIBSSH2_SESSION* session, int fd, _LIBSSH2_SFTP* sftp);
    ~SftpClientResource() override;

    ResourceKind kind() const noexcept override { return ResourceKind::kSftpSession; }
    StepResult closeStep(const ReadySet& ready, MonoTime now) override;
    void forceClose() noexcept override;

    _LIBSSH2_SESSION* session() const noexcept { return session_; }
    int fd() const noexcept { return fd_; }
    _LIBSSH2_SFTP* sftp() const noexcept { return sftp_; }

private:
    _LIBSSH2_SESSION* session_ = nullptr;
    int fd_ = -1;
    _LIBSSH2_SFTP* sftp_ = nullptr;
};

class OpenSftpClientOperation final : public Operation {
public:
    ~OpenSftpClientOperation() override;
    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    _LIBSSH2_SFTP* sftp_ = nullptr;
};

class CloseSftpClientOperation final : public Operation {
public:
    explicit CloseSftpClientOperation(ResourceId handle) : handle_(handle) {}
    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    ResourceId handle_ = 0;
};

// One-shot SFTP directory listing on the active SSH session. It initializes an
// SFTP session, opens a directory, reads all entries, and shuts SFTP down before
// completing. Completion payload is lines of "name\ttype\tsize".
class SftpListOperation final : public Operation {
public:
    explicit SftpListOperation(std::string path, ResourceId client_handle = 0);
    ~SftpListOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    std::string path_;
    ResourceId client_handle_ = 0;
    bool owns_sftp_ = true;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    _LIBSSH2_SFTP_HANDLE* dir_ = nullptr;
    std::string output_;
    bool entries_done_ = false;
    bool done_ = false;
};

// One-shot SFTP realpath on the active SSH session.
class SftpRealPathOperation final : public Operation {
public:
    explicit SftpRealPathOperation(std::string path, ResourceId client_handle = 0);
    ~SftpRealPathOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    std::string path_;
    ResourceId client_handle_ = 0;
    bool owns_sftp_ = true;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    bool done_ = false;
};

// One-shot SFTP stat/lstat on the active SSH session. Completion payload is
// "type\tsize\tmodified\tpermissions\tuid\tgid".
class SftpStatOperation final : public Operation {
public:
    SftpStatOperation(std::string path, bool follow_links, ResourceId client_handle = 0);
    ~SftpStatOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    std::string path_;
    bool follow_links_ = true;
    ResourceId client_handle_ = 0;
    bool owns_sftp_ = true;
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

// One-shot SFTP file write on the active SSH session. Completion payload is
// "written=<N>".
class SftpWriteOperation final : public Operation {
public:
    SftpWriteOperation(std::string path, uint64_t offset, std::string data);
    ~SftpWriteOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    std::string path_;
    uint64_t offset_ = 0;
    std::string data_;
    size_t offset_in_data_ = 0;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    _LIBSSH2_SFTP_HANDLE* file_ = nullptr;
    bool done_ = false;
};

// One-shot SFTP mkdir on the active SSH session.
class SftpMkdirOperation final : public Operation {
public:
    SftpMkdirOperation(std::string path, long mode = 0755, ResourceId client_handle = 0);
    ~SftpMkdirOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    std::string path_;
    long mode_ = 0755;
    ResourceId client_handle_ = 0;
    bool owns_sftp_ = true;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    bool done_ = false;
};

enum class SftpCommand {
    kRename,
    kUnlink,
    kRmdir,
    kChmod,
    kChown,
    kChgrp,
    kSymlink,
    kReadlink,
    kStatVfs,
};

// Remaining one-shot metadata/path operations. Payload is command-specific:
// readlink returns the target and statvfs returns
// "size\tused\tavailable\tcapacity". Mutations return "ok".
class SftpCommandOperation final : public Operation {
public:
    SftpCommandOperation(
        SftpCommand command,
        std::string path,
        std::string target = {},
        uint64_t value = 0,
        ResourceId client_handle = 0);
    ~SftpCommandOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    SftpCommand command_;
    std::string path_;
    std::string target_;
    uint64_t value_ = 0;
    ResourceId client_handle_ = 0;
    bool owns_sftp_ = true;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    std::string output_;
    unsigned long existing_uid_ = 0;
    unsigned long existing_gid_ = 0;
    bool ownership_loaded_ = false;
    bool rename_fallback_ = false;
    bool verify_existing_symlink_ = false;
    SshError symlink_error_;
    bool done_ = false;
};

// A file and its SFTP session kept alive between runtime requests. The opaque
// ResourceId is the only value exposed across JNI.
class SftpFileResource final : public RuntimeResource {
public:
    SftpFileResource(
        _LIBSSH2_SESSION* session,
        int fd,
        _LIBSSH2_SFTP* sftp,
        _LIBSSH2_SFTP_HANDLE* file,
        bool owns_sftp = true);
    ~SftpFileResource() override;

    ResourceKind kind() const noexcept override { return ResourceKind::kSftpHandle; }
    StepResult closeStep(const ReadySet& ready, MonoTime now) override;
    void forceClose() noexcept override;

    _LIBSSH2_SESSION* session() const noexcept { return session_; }
    int fd() const noexcept { return fd_; }
    _LIBSSH2_SFTP* sftp() const noexcept { return sftp_; }
    _LIBSSH2_SFTP_HANDLE* file() const noexcept { return file_; }

private:
    _LIBSSH2_SESSION* session_ = nullptr;
    int fd_ = -1;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    _LIBSSH2_SFTP_HANDLE* file_ = nullptr;
    bool owns_sftp_ = true;
};

class SftpOpenFileOperation final : public Operation {
public:
    SftpOpenFileOperation(
        std::string path,
        uint64_t offset,
        bool write,
        bool truncate,
        ResourceId client_handle = 0);
    ~SftpOpenFileOperation() override;

    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    void cleanup() noexcept;

    std::string path_;
    uint64_t offset_ = 0;
    bool write_ = false;
    bool truncate_ = false;
    ResourceId client_handle_ = 0;
    bool owns_sftp_ = true;
    _LIBSSH2_SFTP* sftp_ = nullptr;
    _LIBSSH2_SFTP_HANDLE* file_ = nullptr;
    uint64_t size_ = 0;
    bool stat_done_ = false;
};

class SftpHandleReadOperation final : public Operation {
public:
    SftpHandleReadOperation(ResourceId handle, size_t max_bytes);
    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    ResourceId handle_ = 0;
    size_t max_bytes_ = 0;
};

class SftpHandleWriteOperation final : public Operation {
public:
    SftpHandleWriteOperation(ResourceId handle, std::string data);
    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    ResourceId handle_ = 0;
    std::string data_;
    size_t offset_ = 0;
};

class SftpCloseHandleOperation final : public Operation {
public:
    explicit SftpCloseHandleOperation(ResourceId handle) : handle_(handle) {}
    StepResult step(LoopContext& context, const ReadySet& ready, MonoTime now) override;

private:
    ResourceId handle_ = 0;
};

} // namespace sshnative
