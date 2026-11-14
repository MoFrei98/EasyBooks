package com.example.easybooks.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Testfälle aus `shared/fixtures`, die auch die iOS-App prüft. So erkennen beide Apps
 * Bücher und Suchanfragen gleich.
 */
class SharedFixturesTest {

    private fun rows(name: String): List<List<String>> {
        val file = listOf(File("../shared/fixtures", name), File("shared/fixtures", name)).first { it.isFile }
        return file.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
    }

    private fun value(s: String): String? = s.takeIf { it != "-" }

    @Test
    fun `file names`() {
        val rows = rows("metadata-filenames.tsv")
        check(rows.isNotEmpty())
        for (r in rows) {
            val expected = BookMetadata(value(r[1]), value(r[2]), value(r[3]), value(r[4])?.toInt())
            assertEquals(r[0], expected, BookMetadataParser.fromFileName(r[0]))
        }
    }

    @Test
    fun authors() {
        val rows = rows("metadata-authors.tsv")
        check(rows.isNotEmpty())
        for (r in rows) {
            assertEquals(r[0], value(r[1]) to value(r[2]), BookMetadataParser.splitAuthor(r[0]))
        }
    }

    @Test
    fun search() {
        val rows = rows("search.tsv")
        check(rows.isNotEmpty())
        for (r in rows) {
            assertEquals(r.joinToString(" | "), r[1].toBoolean(), SearchText.matches(r[0], *r.drop(2).toTypedArray()))
        }
    }
}
