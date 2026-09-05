#!/usr/bin/env python3
"""AsyncSSH test server used by ssh-native E2E host tests.

Supports password auth (default), public-key auth, and an optional
keyboard-interactive-only mode passed as "kbdint". It handles a single exec
command that prints "native-exec-ok" and exits 0. Prints the listening port on
stdout.
"""
import asyncio
import asyncssh
import sys


class ExecSession(asyncssh.SSHServerSession):
    def connection_made(self, chan):
        self._chan = chan
        self._command = ""
        self._shell = False

    def shell_requested(self):
        self._shell = True
        return True

    def exec_requested(self, command):
        self._command = command
        return True

    def data_received(self, data, datatype):
        # Minimal interactive shell used by native Shell/PTY tests: echo input
        # back so the client can verify the channel is alive and full-duplex.
        if self._shell:
            self._chan.write(data)

    def session_started(self):
        if self._shell:
            return
        if self._command == "stderr-test":
            self._chan.write_stderr("native-stderr-ok")
        elif self._command.startswith("big-output:"):
            try:
                size = int(self._command.split(":", 1)[1])
            except ValueError:
                size = 0
            self._chan.write("x" * max(0, size))
        else:
            self._chan.write("native-exec-ok")
        self._chan.exit(0)


class Server(asyncssh.SSHServer):
    def __init__(self, auth_mode="password"):
        self._auth_mode = auth_mode

    def begin_auth(self, username):
        return True

    def password_auth_supported(self):
        return self._auth_mode == "password"

    def kbdint_auth_supported(self):
        return self._auth_mode == "kbdint"

    def validate_password(self, username, password):
        return username == "test" and password == "secret"

    def get_kbdint_challenge(self, username, lang, submethods):
        if self._auth_mode == "kbdint":
            return ("", "Password authentication", "", [("Password: ", False)])
        return False

    def validate_kbdint_response(self, username, responses):
        return (
            self._auth_mode == "kbdint"
            and username == "test"
            and responses == ["secret"]
        )

    def public_key_auth_supported(self):
        return True

    def validate_public_key(self, username, key):
        return username == "test"

    def session_requested(self):
        return ExecSession()


async def main():
    auth_mode = sys.argv[1] if len(sys.argv) > 1 else "password"
    if auth_mode not in ("password", "kbdint"):
        raise SystemExit("unknown auth mode: " + auth_mode)
    key = asyncssh.generate_private_key("ssh-ed25519")
    server = await asyncssh.create_server(
        lambda: Server(auth_mode),
        "127.0.0.1",
        0,
        server_host_keys=[key],
    )
    port = server.sockets[0].getsockname()[1]
    print(port, flush=True)
    await asyncio.Event().wait()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
