package com.difft.android.test.rules

import com.difft.android.base.utils.GlobalHiltEntryPoint
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.rules.TestWatcher
import org.junit.runner.Description














class GlobalStaticMockRule(
    var testUserId: String = "test-user-001"
) : TestWatcher() {

    lateinit var mockGlobalServices: GlobalHiltEntryPoint
        private set

    override fun starting(description: Description) {
        mockGlobalServices = mockk(relaxed = true)

        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobalServices
        every { mockGlobalServices.myId } returns testUserId
    }

    override fun finished(description: Description) {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
    }
}
