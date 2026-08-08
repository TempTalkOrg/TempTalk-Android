package com.difft.android.base.utils

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Boundary behaviour of the app-private storage predicate (T76-T80).
 *
 * Pure JVM: the predicate is structural, so no Android framework is needed once the roots are
 * passed explicitly. The root is canonicalized in the test as well — on some hosts the temp dir
 * itself sits behind a symlink (`/var` -> `/private/var`), which is precisely the false-negative
 * class the production code canonicalizes to avoid.
 */
class AppPrivateStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var tempRoot: String
    private lateinit var privateRoot: String
    private lateinit var roots: List<String>

    @Before
    fun setUp() {
        tempRoot = tempFolder.root.canonicalPath
        privateRoot = tempFolder.newFolder("priv").canonicalPath
        roots = listOf(privateRoot)
    }

    @Test
    fun `T76 path inside a private root is app private`() {
        val path = File(privateRoot, "a/b.jpg").path

        assertTrue(AppPrivateStorage.isUnderAnyRoot(path, roots))
    }

    @Test
    fun `T76 the root itself is app private`() {
        assertTrue(AppPrivateStorage.isUnderAnyRoot(privateRoot, roots))
    }

    @Test
    fun `T77 sibling directory sharing the root name prefix is not app private`() {
        // A bare startsWith would accept this; the root + File.separator boundary rejects it.
        val path = File(tempRoot, "privEVIL/x.jpg").path

        assertFalse(AppPrivateStorage.isUnderAnyRoot(path, roots))
    }

    @Test
    fun `T78 path traversing out of a private root is not app private`() {
        val path = File(privateRoot, "../shared/DCIM/a.jpg").path

        assertFalse(AppPrivateStorage.isUnderAnyRoot(path, roots))
    }

    @Test
    fun `T79 nonexistent path inside a private root is still app private`() {
        val file = File(privateRoot, "never-created.jpg")
        // Reverse regression pin: the predicate must not probe existence at all.
        assertFalse(file.exists())

        assertTrue(AppPrivateStorage.isUnderAnyRoot(file.path, roots))
    }

    @Test
    fun `T80 blank paths are rejected without throwing`() {
        assertFalse(AppPrivateStorage.isUnderAnyRoot("", roots))
        assertFalse(AppPrivateStorage.isUnderAnyRoot("   ", roots))
    }

    @Test
    fun `T80 empty root list is rejected without throwing`() {
        assertFalse(AppPrivateStorage.isUnderAnyRoot(File(privateRoot, "a.jpg").path, emptyList()))
    }
}

/**
 * Root discovery against the real framework (T81).
 *
 * Separate class rather than separate file: `canonicalRoots` reads `dataDir` and
 * `getExternalFilesDirs` off a real Context, so it needs the Robolectric runner while T76-T80 must
 * stay pure JVM, and `@RunWith` is per class.
 */
@RunWith(AndroidJUnit4::class)
class AppPrivateStorageRootsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `T81 canonical roots contain the data dir and the external files parent`() {
        val roots = AppPrivateStorage.canonicalRoots(context)

        assertTrue(roots.isNotEmpty(), "roots must not be empty")
        assertTrue(
            roots.contains(context.dataDir.canonicalPath),
            "expected dataDir among roots, got $roots"
        )
        val externalParent = context.getExternalFilesDir(null)!!.parentFile!!.canonicalPath
        assertTrue(roots.contains(externalParent), "expected $externalParent among roots, got $roots")
    }

    @Test
    fun `T81 null external volumes are filtered out`() {
        val real = context.getExternalFilesDir(null)!!
        // Framework assumption: getExternalFilesDirs may report null entries for unmounted volumes.
        val withNullVolume = object : ContextWrapper(context) {
            override fun getExternalFilesDirs(type: String?): Array<File> {
                @Suppress("UNCHECKED_CAST")
                return arrayOf<File?>(null, real) as Array<File>
            }
        }

        val roots = AppPrivateStorage.canonicalRoots(withNullVolume)

        assertTrue(roots.contains(real.parentFile!!.canonicalPath), "got $roots")
        assertEquals(roots.distinct(), roots)
    }
}
