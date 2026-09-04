#include "ssh_socket.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>

namespace sshnative {

namespace {

void setNonBlocking(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) {
        throw std::runtime_error("failed to set socket non-blocking");
    }
}

std::string socketError(const std::string& prefix, int error) {
    std::string message = prefix;
    message += ": ";
    message += std::strerror(error);
    return message;
}

} // namespace

int connectTcp(
    const std::string& host,
    uint16_t port,
    std::chrono::milliseconds timeout) {
    const std::string port_string = std::to_string(port);

    struct addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    struct addrinfo* addresses = nullptr;
    const int gai_result = getaddrinfo(host.c_str(), port_string.c_str(), &hints, &addresses);
    if (gai_result != 0) {
        throw std::runtime_error(std::string("DNS resolution failed: ") + gai_strerror(gai_result));
    }

    int last_error = ECONNREFUSED;
    int connected_fd = -1;
    auto deadline = std::chrono::steady_clock::now() + timeout;

    for (struct addrinfo* address = addresses; address != nullptr; address = address->ai_next) {
        const int fd = socket(address->ai_family, address->ai_socktype | SOCK_CLOEXEC, address->ai_protocol);
        if (fd < 0) {
            last_error = errno;
            continue;
        }

        try {
            setNonBlocking(fd);
            const int result = connect(fd, address->ai_addr, address->ai_addrlen);
            if (result == 0) {
                connected_fd = fd;
                break;
            }
            if (errno != EINPROGRESS) {
                last_error = errno;
                close(fd);
                continue;
            }

            const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now());
            if (remaining.count() <= 0) {
                last_error = ETIMEDOUT;
                close(fd);
                continue;
            }

            struct pollfd pfd {};
            pfd.fd = fd;
            pfd.events = POLLOUT;
            const int poll_result = poll(&pfd, 1, static_cast<int>(remaining.count()));
            if (poll_result <= 0) {
                last_error = poll_result == 0 ? ETIMEDOUT : errno;
                close(fd);
                continue;
            }

            int socket_error = 0;
            socklen_t socket_error_len = sizeof(socket_error);
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socket_error, &socket_error_len) != 0) {
                last_error = errno;
                close(fd);
                continue;
            }
            if (socket_error != 0) {
                last_error = socket_error;
                close(fd);
                continue;
            }

            connected_fd = fd;
            break;
        } catch (...) {
            close(fd);
            throw;
        }
    }

    freeaddrinfo(addresses);
    if (connected_fd < 0) {
        if (last_error == ETIMEDOUT) {
            throw std::runtime_error("TCP connect timed out");
        }
        throw std::runtime_error(socketError("TCP connect failed", last_error));
    }
    return connected_fd;
}

void closeFd(int fd) {
    if (fd >= 0) {
        close(fd);
    }
}

} // namespace sshnative
