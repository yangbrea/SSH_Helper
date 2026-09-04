package com.yang136.sshhelper.ssh.contract

import com.yang136.sshhelper.data.KnownHostDao
import com.yang136.sshhelper.ssh.JschSshSession
import com.yang136.sshhelper.ssh.SshSession
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** JSch backend runs the shared backend contract while the native backend is under construction. */
@RunWith(JUnit4::class)
class JschBackendContractTest : SshBackendContractTest() {
    override fun createSession(knownHostDao: KnownHostDao): SshSession =
        JschSshSession(knownHostDao)
}
