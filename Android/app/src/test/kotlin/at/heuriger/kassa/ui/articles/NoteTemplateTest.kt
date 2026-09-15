package at.heuriger.kassa.ui.articles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Vorlagen-Chips im Notizdialog. Reine Textlogik, deshalb ohne Robolectric.
 *
 * Der Fall, der sonst durchrutscht: frei getippter Text und angetippte Vorlage
 * stehen in derselben Notiz, und ein zweiter Tipp darf nur die Vorlage
 * herausnehmen.
 */
class NoteTemplateTest {

    @Test
    fun `erster Tipp haengt kommagetrennt an, zweiter nimmt wieder weg`() {
        assertEquals("ohne Senf", toggleNoteTemplate("", "ohne Senf"))
        assertEquals(
            "ohne Senf, scharf",
            toggleNoteTemplate("ohne Senf", "scharf"),
        )
        assertEquals("ohne Senf", toggleNoteTemplate("ohne Senf, scharf", "scharf"))
        assertEquals("", toggleNoteTemplate("scharf", "scharf"))
    }

    @Test
    fun `frei getippter Text bleibt beim Antippen stehen`() {
        val note = toggleNoteTemplate("Brot statt Semmel", "ohne Senf")

        assertEquals("Brot statt Semmel, ohne Senf", note)
        assertEquals("Brot statt Semmel", toggleNoteTemplate(note, "ohne Senf"))
    }

    /** Leerraum und Gross-/Kleinschreibung kommen vom Tippen, nicht vom Chip. */
    @Test
    fun `Erkennung ignoriert Leerraum und Gross-Kleinschreibung`() {
        assertTrue(noteHasTemplate("  Ohne Senf , scharf", "ohne Senf"))
        assertFalse(noteHasTemplate("ohne Senfgurke", "ohne Senf"))
        assertEquals("scharf", toggleNoteTemplate("Ohne Senf, scharf", "ohne senf"))
    }

    @Test
    fun `die Notiz bleibt auf 120 Zeichen begrenzt`() {
        var note = ""
        repeat(20) { note = toggleNoteTemplate(note, "extra Ketchup $it") }

        assertEquals(120, note.length)
    }
}
