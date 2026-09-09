#include "ssh_sftp_operation.h"

#include <libssh2_sftp.h>

#include <algorithm>
#include <cstdio>
#include <limits>
#include <stdexcept>
#include <utility>
#include <vector>

#include "ssh_error.h"
#include "ssh_libssh2_nonblocking.h"
#include "ssh_persistent_session.h"

namespace sshnative {

namespace {

SshError noSessionError() {
    SshError error;
    error.domain = ErrorDomain::kInternal;
    error.code = "no_active_session";
    error.message = "no active authenticated SSH session";
    return error;
}

SshError invalidHandleError() {
    return {ErrorDomain::kSftp, "sftp_invalid_handle", "SFTP file handle is closed"};
}

SshError invalidClientError() {
    return {ErrorDomain::kSftp, "sftp_client_closed", "SFTP client is closed"};
}

SftpClientResource* findClient(LoopContext& context, ResourceId handle) {
    auto* resource = context.indexedResource(handle, ResourceKind::kSftpSession);
    return resource == nullptr ? nullptr : static_cast<SftpClientResource*>(resource);
}

const char* sftpStatusCode(unsigned long status) {
    switch (status) {
        case LIBSSH2_FX_NO_SUCH_FILE:
        case LIBSSH2_FX_NO_SUCH_PATH: return "sftp_no_such_file";
        case LIBSSH2_FX_PERMISSION_DENIED: return "sftp_permission_denied";
        case LIBSSH2_FX_NO_CONNECTION:
        case LIBSSH2_FX_CONNECTION_LOST: return "sftp_connection_lost";
        case LIBSSH2_FX_OP_UNSUPPORTED: return "sftp_unsupported";
        case LIBSSH2_FX_FILE_ALREADY_EXISTS: return "sftp_already_exists";
        case LIBSSH2_FX_NO_SPACE_ON_FILESYSTEM: return "sftp_no_space";
        case LIBSSH2_FX_QUOTA_EXCEEDED: return "sftp_quota_exceeded";
        case LIBSSH2_FX_DIR_NOT_EMPTY: return "sftp_directory_not_empty";
        case LIBSSH2_FX_NOT_A_DIRECTORY: return "sftp_not_a_directory";
        case LIBSSH2_FX_INVALID_FILENAME: return "sftp_invalid_filename";
        default: return "sftp_failure";
    }
}

SshError sftpError(LIBSSH2_SFTP* sftp, const char* operation) {
    const unsigned long status = sftp == nullptr
        ? LIBSSH2_FX_FAILURE
        : libssh2_sftp_last_error(sftp);
    return {ErrorDomain::kSftp, sftpStatusCode(status),
            std::string(operation) + " failed (SFTP status " +
                std::to_string(status) + ")",
            static_cast<int>(status), 0};
}

Libssh2CallResult classifySftpInt(
    LIBSSH2_SESSION* session, int fd, LIBSSH2_SFTP* sftp,
    int result, const char* operation) {
    Libssh2CallResult translated = classifyLibssh2Int(
        session, fd, result, ErrorDomain::kSftp, operation);
    if (translated.kind == Libssh2CallKind::kFailed) {
        translated.error = sftpError(sftp, operation);
    }
    return translated;
}

Libssh2CallResult classifySftpCount(
    LIBSSH2_SESSION* session, int fd, LIBSSH2_SFTP* sftp,
    ssize_t result, const char* operation) {
    Libssh2CallResult translated = classifyLibssh2Count(
        session, fd, result, ErrorDomain::kSftp, operation);
    if (translated.kind == Libssh2CallKind::kFailed) {
        translated.error = sftpError(sftp, operation);
    }
    return translated;
}

char fileType(unsigned long permissions, unsigned long flags) {
    if ((flags & LIBSSH2_SFTP_ATTR_PERMISSIONS) == 0) return '?';
    if (LIBSSH2_SFTP_S_ISDIR(permissions)) return 'D';
    if (LIBSSH2_SFTP_S_ISLNK(permissions)) return 'L';
    if (LIBSSH2_SFTP_S_ISREG(permissions)) return 'F';
    return 'O';
}

std::string escapeField(const std::string& value) {
    static constexpr char hex[] = "0123456789ABCDEF";
    std::string result;
    result.reserve(value.size());
    for (unsigned char byte : value) {
        if (byte == '%' || byte == '\t' || byte == '\r' || byte == '\n') {
            result.push_back('%');
            result.push_back(hex[byte >> 4]);
            result.push_back(hex[byte & 0x0F]);
        } else {
            result.push_back(static_cast<char>(byte));
        }
    }
    return result;
}

uint64_t saturatedMultiply(uint64_t left, uint64_t right) {
    if (left != 0 && right > std::numeric_limits<uint64_t>::max() / left) {
        return std::numeric_limits<uint64_t>::max();
    }
    return left * right;
}

} // namespace

SftpClientResource::SftpClientResource(
    LIBSSH2_SESSION* session,
    int fd,
    LIBSSH2_SFTP* sftp)
    : session_(session), fd_(fd), sftp_(sftp) {
    if (session_ == nullptr || fd_ < 0 || sftp_ == nullptr) {
        throw std::invalid_argument("invalid SFTP client resource");
    }
}

SftpClientResource::~SftpClientResource() { forceClose(); }

StepResult SftpClientResource::closeStep(const ReadySet&, MonoTime) {
    if (sftp_ == nullptr) return StepResult::complete();
    const int result = libssh2_sftp_shutdown(sftp_);
    Libssh2CallResult translated = classifyLibssh2Int(
        session_, fd_, result, ErrorDomain::kSftp, "sftp_shutdown");
    if (translated.kind == Libssh2CallKind::kWouldBlock) {
        return StepResult::waitIo(std::move(translated.interest));
    }
    sftp_ = nullptr;
    if (translated.kind == Libssh2CallKind::kFailed) {
        return StepResult::failed(std::move(translated.error));
    }
    return StepResult::complete();
}

void SftpClientResource::forceClose() noexcept {
    if (sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
        sftp_ = nullptr;
    }
}

OpenSftpClientOperation::~OpenSftpClientOperation() {
    if (sftp_ != nullptr) libssh2_sftp_shutdown(sftp_);
}

StepResult OpenSftpClientOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return StepResult::failed(noSessionError());
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    LIBSSH2_SESSION* session = session_resource->session()->get();
    const int fd = session_resource->fd();
    if (fd < 0) return StepResult::failed(noSessionError());
    Libssh2CallResult init_result = classifyLibssh2Pointer(
        session, fd, libssh2_sftp_init(session), ErrorDomain::kSftp, "sftp_init");
    if (init_result.kind == Libssh2CallKind::kWouldBlock) {
        return StepResult::waitIo(std::move(init_result.interest));
    }
    if (init_result.kind == Libssh2CallKind::kFailed) {
        return StepResult::failed(std::move(init_result.error));
    }
    sftp_ = static_cast<LIBSSH2_SFTP*>(init_result.pointer);
    auto resource = std::make_unique<SftpClientResource>(session, fd, sftp_);
    sftp_ = nullptr;
    const ResourceId id = context.storeIndexedResource(std::move(resource));
    return StepResult::complete("handle=" + std::to_string(id));
}

StepResult CloseSftpClientOperation::step(
    LoopContext& context,
    const ReadySet& ready,
    MonoTime now) {
    auto* resource = context.indexedResource(handle_, ResourceKind::kSftpSession);
    if (resource == nullptr) return StepResult::complete("closed");
    StepResult result = resource->closeStep(ready, now);
    if (result.kind == StepKind::kComplete || result.kind == StepKind::kFailed) {
        context.releaseIndexedResource(handle_);
        if (result.kind == StepKind::kFailed) return result;
        return StepResult::complete("closed");
    }
    return result;
}

SftpListOperation::SftpListOperation(std::string path, ResourceId client_handle)
    : path_(std::move(path)), client_handle_(client_handle),
      owns_sftp_(client_handle == 0) {
    if (path_.empty()) throw std::invalid_argument("path must not be empty");
}

SftpListOperation::~SftpListOperation() {
    cleanup();
}

void SftpListOperation::cleanup() noexcept {
    if (dir_ != nullptr) {
        libssh2_sftp_closedir(dir_);
        dir_ = nullptr;
    }
    if (owns_sftp_ && sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
    }
    sftp_ = nullptr;
}

StepResult SftpListOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return StepResult::failed(noSessionError());
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    LIBSSH2_SESSION* session = session_resource->session()->get();
    const int fd = session_resource->fd();
    if (fd < 0) return StepResult::failed(noSessionError());

    if (done_) return StepResult::complete(std::move(output_));

    if (sftp_ == nullptr && client_handle_ != 0) {
        auto* client = findClient(context, client_handle_);
        if (client == nullptr) return StepResult::failed(invalidClientError());
        sftp_ = client->sftp();
    } else if (sftp_ == nullptr) {
        Libssh2CallResult init_result = classifyLibssh2Pointer(
            session, fd, libssh2_sftp_init(session),
            ErrorDomain::kSftp, "sftp_init");
        switch (init_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(init_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(init_result.error));
            case Libssh2CallKind::kSucceeded:
                sftp_ = static_cast<LIBSSH2_SFTP*>(init_result.pointer);
                break;
        }
    }

    if (sftp_ != nullptr && dir_ == nullptr) {
        Libssh2CallResult open_result = classifyLibssh2Pointer(
            session, fd, libssh2_sftp_opendir(sftp_, path_.c_str()),
            ErrorDomain::kSftp, "sftp_opendir");
        switch (open_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(open_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(sftpError(sftp_, "sftp_opendir"));
            case Libssh2CallKind::kSucceeded:
                dir_ = static_cast<LIBSSH2_SFTP_HANDLE*>(open_result.pointer);
                break;
        }
    }

    size_t entries_this_step = 0;
    const size_t output_size_at_start = output_.size();
    while (dir_ != nullptr && !entries_done_) {
        char name[512];
        LIBSSH2_SFTP_ATTRIBUTES attrs{};
        const ssize_t result = libssh2_sftp_readdir(
            dir_, name, sizeof(name), &attrs);
        Libssh2CallResult read_result = classifySftpCount(
            session, fd, sftp_, result, "sftp_readdir");
        switch (read_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(read_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(read_result.error));
            case Libssh2CallKind::kSucceeded:
                if (read_result.value > 0) {
                    const std::string entry_name(
                        name, static_cast<size_t>(read_result.value));
                    if (entry_name == "." || entry_name == "..") continue;
                    output_ += escapeField(entry_name) + "\t" +
                        fileType(attrs.permissions, attrs.flags) + "\t" +
                        std::to_string(static_cast<unsigned long long>(attrs.filesize)) + "\t" +
                        std::to_string(static_cast<unsigned long long>(attrs.mtime)) + "\t" +
                        std::to_string(static_cast<unsigned long long>(attrs.permissions & 07777)) + "\t" +
                        std::to_string(static_cast<unsigned long long>(attrs.uid)) + "\t" +
                        std::to_string(static_cast<unsigned long long>(attrs.gid)) + "\n";
                } else {
                    entries_done_ = true;
                    break;
                }
                break;
        }
        if (entries_done_) break;
        if (++entries_this_step >= 64 ||
            output_.size() - output_size_at_start >= 256 * 1024) {
            return StepResult::progress(output_.size() - output_size_at_start);
        }
    }

    if (entries_done_ && dir_ != nullptr) {
        const int result = libssh2_sftp_closedir(dir_);
        Libssh2CallResult close_result = classifySftpInt(
            session, fd, sftp_, result, "sftp_closedir");
        if (close_result.kind == Libssh2CallKind::kWouldBlock) {
            return StepResult::waitIo(std::move(close_result.interest));
        }
        if (close_result.kind == Libssh2CallKind::kFailed) {
            return StepResult::failed(std::move(close_result.error));
        }
        dir_ = nullptr;
        done_ = true;
    }

    if (done_) {
        cleanup();
        return StepResult::complete(std::move(output_));
    }
    return StepResult::noProgress();
}

SftpRealPathOperation::SftpRealPathOperation(std::string path, ResourceId client_handle)
    : path_(std::move(path)), client_handle_(client_handle),
      owns_sftp_(client_handle == 0) {
    if (path_.empty()) throw std::invalid_argument("path must not be empty");
}

SftpRealPathOperation::~SftpRealPathOperation() {
    cleanup();
}

void SftpRealPathOperation::cleanup() noexcept {
    if (owns_sftp_ && sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
    }
    sftp_ = nullptr;
}

StepResult SftpRealPathOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return StepResult::failed(noSessionError());
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    LIBSSH2_SESSION* session = session_resource->session()->get();
    const int fd = session_resource->fd();
    if (fd < 0) return StepResult::failed(noSessionError());

    if (done_) return StepResult::complete(path_);
    if (sftp_ == nullptr && client_handle_ != 0) {
        auto* client = findClient(context, client_handle_);
        if (client == nullptr) return StepResult::failed(invalidClientError());
        sftp_ = client->sftp();
    } else if (sftp_ == nullptr) {
        Libssh2CallResult init_result = classifyLibssh2Pointer(
            session, fd, libssh2_sftp_init(session),
            ErrorDomain::kSftp, "sftp_init");
        switch (init_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(init_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(init_result.error));
            case Libssh2CallKind::kSucceeded:
                sftp_ = static_cast<LIBSSH2_SFTP*>(init_result.pointer);
                break;
        }
    }
    if (sftp_ != nullptr) {
        char target[4096];
        const int result = libssh2_sftp_realpath(
            sftp_, path_.c_str(), target, sizeof(target));
        Libssh2CallResult real_result = classifySftpInt(
            session, fd, sftp_, result, "sftp_realpath");
        switch (real_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(real_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(real_result.error));
            case Libssh2CallKind::kSucceeded:
                path_.assign(target, static_cast<size_t>(result));
                done_ = true;
                break;
        }
    }
    if (done_) {
        cleanup();
        return StepResult::complete(path_);
    }
    return StepResult::noProgress();
}

SftpStatOperation::SftpStatOperation(
    std::string path,
    bool follow_links,
    ResourceId client_handle)
    : path_(std::move(path)), follow_links_(follow_links),
      client_handle_(client_handle), owns_sftp_(client_handle == 0) {
    if (path_.empty()) throw std::invalid_argument("path must not be empty");
}

SftpStatOperation::~SftpStatOperation() {
    cleanup();
}

void SftpStatOperation::cleanup() noexcept {
    if (owns_sftp_ && sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
    }
    sftp_ = nullptr;
}

StepResult SftpStatOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return StepResult::failed(noSessionError());
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    LIBSSH2_SESSION* session = session_resource->session()->get();
    const int fd = session_resource->fd();
    if (fd < 0) return StepResult::failed(noSessionError());

    if (done_) return StepResult::complete(output_);
    if (sftp_ == nullptr && client_handle_ != 0) {
        auto* client = findClient(context, client_handle_);
        if (client == nullptr) return StepResult::failed(invalidClientError());
        sftp_ = client->sftp();
    } else if (sftp_ == nullptr) {
        Libssh2CallResult init_result = classifyLibssh2Pointer(
            session, fd, libssh2_sftp_init(session),
            ErrorDomain::kSftp, "sftp_init");
        switch (init_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(init_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(init_result.error));
            case Libssh2CallKind::kSucceeded:
                sftp_ = static_cast<LIBSSH2_SFTP*>(init_result.pointer);
                break;
        }
    }
    if (sftp_ != nullptr) {
        LIBSSH2_SFTP_ATTRIBUTES attrs{};
        const int result = libssh2_sftp_stat_ex(
            sftp_, path_.c_str(), static_cast<unsigned int>(path_.size()),
            follow_links_ ? LIBSSH2_SFTP_STAT : LIBSSH2_SFTP_LSTAT, &attrs);
        Libssh2CallResult stat_result = classifySftpInt(
            session, fd, sftp_, result, "sftp_stat");
        switch (stat_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(stat_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(stat_result.error));
            case Libssh2CallKind::kSucceeded:
                output_ = std::string(1, fileType(attrs.permissions, attrs.flags)) + "\t" +
                    std::to_string(static_cast<long long>(attrs.filesize)) + "\t" +
                    std::to_string(static_cast<long long>(attrs.mtime)) + "	" +
                    std::to_string(static_cast<long long>(attrs.permissions & 0xFFF)) + "	" +
                    std::to_string(static_cast<long long>(attrs.uid)) + "	" +
                    std::to_string(static_cast<long long>(attrs.gid));
                done_ = true;
                break;
        }
    }
    if (done_) {
        cleanup();
        return StepResult::complete(output_);
    }
    return StepResult::noProgress();
}

SftpReadOperation::SftpReadOperation(
    std::string path,
    uint64_t offset,
    size_t max_bytes)
    : path_(std::move(path)), offset_(offset), max_bytes_(max_bytes) {
    if (path_.empty()) throw std::invalid_argument("path must not be empty");
    if (max_bytes_ == 0) throw std::invalid_argument("max bytes must be positive");
}

SftpReadOperation::~SftpReadOperation() {
    cleanup();
}

void SftpReadOperation::cleanup() noexcept {
    if (file_ != nullptr) {
        libssh2_sftp_close_handle(file_);
        file_ = nullptr;
    }
    if (sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
        sftp_ = nullptr;
    }
}

StepResult SftpReadOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return StepResult::failed(noSessionError());
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    LIBSSH2_SESSION* session = session_resource->session()->get();
    const int fd = session_resource->fd();
    if (fd < 0) return StepResult::failed(noSessionError());

    if (done_) return StepResult::complete(std::move(output_));
    if (sftp_ == nullptr) {
        Libssh2CallResult init_result = classifyLibssh2Pointer(
            session, fd, libssh2_sftp_init(session),
            ErrorDomain::kSftp, "sftp_init");
        switch (init_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(init_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(init_result.error));
            case Libssh2CallKind::kSucceeded:
                sftp_ = static_cast<LIBSSH2_SFTP*>(init_result.pointer);
                break;
        }
    }
    if (sftp_ != nullptr && file_ == nullptr) {
        Libssh2CallResult open_result = classifyLibssh2Pointer(
            session, fd,
            libssh2_sftp_open(sftp_, path_.c_str(), LIBSSH2_FXF_READ, 0),
            ErrorDomain::kSftp, "sftp_open");
        switch (open_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(open_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(open_result.error));
            case Libssh2CallKind::kSucceeded:
                file_ = static_cast<LIBSSH2_SFTP_HANDLE*>(open_result.pointer);
                libssh2_sftp_seek64(file_, offset_);
                break;
        }
    }
    while (file_ != nullptr && output_.size() < max_bytes_) {
        char buffer[8192];
        const size_t want = std::min(sizeof(buffer), max_bytes_ - output_.size());
        const ssize_t result = libssh2_sftp_read(file_, buffer, want);
        Libssh2CallResult read_result = classifyLibssh2Count(
            session, fd, result, ErrorDomain::kSftp, "sftp_read");
        switch (read_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(read_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(read_result.error));
            case Libssh2CallKind::kSucceeded:
                if (read_result.value > 0) {
                    output_.append(buffer, static_cast<size_t>(read_result.value));
                } else {
                    done_ = true;
                }
                break;
        }
        if (done_) break;
    }
    if (output_.size() >= max_bytes_) done_ = true;
    if (done_) {
        cleanup();
        return StepResult::complete(std::move(output_));
    }
    return StepResult::noProgress();
}

SftpWriteOperation::SftpWriteOperation(
    std::string path,
    uint64_t offset,
    std::string data)
    : path_(std::move(path)), offset_(offset), data_(std::move(data)) {
    if (path_.empty()) throw std::invalid_argument("path must not be empty");
    if (data_.empty()) throw std::invalid_argument("data must not be empty");
}

SftpWriteOperation::~SftpWriteOperation() {
    cleanup();
}

void SftpWriteOperation::cleanup() noexcept {
    if (file_ != nullptr) {
        libssh2_sftp_close_handle(file_);
        file_ = nullptr;
    }
    if (sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
        sftp_ = nullptr;
    }
}

StepResult SftpWriteOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return StepResult::failed(noSessionError());
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    LIBSSH2_SESSION* session = session_resource->session()->get();
    const int fd = session_resource->fd();
    if (fd < 0) return StepResult::failed(noSessionError());

    if (done_) return StepResult::complete("written=" + std::to_string(offset_in_data_));
    if (sftp_ == nullptr) {
        Libssh2CallResult init_result = classifyLibssh2Pointer(
            session, fd, libssh2_sftp_init(session),
            ErrorDomain::kSftp, "sftp_init");
        switch (init_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(init_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(init_result.error));
            case Libssh2CallKind::kSucceeded:
                sftp_ = static_cast<LIBSSH2_SFTP*>(init_result.pointer);
                break;
        }
    }
    if (sftp_ != nullptr && file_ == nullptr) {
        unsigned long flags = LIBSSH2_FXF_WRITE | LIBSSH2_FXF_CREAT;
        if (offset_ == 0) flags |= LIBSSH2_FXF_TRUNC;
        Libssh2CallResult open_result = classifyLibssh2Pointer(
            session, fd,
            libssh2_sftp_open(sftp_, path_.c_str(), flags, 0644),
            ErrorDomain::kSftp, "sftp_open");
        switch (open_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(open_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(open_result.error));
            case Libssh2CallKind::kSucceeded:
                file_ = static_cast<LIBSSH2_SFTP_HANDLE*>(open_result.pointer);
                libssh2_sftp_seek64(file_, offset_);
                break;
        }
    }
    while (file_ != nullptr && offset_in_data_ < data_.size()) {
        const ssize_t result = libssh2_sftp_write(
            file_, data_.data() + offset_in_data_,
            data_.size() - offset_in_data_);
        Libssh2CallResult write_result = classifyLibssh2Count(
            session, fd, result, ErrorDomain::kSftp, "sftp_write");
        switch (write_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(write_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(write_result.error));
            case Libssh2CallKind::kSucceeded:
                if (write_result.value > 0) {
                    offset_in_data_ += static_cast<size_t>(write_result.value);
                } else {
                    done_ = true;
                }
                break;
        }
        if (done_) break;
    }
    if (offset_in_data_ >= data_.size()) done_ = true;
    if (done_) {
        cleanup();
        return StepResult::complete("written=" + std::to_string(offset_in_data_));
    }
    return StepResult::noProgress();
}

SftpMkdirOperation::SftpMkdirOperation(
    std::string path,
    long mode,
    ResourceId client_handle)
    : path_(std::move(path)), mode_(mode), client_handle_(client_handle),
      owns_sftp_(client_handle == 0) {
    if (path_.empty()) throw std::invalid_argument("path must not be empty");
}

SftpMkdirOperation::~SftpMkdirOperation() {
    cleanup();
}

void SftpMkdirOperation::cleanup() noexcept {
    if (owns_sftp_ && sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
    }
    sftp_ = nullptr;
}

StepResult SftpMkdirOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return StepResult::failed(noSessionError());
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    LIBSSH2_SESSION* session = session_resource->session()->get();
    const int fd = session_resource->fd();
    if (fd < 0) return StepResult::failed(noSessionError());

    if (done_) return StepResult::complete("mkdir=ok");
    if (sftp_ == nullptr && client_handle_ != 0) {
        auto* client = findClient(context, client_handle_);
        if (client == nullptr) return StepResult::failed(invalidClientError());
        sftp_ = client->sftp();
    } else if (sftp_ == nullptr) {
        Libssh2CallResult init_result = classifyLibssh2Pointer(
            session, fd, libssh2_sftp_init(session),
            ErrorDomain::kSftp, "sftp_init");
        switch (init_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(init_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(init_result.error));
            case Libssh2CallKind::kSucceeded:
                sftp_ = static_cast<LIBSSH2_SFTP*>(init_result.pointer);
                break;
        }
    }
    if (sftp_ != nullptr) {
        const int result = libssh2_sftp_mkdir(sftp_, path_.c_str(), mode_);
        Libssh2CallResult mkdir_result = classifySftpInt(
            session, fd, sftp_, result, "sftp_mkdir");
        switch (mkdir_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(mkdir_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(mkdir_result.error));
            case Libssh2CallKind::kSucceeded:
                done_ = true;
                break;
        }
    }
    if (done_) {
        cleanup();
        return StepResult::complete("mkdir=ok");
    }
    return StepResult::noProgress();
}

SftpCommandOperation::SftpCommandOperation(
    SftpCommand command,
    std::string path,
    std::string target,
    uint64_t value,
    ResourceId client_handle)
    : command_(command), path_(std::move(path)), target_(std::move(target)), value_(value),
      client_handle_(client_handle), owns_sftp_(client_handle == 0) {
    if (path_.empty()) throw std::invalid_argument("path must not be empty");
    if ((command_ == SftpCommand::kRename || command_ == SftpCommand::kSymlink) &&
        target_.empty()) {
        throw std::invalid_argument("target must not be empty");
    }
}

SftpCommandOperation::~SftpCommandOperation() { cleanup(); }

void SftpCommandOperation::cleanup() noexcept {
    if (owns_sftp_ && sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
    }
    sftp_ = nullptr;
}

StepResult SftpCommandOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return StepResult::failed(noSessionError());
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    LIBSSH2_SESSION* session = session_resource->session()->get();
    const int fd = session_resource->fd();
    if (fd < 0) return StepResult::failed(noSessionError());
    if (done_) return StepResult::complete(output_);

    if (sftp_ == nullptr && client_handle_ != 0) {
        auto* client = findClient(context, client_handle_);
        if (client == nullptr) return StepResult::failed(invalidClientError());
        sftp_ = client->sftp();
    } else if (sftp_ == nullptr) {
        Libssh2CallResult init_result = classifyLibssh2Pointer(
            session, fd, libssh2_sftp_init(session),
            ErrorDomain::kSftp, "sftp_init");
        switch (init_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(init_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(init_result.error));
            case Libssh2CallKind::kSucceeded:
                sftp_ = static_cast<LIBSSH2_SFTP*>(init_result.pointer);
                break;
        }
    }

    if ((command_ == SftpCommand::kChown || command_ == SftpCommand::kChgrp) &&
        !ownership_loaded_) {
        LIBSSH2_SFTP_ATTRIBUTES current{};
        const int result = libssh2_sftp_stat_ex(
            sftp_, path_.c_str(), static_cast<unsigned int>(path_.size()),
            LIBSSH2_SFTP_LSTAT, &current);
        Libssh2CallResult translated = classifySftpInt(
            session, fd, sftp_, result, "sftp_lstat");
        if (translated.kind == Libssh2CallKind::kWouldBlock) {
            return StepResult::waitIo(std::move(translated.interest));
        }
        if (translated.kind == Libssh2CallKind::kFailed) {
            return StepResult::failed(std::move(translated.error));
        }
        existing_uid_ = current.uid;
        existing_gid_ = current.gid;
        if ((current.flags & LIBSSH2_SFTP_ATTR_UIDGID) == 0) {
            return StepResult::failed({
                ErrorDomain::kSftp, "sftp_unsupported",
                "server did not return uid/gid attributes"});
        }
        ownership_loaded_ = true;
    }

    int result = 0;
    const char* operation = "sftp_command";
    char target_buffer[4096];
    LIBSSH2_SFTP_STATVFS statvfs{};
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    if (command_ == SftpCommand::kSymlink && verify_existing_symlink_) {
        operation = "sftp_readlink_after_symlink";
        result = libssh2_sftp_readlink(
            sftp_, target_.c_str(), target_buffer, sizeof(target_buffer));
    } else switch (command_) {
        case SftpCommand::kRename:
            operation = "sftp_rename";
            result = rename_fallback_
                ? libssh2_sftp_rename_ex(
                    sftp_, path_.c_str(), static_cast<unsigned int>(path_.size()),
                    target_.c_str(), static_cast<unsigned int>(target_.size()),
                    LIBSSH2_SFTP_RENAME_OVERWRITE)
                : libssh2_sftp_posix_rename_ex(
                    sftp_, path_.c_str(), path_.size(),
                    target_.c_str(), target_.size());
            break;
        case SftpCommand::kUnlink:
            operation = "sftp_unlink";
            result = libssh2_sftp_unlink_ex(
                sftp_, path_.c_str(), static_cast<unsigned int>(path_.size()));
            break;
        case SftpCommand::kRmdir:
            operation = "sftp_rmdir";
            result = libssh2_sftp_rmdir_ex(
                sftp_, path_.c_str(), static_cast<unsigned int>(path_.size()));
            break;
        case SftpCommand::kChmod:
            operation = "sftp_chmod";
            attrs.flags = LIBSSH2_SFTP_ATTR_PERMISSIONS;
            attrs.permissions = static_cast<unsigned long>(value_ & 07777);
            result = libssh2_sftp_setstat(sftp_, path_.c_str(), &attrs);
            break;
        case SftpCommand::kChown:
        case SftpCommand::kChgrp:
            operation = command_ == SftpCommand::kChown ? "sftp_chown" : "sftp_chgrp";
            attrs.flags = LIBSSH2_SFTP_ATTR_UIDGID;
            attrs.uid = command_ == SftpCommand::kChown
                ? static_cast<unsigned long>(value_)
                : existing_uid_;
            attrs.gid = command_ == SftpCommand::kChgrp
                ? static_cast<unsigned long>(value_)
                : existing_gid_;
            result = libssh2_sftp_setstat(sftp_, path_.c_str(), &attrs);
            break;
        case SftpCommand::kSymlink:
            operation = "sftp_symlink";
            // libssh2 follows OpenSSH's de-facto SFTP v3 argument order:
            // existing target first, new link path second.
            result = libssh2_sftp_symlink_ex(
                sftp_, path_.c_str(), static_cast<unsigned int>(path_.size()),
                const_cast<char*>(target_.c_str()), static_cast<unsigned int>(target_.size()),
                LIBSSH2_SFTP_SYMLINK);
            break;
        case SftpCommand::kReadlink:
            operation = "sftp_readlink";
            result = libssh2_sftp_readlink(
                sftp_, path_.c_str(), target_buffer, sizeof(target_buffer));
            break;
        case SftpCommand::kStatVfs:
            operation = "sftp_statvfs";
            result = libssh2_sftp_statvfs(
                sftp_, path_.c_str(), path_.size(), &statvfs);
            break;
    }
    Libssh2CallResult translated = classifySftpInt(
        session, fd, sftp_, result, operation);
    if (translated.kind == Libssh2CallKind::kWouldBlock) {
        return StepResult::waitIo(std::move(translated.interest));
    }
    if (translated.kind == Libssh2CallKind::kFailed) {
        if (command_ == SftpCommand::kRename && !rename_fallback_ &&
            translated.error.libssh2_code == static_cast<int>(LIBSSH2_FX_OP_UNSUPPORTED)) {
            rename_fallback_ = true;
            return StepResult::progress();
        }
        if (command_ == SftpCommand::kSymlink && !verify_existing_symlink_ &&
            translated.error.libssh2_code == static_cast<int>(LIBSSH2_FX_FILE_ALREADY_EXISTS)) {
            symlink_error_ = translated.error;
            verify_existing_symlink_ = true;
            return StepResult::progress();
        }
        return StepResult::failed(std::move(translated.error));
    }

    if (command_ == SftpCommand::kSymlink && verify_existing_symlink_) {
        const std::string actual(target_buffer, static_cast<size_t>(result));
        if (actual != path_) return StepResult::failed(std::move(symlink_error_));
        output_ = "ok";
    } else if (command_ == SftpCommand::kReadlink) {
        output_.assign(target_buffer, static_cast<size_t>(result));
    } else if (command_ == SftpCommand::kStatVfs) {
        const uint64_t fragment = statvfs.f_frsize == 0 ? statvfs.f_bsize : statvfs.f_frsize;
        const uint64_t max_signed = static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
        const uint64_t size = std::min(saturatedMultiply(statvfs.f_blocks, fragment), max_signed);
        const uint64_t free = std::min(saturatedMultiply(statvfs.f_bfree, fragment), max_signed);
        const uint64_t available = std::min(saturatedMultiply(statvfs.f_bavail, fragment), max_signed);
        const uint64_t used = free > size ? 0 : size - free;
        const uint64_t capacity = size == 0 ? 0 :
            std::min<uint64_t>(100, static_cast<uint64_t>(
                (static_cast<long double>(used) * 100.0L) /
                static_cast<long double>(size)));
        output_ = std::to_string(size) + "\t" + std::to_string(used) + "\t" +
            std::to_string(available) + "\t" + std::to_string(capacity);
    } else {
        output_ = "ok";
    }
    done_ = true;
    cleanup();
    return StepResult::complete(output_);
}

SftpFileResource::SftpFileResource(
    LIBSSH2_SESSION* session,
    int fd,
    LIBSSH2_SFTP* sftp,
    LIBSSH2_SFTP_HANDLE* file,
    bool owns_sftp)
    : session_(session), fd_(fd), sftp_(sftp), file_(file),
      owns_sftp_(owns_sftp) {
    if (session_ == nullptr || fd_ < 0 || sftp_ == nullptr || file_ == nullptr) {
        throw std::invalid_argument("invalid SFTP file resource");
    }
}

SftpFileResource::~SftpFileResource() { forceClose(); }

StepResult SftpFileResource::closeStep(const ReadySet&, MonoTime) {
    if (file_ != nullptr) {
        const int result = libssh2_sftp_close_handle(file_);
        Libssh2CallResult translated = classifySftpInt(
            session_, fd_, sftp_, result, "sftp_close_handle");
        if (translated.kind == Libssh2CallKind::kWouldBlock) {
            return StepResult::waitIo(std::move(translated.interest));
        }
        if (translated.kind == Libssh2CallKind::kFailed) {
            forceClose();
            return StepResult::failed(std::move(translated.error));
        }
        file_ = nullptr;
    }
    if (owns_sftp_ && sftp_ != nullptr) {
        const int result = libssh2_sftp_shutdown(sftp_);
        Libssh2CallResult translated = classifyLibssh2Int(
            session_, fd_, result, ErrorDomain::kSftp, "sftp_shutdown");
        if (translated.kind == Libssh2CallKind::kWouldBlock) {
            return StepResult::waitIo(std::move(translated.interest));
        }
        sftp_ = nullptr;
        if (translated.kind == Libssh2CallKind::kFailed) {
            return StepResult::failed(std::move(translated.error));
        }
    }
    return StepResult::complete();
}

void SftpFileResource::forceClose() noexcept {
    if (file_ != nullptr) {
        libssh2_sftp_close_handle(file_);
        file_ = nullptr;
    }
    if (owns_sftp_ && sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
        sftp_ = nullptr;
    }
}

SftpOpenFileOperation::SftpOpenFileOperation(
    std::string path,
    uint64_t offset,
    bool write,
    bool truncate,
    ResourceId client_handle)
    : path_(std::move(path)), offset_(offset), write_(write), truncate_(truncate),
      client_handle_(client_handle), owns_sftp_(client_handle == 0) {
    if (path_.empty()) throw std::invalid_argument("path must not be empty");
}

SftpOpenFileOperation::~SftpOpenFileOperation() { cleanup(); }

void SftpOpenFileOperation::cleanup() noexcept {
    if (file_ != nullptr) {
        libssh2_sftp_close_handle(file_);
        file_ = nullptr;
    }
    if (owns_sftp_ && sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
    }
    sftp_ = nullptr;
}

StepResult SftpOpenFileOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    RuntimeResource* active_resource = context.activeSession();
    if (active_resource == nullptr ||
        active_resource->kind() != ResourceKind::kLibssh2Session) {
        return StepResult::failed(noSessionError());
    }
    auto* session_resource = static_cast<SshSessionResource*>(active_resource);
    LIBSSH2_SESSION* session = session_resource->session()->get();
    const int fd = session_resource->fd();
    if (fd < 0) return StepResult::failed(noSessionError());

    if (sftp_ == nullptr && client_handle_ != 0) {
        auto* client = findClient(context, client_handle_);
        if (client == nullptr) return StepResult::failed(invalidClientError());
        sftp_ = client->sftp();
    } else if (sftp_ == nullptr) {
        Libssh2CallResult init_result = classifyLibssh2Pointer(
            session, fd, libssh2_sftp_init(session),
            ErrorDomain::kSftp, "sftp_init");
        switch (init_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(init_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(init_result.error));
            case Libssh2CallKind::kSucceeded:
                sftp_ = static_cast<LIBSSH2_SFTP*>(init_result.pointer);
                break;
        }
    }

    if (!write_ && !stat_done_) {
        LIBSSH2_SFTP_ATTRIBUTES attrs{};
        const int result = libssh2_sftp_stat_ex(
            sftp_, path_.c_str(), static_cast<unsigned int>(path_.size()),
            LIBSSH2_SFTP_STAT, &attrs);
        Libssh2CallResult translated = classifySftpInt(
            session, fd, sftp_, result, "sftp_stat");
        if (translated.kind == Libssh2CallKind::kWouldBlock) {
            return StepResult::waitIo(std::move(translated.interest));
        }
        if (translated.kind == Libssh2CallKind::kFailed) {
            return StepResult::failed(std::move(translated.error));
        }
        size_ = attrs.filesize;
        stat_done_ = true;
    }

    if (file_ == nullptr) {
        unsigned long flags = write_
            ? LIBSSH2_FXF_WRITE | LIBSSH2_FXF_CREAT
            : LIBSSH2_FXF_READ;
        if (write_ && truncate_) flags |= LIBSSH2_FXF_TRUNC;
        Libssh2CallResult open_result = classifyLibssh2Pointer(
            session, fd,
            libssh2_sftp_open(sftp_, path_.c_str(), flags, 0644),
            ErrorDomain::kSftp, "sftp_open");
        if (open_result.kind == Libssh2CallKind::kWouldBlock) {
            return StepResult::waitIo(std::move(open_result.interest));
        }
        if (open_result.kind == Libssh2CallKind::kFailed) {
            return StepResult::failed(sftpError(sftp_, "sftp_open"));
        }
        file_ = static_cast<LIBSSH2_SFTP_HANDLE*>(open_result.pointer);
        libssh2_sftp_seek64(file_, offset_);
    }

    auto resource = std::make_unique<SftpFileResource>(
        session, fd, sftp_, file_, owns_sftp_);
    if (owns_sftp_) sftp_ = nullptr;
    file_ = nullptr;
    const ResourceId id = context.storeIndexedResource(std::move(resource));
    return StepResult::complete(
        "handle=" + std::to_string(id) + "\nsize=" + std::to_string(size_));
}

SftpHandleReadOperation::SftpHandleReadOperation(ResourceId handle, size_t max_bytes)
    : handle_(handle), max_bytes_(max_bytes) {
    if (handle_ == 0 || max_bytes_ == 0) {
        throw std::invalid_argument("invalid SFTP handle/read size");
    }
}

StepResult SftpHandleReadOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    auto* base = context.indexedResource(handle_, ResourceKind::kSftpHandle);
    if (base == nullptr) return StepResult::failed(invalidHandleError());
    auto* resource = static_cast<SftpFileResource*>(base);
    std::string output(max_bytes_, '\0');
    const ssize_t result = libssh2_sftp_read(
        resource->file(), output.data(), output.size());
    Libssh2CallResult translated = classifySftpCount(
        resource->session(), resource->fd(), resource->sftp(), result, "sftp_read");
    if (translated.kind == Libssh2CallKind::kWouldBlock) {
        return StepResult::waitIo(std::move(translated.interest));
    }
    if (translated.kind == Libssh2CallKind::kFailed) {
        return StepResult::failed(std::move(translated.error));
    }
    output.resize(static_cast<size_t>(translated.value));
    return StepResult::complete(std::move(output));
}

SftpHandleWriteOperation::SftpHandleWriteOperation(ResourceId handle, std::string data)
    : handle_(handle), data_(std::move(data)) {
    if (handle_ == 0) throw std::invalid_argument("invalid SFTP handle");
}

StepResult SftpHandleWriteOperation::step(
    LoopContext& context,
    const ReadySet&,
    MonoTime) {
    auto* base = context.indexedResource(handle_, ResourceKind::kSftpHandle);
    if (base == nullptr) return StepResult::failed(invalidHandleError());
    auto* resource = static_cast<SftpFileResource*>(base);
    if (data_.empty()) return StepResult::complete("written=0");
    const ssize_t result = libssh2_sftp_write(
        resource->file(), data_.data() + offset_, data_.size() - offset_);
    Libssh2CallResult translated = classifySftpCount(
        resource->session(), resource->fd(), resource->sftp(), result, "sftp_write");
    if (translated.kind == Libssh2CallKind::kWouldBlock) {
        return StepResult::waitIo(std::move(translated.interest));
    }
    if (translated.kind == Libssh2CallKind::kFailed) {
        return StepResult::failed(std::move(translated.error));
    }
    if (translated.value <= 0) {
        return StepResult::failed(sftpError(resource->sftp(), "sftp_write"));
    }
    offset_ += static_cast<size_t>(translated.value);
    if (offset_ < data_.size()) {
        return StepResult::progress(static_cast<size_t>(translated.value));
    }
    return StepResult::complete("written=" + std::to_string(offset_));
}

StepResult SftpCloseHandleOperation::step(
    LoopContext& context,
    const ReadySet& ready,
    MonoTime now) {
    auto* base = context.indexedResource(handle_, ResourceKind::kSftpHandle);
    if (base == nullptr) return StepResult::complete("closed");
    StepResult result = base->closeStep(ready, now);
    if (result.kind == StepKind::kComplete || result.kind == StepKind::kFailed) {
        context.releaseIndexedResource(handle_);
        if (result.kind == StepKind::kFailed) return result;
        return StepResult::complete("closed");
    }
    return result;
}

} // namespace sshnative
