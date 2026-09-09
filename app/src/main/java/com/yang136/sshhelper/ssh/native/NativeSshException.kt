package com.yang136.sshhelper.ssh.native

/**
 * Structured error thrown when a native runtime SSH operation fails.
 *
 * The domain/code are stable identifiers used for user-facing mapping; raw
 * libssh2 numeric codes are not shown to the UI.
 */
class NativeSshException(
    val domain: String,
    val code: String,
    message: String,
    val libssh2Code: Int,
    val systemErrno: Int,
) : IllegalStateException(message)
