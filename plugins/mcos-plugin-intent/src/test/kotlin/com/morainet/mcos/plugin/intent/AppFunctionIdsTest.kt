package com.morainet.mcos.plugin.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * §12.5 App Function id encoding (02-command-protocol.md).
 */
class AppFunctionIdsTest {

    @Test
    fun `AF1-encodes the spec example`() {
        assertEquals(
            "sys.appfn.com_example_notes.createNote",
            AppFunctionIds.encode("com.example.notes", "createNote"),
        )
    }

    @Test
    fun `AF2-decode inverts encode for underscore-free packages`() {
        val id = AppFunctionIds.encode("com.example.notes", "createNote")
        assertEquals(
            AppFunctionIds.Decoded("com.example.notes", "createNote"),
            AppFunctionIds.decode(id),
        )
    }

    @Test
    fun `AF3-decode rejects ids that are not sys-appfn shaped`() {
        assertNull(AppFunctionIds.decode("note.create"), "hand-authored ids are not app-function ids")
        assertNull(AppFunctionIds.decode("camera.capture"))
        assertNull(AppFunctionIds.decode("sys.appfn.com_example_notes"), "function segment missing")
        assertNull(AppFunctionIds.decode("sys.appfn.com_example_notes.createNote.extra"), "extra segment")
        assertNull(AppFunctionIds.decode("app.appfn.com_example_notes.createNote"), "wrong prefix")
        assertNull(AppFunctionIds.decode("sys.appfn..createNote"), "blank package")
        assertNull(AppFunctionIds.decode("sys.appfn.com_example_notes."), "blank function")
    }

    @Test
    fun `AF4-encode fails closed on packages whose ids would be ambiguous`() {
        // §12.5 says the reverse lookup is unambiguous "because step 1 is a
        // 1:1 char swap" — that only holds for packages with no `_`. A package
        // that already contains `_` and one that had a `.` at that position
        // would encode to the SAME id, so the encoded form cannot name which
        // app to run with the caller's arguments. The ambiguity lives in the
        // encoding, so encode refuses; such an app is addressed through
        // `appfn.invoke(package, function, args)` instead.
        assertFailsWith<IllegalArgumentException> { AppFunctionIds.encode("com.my_app", "f") }
        // The dotted package still encodes to exactly the id the underscore
        // package would have produced — which is why the refusal is necessary.
        assertEquals("sys.appfn.com_my_app.f", AppFunctionIds.encode("com.my.app", "f"))
        assertEquals(
            AppFunctionIds.Decoded("com.my.app", "f"),
            AppFunctionIds.decode("sys.appfn.com_my_app.f"),
        )
    }

    @Test
    fun `AF5-encode rejects unusable targets`() {
        assertFailsWith<IllegalArgumentException> { AppFunctionIds.encode("", "f") }
        assertFailsWith<IllegalArgumentException> { AppFunctionIds.encode("   ", "f") }
        assertFailsWith<IllegalArgumentException> { AppFunctionIds.encode("com.example", "") }
        assertFailsWith<IllegalArgumentException> { AppFunctionIds.encode("com.example", "a.b") }
    }
}
