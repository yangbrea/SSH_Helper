#include "ssh_sftp_operation.h"

#include <libssh2_sftp.h>

#include <cstdio>
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

} // namespace

SftpListOperation::SftpListOperation(std::string path)
    : path_(std::move(path)) {
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
    if (sftp_ != nullptr) {
        libssh2_sftp_shutdown(sftp_);
        sftp_ = nullptr;
    }
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

    if (sftp_ != nullptr && dir_ == nullptr) {
        Libssh2CallResult open_result = classifyLibssh2Pointer(
            session, fd, libssh2_sftp_opendir(sftp_, path_.c_str()),
            ErrorDomain::kSftp, "sftp_opendir");
        switch (open_result.kind) {
            case Libssh2CallKind::kWouldBlock:
                return StepResult::waitIo(std::move(open_result.interest));
            case Libssh2CallKind::kFailed:
                return StepResult::failed(std::move(open_result.error));
            case Libssh2CallKind::kSucceeded:
                dir_ = static_cast<LIBSSH2_SFTP_HANDLE*>(open_result.pointer);
                break;
        }
    }

    while (dir_ != nullptr) {
        char name[512];
        LIBSSH2_SFTP_ATTRIBUTES attrs{};
        const ssize_t result = libssh2_sftp_readdir(
            dir_, name, sizeof(name), &attrs);
        Libssh2CallResult read_result = classifyLibssh2Count(
            session, fd, result, ErrorDomain::kSftp, "sftp_readdir");
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
                    const char type = (attrs.flags & LIBSSH2_SFTP_ATTR_PERMISSIONS)
                        ? ((attrs.permissions & LIBSSH2_SFTP_S_IFMT) == LIBSSH2_SFTP_S_IFDIR ? 'D' : 'F')
                        : '?';
                    output_ += entry_name + "\t" + type + "\t" +
                        std::to_string(static_cast<long long>(attrs.filesize)) + "\n";
                } else {
                    done_ = true;
                    break;
                }
                break;
        }
        if (done_) break;
    }

    if (done_) {
        cleanup();
        return StepResult::complete(std::move(output_));
    }
    return StepResult::noProgress();
}

} // namespace sshnative
