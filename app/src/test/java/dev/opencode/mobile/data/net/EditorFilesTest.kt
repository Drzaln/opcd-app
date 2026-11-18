package dev.opencode.mobile.data.net

import dev.opencode.mobile.data.net.EditorFiles.PathCheck
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class EditorFilesTest {
    private val root = "/Users/me/dev/app"

    private fun ok(path: String) = (EditorFiles.resolve(root, path) as? PathCheck.Ok)?.relative
    private fun rejected(path: String, project: String = root) = EditorFiles.resolve(project, path) is PathCheck.Rejected

    @Test fun relativePathsAreNormalized() {
        assertEquals("src/App.tsx", ok("src/App.tsx"))
        assertEquals("src/App.tsx", ok("./src//App.tsx"))
        assertEquals("a/b.kt", ok("a/./b.kt"))
        assertEquals("it's a file.txt", ok("it's a file.txt"))
    }

    @Test fun absolutePathsInsideProjectBecomeRelative() {
        assertEquals("src/App.tsx", ok("/Users/me/dev/app/src/App.tsx"))
        assertEquals("x.kt", (EditorFiles.resolve("/Users/me/dev/app/", "/Users/me/dev/app/x.kt") as PathCheck.Ok).relative)
    }

    @Test fun dotDotIsRejectedEvenWhenItStaysInside() {
        assertTrue(rejected("../etc/passwd"))
        assertTrue(rejected("src/../../x"))
        assertTrue(rejected("src/../App.tsx"))
        assertTrue(rejected("/Users/me/dev/app/../secret"))
    }

    @Test fun absolutePathsOutsideProjectAreRejected() {
        assertTrue(rejected("/etc/passwd"))
        assertTrue(rejected("/Users/me/dev/application/x.kt")) // prefix match without a separator
        assertTrue(rejected("/Users/me/dev/app"))
        assertTrue(rejected("/Users/me/dev/app/"))
    }

    @Test fun controlCharactersAndOddInputsAreRejected() {
        assertTrue(rejected("a\u0000b"))
        assertTrue(rejected("a\nb"))
        assertTrue(rejected("a\rb"))
        assertTrue(rejected(""))
        assertTrue(rejected("   "))
        assertTrue(rejected("."))
        assertTrue(rejected("~/x"))
        assertTrue(rejected("x", project = "relative/project"))
        assertTrue(rejected("x", project = "/proj\n/x"))
    }

    @Test fun base64LinesNeverExceedWidthAndRoundTrip() {
        for (size in listOf(0, 1, 2, 3, 56, 57, 58, 1000, 65_537)) {
            val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
            val lines = EditorFiles.base64Lines(bytes)
            assertTrue("size $size", lines.all { it.length in 1..76 })
            assertTrue("only the last line may be short", lines.dropLast(1).all { it.length == 76 })
            assertArrayEquals(bytes, Base64.getDecoder().decode(lines.joinToString("")))
        }
    }

    @Test fun base64LinesHonourCustomWidth() {
        val lines = EditorFiles.base64Lines(ByteArray(300) { it.toByte() }, width = 16)
        assertTrue(lines.all { it.length <= 16 })
        assertEquals(25, lines.size) // 300 bytes -> 400 chars -> 25 lines of 16
    }

    @Test fun shellQuoteProducesOneWord() {
        assertEquals("'plain'", EditorFiles.shellQuote("plain"))
        assertEquals("'it'\\''s'", EditorFiles.shellQuote("it's"))
        assertEquals("'\$(rm -rf /)'", EditorFiles.shellQuote("\$(rm -rf /)"))
    }

    @Test fun binaryAndInvalidUtf8AreDetected() {
        assertNull(EditorFiles.decodeText(byteArrayOf(0x41, 0x00, 0x42)))
        assertNull(EditorFiles.decodeText(byteArrayOf(0xC3.toByte(), 0x28)))
        assertEquals("ünï ✓", EditorFiles.decodeText("ünï ✓".toByteArray()))
    }

    @Test fun tempFileSitsBesideTarget() {
        assertEquals("src/.App.tsx.oc-abc.tmp", EditorFiles.tempPathFor("src/App.tsx", "abc"))
        assertEquals(".a.txt.oc-1.tmp", EditorFiles.tempPathFor("a.txt", "1"))
    }

    @Test fun extractBase64IgnoresTtyNoise() {
        val payload = "hello\nworld".toByteArray()
        val b64 = Base64.getEncoder().encodeToString(payload)
        val output = "junk\r\n__OC_BEGIN__\r\n${b64.take(8)}\r\n${b64.drop(8)}\r\n\r\n__OC_END__\r\n__OC_EXIT__0\r\n"
        assertArrayEquals(payload, EditorFiles.extractBase64(output, "__OC_BEGIN__", "__OC_END__"))
    }

    @Test fun sha256MatchesKnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", EditorFiles.sha256(ByteArray(0)))
    }

    @Test fun gitParseKeepsLeadingStatusSpace() {
        val runner = GitRunner(ServerConfig(), OpenCodeApi(ServerConfig(), stubV1(), stubV2(), okhttp3.OkHttpClient()))
        val result = runner.parse("\r\n M src/App.tsx\r\n?? b.ts\r\n__OC_EXIT__0\r\n")
        assertEquals(0, result.exitCode)
        assertEquals(listOf("src/App.tsx", "b.ts"), GitRunner.parseStatus(result.output).map { it.path })
    }

    private fun retrofit() = retrofit2.Retrofit.Builder().baseUrl("http://localhost/").build()
    private fun stubV1() = retrofit().create(OpenCodeV1Api::class.java)
    private fun stubV2() = retrofit().create(OpenCodeV2Api::class.java)

    @Test fun gitStatusParsing() {
        val changes = GitRunner.parseStatus(" M src/App.tsx\n?? new file.ts\nR  old.kt -> pkg/New.kt\nA  \"quo\\\"te.txt\"\n")
        assertEquals(listOf("src/App.tsx", "new file.ts", "pkg/New.kt", "quo\"te.txt"), changes.map { it.path })
        assertEquals(listOf("M", "U", "R", "A"), changes.map { it.label })
    }
}
