package com.thelightphone.subway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The source read as text, for what cannot be seen in it. */
class SourcesTest {
    @Test
    fun noSourceFileHoldsASpaceOrAMarkThatCannotBeSeen() {
        // A no-break space typed between two quotes looks like an ordinary one, and the line it is on like a line
        // that does nothing: the next person tidies it away. Such characters are written as escapes, which can be read.
        val files = listOf("src/main/kotlin", "src/test/kotlin").flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension == "kt" } }
        assertTrue(files.size > 50, "${files.size} files")
        val unseen = files.flatMap { file ->
            file.readText().lines().mapIndexedNotNull { line, text ->
                val odd = text.filter { it != ' ' && (it.isWhitespace() || it.isISOControl() || it.category == CharCategory.FORMAT) }
                if (odd.isEmpty()) null else "${file.name}:${line + 1} holds ${odd.map { "U+%04X".format(it.code) }}"
            }
        }
        assertTrue(unseen.isEmpty(), unseen.joinToString("\n"))
    }
}
