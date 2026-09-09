#include <libssh2.h>

#include <arpa/inet.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <poll.h>
#include <string>
#include <sys/socket.h>
#include <unistd.h>

namespace {

struct TunnelContext {
    LIBSSH2_SESSION* jump_session = nullptr;
    LIBSSH2_CHANNEL* channel = nullptr;
};

int connectHost(const char* host, int port) {
    const int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, host, &address.sin_addr) != 1) {
        close(fd);
        return -1;
    }
    if (connect(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

ssize_t tunnelSend(libssh2_socket_t, const void* buffer, size_t length,
                   int, void** abstract) {
    auto* context = static_cast<TunnelContext*>(*abstract);
    const ssize_t result = libssh2_channel_write(
        context->channel, static_cast<const char*>(buffer), length);
    if (result >= 0) return result;
    if (result == LIBSSH2_ERROR_EAGAIN) return -EAGAIN;
    return -1;
}

ssize_t tunnelRecv(libssh2_socket_t, void* buffer, size_t length,
                   int, void** abstract) {
    auto* context = static_cast<TunnelContext*>(*abstract);
    const ssize_t result = libssh2_channel_read(
        context->channel, static_cast<char*>(buffer), length);
    if (result >= 0) return result;
    if (result == LIBSSH2_ERROR_EAGAIN) return -EAGAIN;
    return -1;
}

bool waitForFd(int fd, short events) {
    pollfd descriptor{fd, events, 0};
    int result = 0;
    do {
        result = ::poll(&descriptor, 1, 2000);
    } while (result < 0 && errno == EINTR);
    return result > 0;
}

short pollEventsFor(LIBSSH2_SESSION* session) {
    const int directions = libssh2_session_block_directions(session);
    short events = 0;
    if ((directions & LIBSSH2_SESSION_BLOCK_INBOUND) != 0) events |= POLLIN;
    if ((directions & LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) events |= POLLOUT;
    return events != 0 ? events : static_cast<short>(POLLIN | POLLOUT);
}

LIBSSH2_CHANNEL* openSessionNonblock(LIBSSH2_SESSION* session, int fd) {
    for (int attempt = 0; attempt < 10000; ++attempt) {
        LIBSSH2_CHANNEL* channel = libssh2_channel_open_session(session);
        if (channel != nullptr) return channel;
        if (libssh2_session_last_errno(session) != LIBSSH2_ERROR_EAGAIN) {
            return nullptr;
        }
        if (!waitForFd(fd, pollEventsFor(session))) return nullptr;
    }
    return nullptr;
}

int execNonblock(LIBSSH2_SESSION* session, LIBSSH2_CHANNEL* channel,
                 int fd, const char* command) {
    for (int attempt = 0; attempt < 10000; ++attempt) {
        const int result = libssh2_channel_exec(channel, command);
        if (result == 0) return 0;
        if (result != LIBSSH2_ERROR_EAGAIN) return result;
        if (!waitForFd(fd, pollEventsFor(session))) return -1;
    }
    return -1;
}

int readAllNonblock(LIBSSH2_SESSION* session, LIBSSH2_CHANNEL* channel,
                    int fd, std::string* output) {
    char buffer[1024];
    for (int attempt = 0; attempt < 100000; ++attempt) {
        if (libssh2_channel_eof(channel)) return 0;
        const ssize_t count = libssh2_channel_read(channel, buffer, sizeof(buffer));
        if (count > 0) {
            output->append(buffer, static_cast<size_t>(count));
            continue;
        }
        if (count == LIBSSH2_ERROR_EAGAIN) {
            if (!waitForFd(fd, pollEventsFor(session))) return -1;
            continue;
        }
        return count < 0 ? static_cast<int>(count) : 0;
    }
    return -1;
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 3) {
        std::cerr << "usage: " << argv[0] << " <target_port> <jump_port>\n";
        return 64;
    }
    const int target_port = std::atoi(argv[1]);
    const int jump_port = std::atoi(argv[2]);
    if (target_port <= 0 || jump_port <= 0) return 64;

    if (libssh2_init(0) != 0) return 2;
    const int jump_fd = connectHost("127.0.0.1", jump_port);
    if (jump_fd < 0) return 3;

    LIBSSH2_SESSION* jump = libssh2_session_init();
    if (jump == nullptr) return 4;
    libssh2_session_set_blocking(jump, 1);
    if (libssh2_session_handshake(jump, jump_fd) != 0) return 5;
    if (libssh2_userauth_password(jump, "test", "secret-jump") != 0) return 6;

    LIBSSH2_CHANNEL* tunnel = libssh2_channel_direct_tcpip_ex(
        jump, "127.0.0.1", target_port, "127.0.0.1", 0);
    if (tunnel == nullptr) return 7;

    TunnelContext context{jump, tunnel};
    LIBSSH2_SESSION* target = libssh2_session_init_ex(
        nullptr, nullptr, nullptr, &context);
    if (target == nullptr) return 8;
    libssh2_session_set_blocking(target, 1);
    libssh2_session_callback_set2(
        target, LIBSSH2_CALLBACK_SEND,
        reinterpret_cast<libssh2_cb_generic*>(&tunnelSend));
    libssh2_session_callback_set2(
        target, LIBSSH2_CALLBACK_RECV,
        reinterpret_cast<libssh2_cb_generic*>(&tunnelRecv));

    if (libssh2_session_handshake(target, 0) != 0) return 9;
    if (libssh2_userauth_password(target, "test", "secret-target") != 0) return 10;

    // Switch to nonblocking after the blocking handshake/auth POC. Subsequent
    // channel/exec/read phases exercise the same -EAGAIN path used by the real
    // native runtime.
    libssh2_session_set_blocking(jump, 0);
    libssh2_session_set_blocking(target, 0);

    LIBSSH2_CHANNEL* channel = openSessionNonblock(target, jump_fd);
    if (channel == nullptr) return 11;
    if (execNonblock(target, channel, jump_fd, "test") != 0) return 12;

    std::string output;
    if (readAllNonblock(target, channel, jump_fd, &output) != 0) return 13;
    if (output.find("native-jump-poc-exec-ok") == std::string::npos) {
        std::cerr << "unexpected output: " << output << "\n";
        return 14;
    }

    libssh2_channel_free(channel);
    libssh2_session_free(target);
    libssh2_channel_free(tunnel);
    libssh2_session_disconnect(jump, "poc complete");
    libssh2_session_free(jump);
    close(jump_fd);
    libssh2_exit();

    std::cout << "jump-custom-transport-poc-ok\n";
    return 0;
}
