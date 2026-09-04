#include "ssh_libssh2.h"

#include <mutex>
#include <stdexcept>

namespace sshnative {

namespace {

std::once_flag gInitFlag;

void initializeLibssh2() {
    if (libssh2_init(0) != 0) {
        throw std::runtime_error("libssh2_init failed");
    }
}

void ensureInitialized() {
    std::call_once(gInitFlag, initializeLibssh2);
}

} // namespace

Libssh2Session::Libssh2Session() {
    ensureInitialized();
    session_ = libssh2_session_init();
    if (session_ == nullptr) {
        throw std::runtime_error("libssh2_session_init failed");
    }
}

Libssh2Session::~Libssh2Session() {
    if (session_ != nullptr) {
        libssh2_session_free(session_);
        session_ = nullptr;
    }
}

std::string Libssh2Session::libraryVersion() {
    const char* version = libssh2_version(0);
    return version == nullptr ? std::string() : std::string(version);
}

} // namespace sshnative
