#!/usr/bin/env python3
"""AsyncSSH jump-host fixture used by native jump POC/E2E tests.

Starts two loopback SSH servers:
  * the target server authenticates password `target_password`;
  * the jump server authenticates password `jump_password` and accepts
    direct-tcpip forwarding requests to any local destination.

Prints `<target_port> <jump_port>` on stdout.
"""
import asyncio
import asyncssh
import sys


class ExecSession(asyncssh.SSHServerSession):
    def connection_made(self, chan):
        self._chan = chan
        self._shell = False

    def shell_requested(self):
        self._shell = True
        return True

    def exec_requested(self, command):
        self._command = command
        return True

    def data_received(self, data, datatype):
        if self._shell:
            self._chan.write(data)

    def session_started(self):
        if self._shell:
            return
        self._chan.write("native-jump-poc-exec-ok")
        self._chan.exit(0)


class PasswordServer(asyncssh.SSHServer):
    def __init__(self, password):
        self._password = password

    def begin_auth(self, username):
        return True

    def password_auth_supported(self):
        return True

    def validate_password(self, username, password):
        return username == "test" and password == self._password

    def session_requested(self):
        return ExecSession()


class JumpServer(PasswordServer):
    def connection_requested(self, dest_host, dest_port, orig_host, orig_port):
        # Standard direct-tcpip forwarding is sufficient for the SSH-over-jump POC.
        return True


async def main():
    target_password = sys.argv[1] if len(sys.argv) > 1 else "secret-target"
    jump_password = sys.argv[2] if len(sys.argv) > 2 else "secret-jump"
    target_key = asyncssh.generate_private_key("ssh-ed25519")
    jump_key = asyncssh.generate_private_key("ssh-ed25519")
    target = await asyncssh.create_server(
        lambda: PasswordServer(target_password),
        "127.0.0.1", 0,
        server_host_keys=[target_key],
    )
    jump = await asyncssh.create_server(
        lambda: JumpServer(jump_password),
        "127.0.0.1", 0,
        server_host_keys=[jump_key],
    )
    target_port = target.sockets[0].getsockname()[1]
    jump_port = jump.sockets[0].getsockname()[1]
    print(f"{target_port} {jump_port}", flush=True)
    await asyncio.Event().wait()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
