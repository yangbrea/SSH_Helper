package com.yang136.sshhelper.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalFrontendFactoryTest {
    private val dispatcher = StandardTestDispatcher()
    private var frontend: TerminalFrontend? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        frontend?.close()
        frontend = null
        Dispatchers.resetMain()
    }

    @Test
    fun factoryAlwaysCreatesGhosttyFrontend() {
        frontend = createTerminalFrontend()

        assertTrue(frontend is GhosttyTerminalFrontend)
    }
}
