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


void Libssh2Session::setBlocking(bool enabled) {
    libssh2_session_set_blocking(session_, enabled ? 1 : 0);
}

void Libssh2Session::handshake(int socket_fd) {
    const int result = libssh2_session_handshake(session_, socket_fd);
    if (result != 0) {
        char* message = nullptr;
        libssh2_session_last_error(session_, &message, nullptr, 0);
        throw std::runtime_error(message != nullptr ? message : "libssh2 handshake failed");
    }
}

bool Libssh2Session::passwordAuth(
    const std::string& username,
    const std::string& password) {
    return libssh2_userauth_password(
        session_, username.c_str(), password.c_str()) == 0;
}

std::string Libssh2Session::libraryVersion() {
    const char* version = libssh2_version(0);
    return version == nullptr ? std::string() : std::string(version);
}

} // namespace sshnative
