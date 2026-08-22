package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.HostProfile

/**
 * A connection route: the final target plus an optional single-layer jump host.
 * The jump host itself must never be configured with its own jump (enforced by
 * [com.yang136.sshhelper.data.validateJumpRoute] at save time).
 */
data class SshRoute(
    val target: HostProfile,
    val jump: HostProfile? = null,
)

/** Credentials for each hop; [jump] is null when the route is direct. */
data class RouteCredentials(
    val target: Credential,
    val jump: Credential? = null,
)

/** Progress of a route connection, used both by the UI and the session state. */
enum class ConnectionStage {
    /** Not connecting, or the route is fully established. */
    READY,
    JUMP_AUTH,
    JUMP_HOST_KEY,
    TARGET_AUTH,
    TARGET_HOST_KEY,
}

/** Which hop a host-key or credential prompt currently refers to. */
enum class CredentialRole { JUMP, TARGET }

/** Which hop a host-key verification request refers to. */
enum class HostKeySubject { JUMP, TARGET }

internal fun Credential.copyCredential(): Credential = when (this) {
    is Credential.Password -> Credential.Password(value.copyOf())
    is Credential.PrivateKey -> Credential.PrivateKey(bytes.copyOf(), passphrase?.copyOf(), fileName)
}

internal fun RouteCredentials.copyDeep(): RouteCredentials =
    RouteCredentials(target.copyCredential(), jump?.copyCredential())
