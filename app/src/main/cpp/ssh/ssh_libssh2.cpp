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



bool Libssh2Session::publicKeyAuth(
    const std::string& username,
    const std::string& private_key,
    const std::string& passphrase) {
    const int result = libssh2_userauth_publickey_frommemory(
        session_,
        username.c_str(),
        username.size(),
        nullptr,
        0,
        private_key.data(),
        private_key.size(),
        passphrase.empty() ? nullptr : passphrase.c_str());
    return result == 0;
}

std::vector<uint8_t> Libssh2Session::hostKey(int* type_out) {
    size_t length = 0;
    int type = 0;
    const char* key = libssh2_session_hostkey(session_, &length, &type);
    if (key == nullptr) {
        throw std::runtime_error("libssh2_session_hostkey failed");
    }
    if (type_out != nullptr) {
        *type_out = type;
    }
    return std::vector<uint8_t>(key, key + length);
}

int Libssh2Session::execCommand(const std::string& command, std::string& output) {
    LIBSSH2_CHANNEL* channel = libssh2_channel_open_session(session_);
    if (channel == nullptr) {
        throw std::runtime_error("libssh2_channel_open_session failed");
    }
    const int exec_result = libssh2_channel_exec(channel, command.c_str());
    if (exec_result != 0) {
        libssh2_channel_free(channel);
        throw std::runtime_error("libssh2_channel_exec failed");
    }

    output.clear();
    char buffer[4096];
    while (true) {
        const ssize_t count = libssh2_channel_read(channel, buffer, sizeof(buffer));
        if (count > 0) {
            output.append(buffer, static_cast<size_t>(count));
            continue;
        }
        if (count == LIBSSH2_ERROR_EAGAIN) {
            continue;
        }
        break;
    }
    const int exit_status = libssh2_channel_get_exit_status(channel);
    libssh2_channel_close(channel);
    libssh2_channel_free(channel);
    return exit_status;
}

std::string Libssh2Session::libraryVersion() {
    const char* version = libssh2_version(0);
    return version == nullptr ? std::string() : std::string(version);
}

} // namespace sshnative
