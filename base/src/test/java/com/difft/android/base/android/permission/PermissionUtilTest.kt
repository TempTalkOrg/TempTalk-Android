package com.difft.android.base.android.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentResolver
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.android.permission.PermissionUtil.PermissionState
import com.difft.android.base.android.permission.PermissionUtil.launchMediaSelectionOrOpen
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue





@RunWith(AndroidJUnit4::class)
@Config(application = PermissionUtilTest.TestScopeApplication::class)
class PermissionUtilTest {

    class TestScopeApplication : ScopeApplication() {
        override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default

        override fun onCreate() {
            super.onCreate()
            ApplicationHelper.init(this)
        }
    }

    private val visualUserSelected = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    @Before
    fun setup() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext<Application>() as ScopeApplication)
    }

    private fun grantVisualUserSelected() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(visualUserSelected)
    }

    private fun grant(vararg permissions: String) {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(*permissions)
    }

    private fun robolectricActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).create().get()

    

    @Test
    @Config(sdk = [34])
    fun `picturePermissions on 34 requests visual user selected with images and video`() {
        assertContentEquals(
            arrayOf(
                visualUserSelected,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            ),
            PermissionUtil.picturePermissions
        )
    }

    @Test
    @Config(sdk = [33])
    fun `picturePermissions on 33 requests images and video only`() {
        assertContentEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            ),
            PermissionUtil.picturePermissions
        )
    }

    @Test
    @Config(sdk = [30])
    fun `picturePermissions on 30 requests read external storage only`() {
        assertContentEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            PermissionUtil.picturePermissions
        )
    }

    @Test
    @Config(sdk = [26])
    fun `picturePermissions on 26 requests read and write external storage`() {
        assertContentEquals(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ),
            PermissionUtil.picturePermissions
        )
    }

    

    @Test
    @Config(sdk = [34])
    fun `partial access on 34 is granted when visual user selected is granted`() {
        grantVisualUserSelected()
        val result = mapOf(
            visualUserSelected to true,
            Manifest.permission.READ_MEDIA_IMAGES to false,
            Manifest.permission.READ_MEDIA_VIDEO to false,
        )

        val state = PermissionUtil.getPermissionState(robolectricActivity(), result)

        assertEquals(PermissionState.Granted, state)
    }

    @Test
    @Config(sdk = [34])
    fun `repeated partial access on 34 never escalates to permanently denied`() {
        grantVisualUserSelected()
        val result = mapOf(
            visualUserSelected to true,
            Manifest.permission.READ_MEDIA_IMAGES to false,
            Manifest.permission.READ_MEDIA_VIDEO to false,
        )
        
        
        val activity = robolectricActivity()

        assertEquals(PermissionState.Granted, PermissionUtil.getPermissionState(activity, result))
        assertEquals(PermissionState.Granted, PermissionUtil.getPermissionState(activity, result))
    }

    @Test
    @Config(sdk = [34])
    fun `full grant on 34 is granted`() {
        grantVisualUserSelected()
        val result = mapOf(
            visualUserSelected to true,
            Manifest.permission.READ_MEDIA_IMAGES to true,
            Manifest.permission.READ_MEDIA_VIDEO to true,
        )

        val state = PermissionUtil.getPermissionState(null, result)

        assertEquals(PermissionState.Granted, state)
    }

    @Test
    @Config(sdk = [34])
    fun `granted images and video with denied visual on 34 is granted`() {
        
        
        grant(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        val result = mapOf(
            visualUserSelected to false,
            Manifest.permission.READ_MEDIA_IMAGES to true,
            Manifest.permission.READ_MEDIA_VIDEO to true,
        )

        val state = PermissionUtil.getPermissionState(robolectricActivity(), result)

        assertEquals(PermissionState.Granted, state)
    }

    @Test
    @Config(sdk = [34])
    fun `true denial on 34 stays denied without rationale info`() {
        val result = mapOf(
            visualUserSelected to false,
            Manifest.permission.READ_MEDIA_IMAGES to false,
            Manifest.permission.READ_MEDIA_VIDEO to false,
        )

        val state = PermissionUtil.getPermissionState(null, result)

        assertEquals(PermissionState.Denied, state)
    }

    @Test
    @Config(sdk = [34])
    fun `true denial on 34 escalates to permanently denied when rationale is false`() {
        val result = mapOf(
            visualUserSelected to false,
            Manifest.permission.READ_MEDIA_IMAGES to false,
            Manifest.permission.READ_MEDIA_VIDEO to false,
        )

        val state = PermissionUtil.getPermissionState(robolectricActivity(), result)

        assertEquals(PermissionState.PermanentlyDenied, state)
    }

    

    @Test
    @Config(sdk = [34])
    fun `audio denial on 34 is unaffected by granted visual user selected`() {
        grantVisualUserSelected()
        val result = mapOf(Manifest.permission.RECORD_AUDIO to false)

        val state = PermissionUtil.getPermissionState(null, result)

        assertEquals(PermissionState.Denied, state)
    }

    @Test
    @Config(sdk = [34])
    fun `camera grant on 34 stays granted`() {
        val result = mapOf(Manifest.permission.CAMERA to true)

        val state = PermissionUtil.getPermissionState(null, result)

        assertEquals(PermissionState.Granted, state)
    }

    

    @Test
    @Config(sdk = [33])
    fun `full media grant on 33 is granted`() {
        val result = mapOf(
            Manifest.permission.READ_MEDIA_IMAGES to true,
            Manifest.permission.READ_MEDIA_VIDEO to true,
        )

        val state = PermissionUtil.getPermissionState(null, result)

        assertEquals(PermissionState.Granted, state)
    }

    @Test
    @Config(sdk = [33])
    fun `media denial on 33 stays denied even if visual user selected string is granted`() {
        
        grantVisualUserSelected()
        val result = mapOf(
            Manifest.permission.READ_MEDIA_IMAGES to false,
            Manifest.permission.READ_MEDIA_VIDEO to false,
        )

        val state = PermissionUtil.getPermissionState(null, result)

        assertEquals(PermissionState.Denied, state)
    }

    @Test
    @Config(sdk = [30])
    fun `storage grant on 30 is granted`() {
        val result = mapOf(Manifest.permission.READ_EXTERNAL_STORAGE to true)

        val state = PermissionUtil.getPermissionState(null, result)

        assertEquals(PermissionState.Granted, state)
    }

    @Test
    @Config(sdk = [30])
    fun `storage denial on 30 stays denied`() {
        val result = mapOf(Manifest.permission.READ_EXTERNAL_STORAGE to false)

        val state = PermissionUtil.getPermissionState(null, result)

        assertEquals(PermissionState.Denied, state)
    }

    

    @Test
    @Config(sdk = [34])
    fun `T1 media access on 34 with images video visual granted is FULL`() {
        grant(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            visualUserSelected,
        )
        assertEquals(MediaAccessState.FULL, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [34])
    fun `T2 media access on 34 with only visual granted is PARTIAL`() {
        grant(visualUserSelected)
        assertEquals(MediaAccessState.PARTIAL, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [34])
    fun `T3 media access on 34 with nothing granted is NONE`() {
        assertEquals(MediaAccessState.NONE, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [34])
    fun `T4 media access on 34 with only images granted is FULL`() {
        grant(Manifest.permission.READ_MEDIA_IMAGES)
        assertEquals(MediaAccessState.FULL, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [33])
    fun `T5 media access on 33 with images video granted is FULL`() {
        grant(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        assertEquals(MediaAccessState.FULL, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [33])
    fun `T6 media access on 33 with only visual string granted is NONE`() {
        
        grant(visualUserSelected)
        assertEquals(MediaAccessState.NONE, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [30])
    fun `T7 media access on 30 with read external storage granted is FULL`() {
        grant(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertEquals(MediaAccessState.FULL, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [30])
    fun `T8 media access on 30 with nothing granted is NONE`() {
        assertEquals(MediaAccessState.NONE, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [26])
    fun `T9 media access on 26 with read and write external storage granted is FULL`() {
        grant(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        assertEquals(MediaAccessState.FULL, PermissionUtil.getMediaAccessState())
    }

    

    @Test
    @Config(sdk = [34])
    fun `T10 launchMediaSelectionOrOpen opens directly and does not request when usable`() {
        grant(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        val launcher = mockk<ActivityResultLauncher<Array<String>>>(relaxed = true)
        val permission = PermissionUtil.Permission(launcher)
        var usableCount = 0

        permission.launchMediaSelectionOrOpen { usableCount++ }

        assertEquals(1, usableCount)
        verify(exactly = 0) { launcher.launch(any<Array<String>>()) }
    }

    @Test
    @Config(sdk = [34])
    fun `T11 launchMediaSelectionOrOpen requests picture permissions and skips open when NONE`() {
        val launcher = mockk<ActivityResultLauncher<Array<String>>>(relaxed = true)
        val permission = PermissionUtil.Permission(launcher)
        var usableCount = 0
        val requested = slot<Array<String>>()

        permission.launchMediaSelectionOrOpen { usableCount++ }

        assertEquals(0, usableCount)
        verify(exactly = 1) { launcher.launch(capture(requested)) }
        assertContentEquals(PermissionUtil.picturePermissions, requested.captured)
    }

    @Test
    @Config(sdk = [34])
    fun `T12 launchMediaSelectionOrOpen opens directly under partial access`() {
        grant(visualUserSelected)
        val launcher = mockk<ActivityResultLauncher<Array<String>>>(relaxed = true)
        val permission = PermissionUtil.Permission(launcher)
        var usableCount = 0

        permission.launchMediaSelectionOrOpen { usableCount++ }

        assertEquals(1, usableCount)
        verify(exactly = 0) { launcher.launch(any<Array<String>>()) }
    }

    
    
    
    

    private val mediaUri: Uri = Uri.parse("content://media/external/images/media/1")

    @Test
    @Config(sdk = [29])
    fun `T12 getMediaAccessState on 29 with read external storage granted is FULL`() {
        grant(Manifest.permission.READ_EXTERNAL_STORAGE)

        assertEquals(MediaAccessState.FULL, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [29])
    fun `T12 getMediaAccessState on 29 with nothing granted is NONE`() {
        assertEquals(MediaAccessState.NONE, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [29])
    fun `T12 getMediaAccessState on 29 ignores granted READ_MEDIA permissions`() {
        
        
        
        grant(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            visualUserSelected,
        )

        assertEquals(MediaAccessState.NONE, PermissionUtil.getMediaAccessState())

        grant(Manifest.permission.READ_EXTERNAL_STORAGE)

        assertEquals(MediaAccessState.FULL, PermissionUtil.getMediaAccessState())
    }

    @Test
    @Config(sdk = [29])
    fun `T90 classifyReadDenial on granted full access is GRANTED_BUT_UNREADABLE`() {
        grant(Manifest.permission.READ_EXTERNAL_STORAGE)
        val cause = IOException("boom")

        val denial = PermissionUtil.classifyReadDenial(mediaUri, cause)

        assertEquals(MediaReadDenialKind.GRANTED_BUT_UNREADABLE, denial.kind)
        assertSame(cause, denial.cause)
    }

    @Test
    @Config(sdk = [29])
    fun `T91 classifyReadDenial without any grant is PERMISSION_MISSING`() {
        val denial = PermissionUtil.classifyReadDenial(mediaUri, IOException("boom"))

        assertEquals(MediaReadDenialKind.PERMISSION_MISSING, denial.kind)
    }

    @Test
    @Config(sdk = [34])
    fun `T92 classifyReadDenial under partial selection is PARTIAL_SELECTION`() {
        grantVisualUserSelected()

        val denial = PermissionUtil.classifyReadDenial(mediaUri, IOException("boom"))

        assertEquals(MediaReadDenialKind.PARTIAL_SELECTION, denial.kind)
    }

    @Test
    @Config(sdk = [29])
    fun `T93 classifyReadDenial on a sandbox file uri is NOT_MEDIA_SCOPED`() {
        
        grant(Manifest.permission.READ_EXTERNAL_STORAGE)
        val sandboxUri = Uri.parse("file:///data/user/0/pkg/files/draft_blobs/x.jpg")
        val denial = PermissionUtil.classifyReadDenial(sandboxUri, IOException("boom"))

        assertEquals(MediaReadDenialKind.NOT_MEDIA_SCOPED, denial.kind)
    }

    @Test
    @Config(sdk = [29])
    fun `T94 classifyReadDenial on a cross profile media authority takes the media branch`() {
        grant(Manifest.permission.READ_EXTERNAL_STORAGE)
        val crossProfileUri = Uri.parse("content://0@media/external/images/media/1")
        val denial = PermissionUtil.classifyReadDenial(crossProfileUri, IOException("boom"))

        assertEquals(MediaReadDenialKind.GRANTED_BUT_UNREADABLE, denial.kind)
    }

    @Test
    fun `T95 classifyReadDenial performs no IO and writes no log`() {
        grant(Manifest.permission.READ_EXTERNAL_STORAGE)
        
        
        val noResolver = object : ContextWrapper(
            ApplicationProvider.getApplicationContext<Application>()
        ) {
            override fun getContentResolver(): ContentResolver =
                throw AssertionError("classifyReadDenial must not touch the ContentResolver")
        }
        val tree = RecordingTree()
        L.plant(tree)
        try {
            tree.barrier("PermissionUtilTest-warmup")
            tree.clear()

            PermissionUtil.classifyReadDenial(mediaUri, IOException("boom"), noResolver)
            PermissionUtil.classifyReadDenial(
                Uri.parse("file:///data/user/0/pkg/files/draft_blobs/x.jpg"),
                IOException("boom"),
                noResolver
            )

            tree.barrier("PermissionUtilTest-probe")
            val recorded = tree.snapshot()
            assertEquals(
                1,
                recorded.size,
                "classifyReadDenial must not log; recorded=$recorded"
            )
        } finally {
            L.uproot(tree)
        }
    }

    






    private class RecordingTree : Timber.Tree() {
        private val recorded = CopyOnWriteArrayList<String>()

        @Volatile
        private var marker: String? = null

        @Volatile
        private var latch: CountDownLatch? = null

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            recorded += message
            marker?.let { if (message.contains(it)) latch?.countDown() }
        }

        fun snapshot(): List<String> = recorded.toList()

        fun clear() = recorded.clear()

        fun barrier(sentinel: String) {
            val gate = CountDownLatch(1)
            marker = sentinel
            latch = gate
            L.i { sentinel }
            assertTrue(
                gate.await(5, TimeUnit.SECONDS),
                "log barrier '$sentinel' never arrived; recorded=${snapshot()}"
            )
            marker = null
            latch = null
        }
    }
}
