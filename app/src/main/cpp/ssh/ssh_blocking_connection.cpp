#include "ssh_blocking_connection.h"

#include <chrono>
#include <stdexcept>

#include "ssh_hostkey.h"
#include "ssh_socket.h"

namespace sshnative {

namespace {

constexpr std::chrono::seconds kConnectTimeout(10);

} // namespace

std::shared_ptr<BlockingSshConnection> BlockingSshConnection::openDirect(
    const std::string& host,
    uint16_t port) {
    auto connection = std::shared_ptr<BlockingSshConnection>(
        new BlockingSshConnection());
    connection->fd_ = connectTcp(host, port, kConnectTimeout);
    try {
        connection->session_.setBlocking(true);
        connection->session_.handshake(connection->fd_);
    } catch (...) {
        connection->close();
        throw;
    }
    return connection;
}

BlockingSshConnection::~BlockingSshConnection() {
    close();
}

BlockingSshHostKey BlockingSshConnection::hostKeyDetails() {
    int type = 0;
    const std::vector<uint8_t> blob = session_.hostKey(&type);
    BlockingSshHostKey details;
    details.type = hostKeyTypeName(type);
    details.fingerprint = hostKeySha256Fingerprint(blob);
    details.key_base64 = hostKeyBase64(blob);
    return details;
}

int BlockingSshConnection::execPassword(
    const std::string& username,
    const std::string& password,
    const std::string& command,
    std::string& output) {
    if (!session_.passwordAuth(username, password)) {
        throw std::runtime_error("SSH authentication failed");
    }
    return session_.execCommand(command, output);
}

int BlockingSshConnection::execPublicKey(
    const std::string& username,
    const std::string& private_key,
    const std::string& passphrase,
    const std::string& command,
    std::string& output) {
    if (!session_.publicKeyAuth(username, private_key, passphrase)) {
        throw std::runtime_error("SSH public key authentication failed");
    }
    return session_.execCommand(command, output);
}

void BlockingSshConnection::close() {
    if (fd_ != -1) {
        closeFd(fd_);
        fd_ = -1;
    }
}

} // namespace sshnative
