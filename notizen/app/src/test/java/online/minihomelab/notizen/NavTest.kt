package online.minihomelab.notizen

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTest {
    private val paths = listOf(
        "Schule/Physik/Elektrisches Feld.md", "Schule/Physik/Magnetismus.md", "Schule/Physik/Mechanik Grundlagen.md",
        "Schule/Mathe/Ableitung.md", "Schule/Chemie/Elektrolyse.md", "Projekte/Notizen-App.md",
        "Projekte/Basteln/Elektronik.md", "Alltag/Einkauf.md", "Eigene Notiz.md",
    )
    private val mt = mapOf("Schule/Physik/Elektrisches Feld.md" to 9L, "Schule/Chemie/Elektrolyse.md" to 5L, "Projekte/Basteln/Elektronik.md" to 7L)
    private val mtime = { p: String -> mt[p] ?: 1L }
    private val text = { p: String -> if (p == "Schule/Mathe/Ableitung.md") "hier geht es um das elektrische Potential" else null }

    @Test fun folderCountsIncludeSubfolders() {
        val c = Nav.folderCounts(paths)
        assertEquals(5, c["Schule"]); assertEquals(3, c["Schule/Physik"]); assertEquals(2, c["Projekte"])
        assertNull(c["Eigene Notiz"])
    }

    @Test fun peers() {
        assertEquals(listOf("Schule", "Projekte", "Alltag"), Nav.folderPeers(paths, "Schule").map { it.name })
        val sub = Nav.folderPeers(paths, "Schule/Physik")
        assertEquals(listOf("Chemie", "Mathe", "Physik"), sub.map { it.name })
        assertEquals(3, sub.first { it.current }.count)
    }

    @Test fun pager() {
        val p = Nav.pager(paths, "Schule/Physik/Magnetismus.md")
        assertEquals(1, p.index); assertEquals(3, p.total)
        assertEquals("Schule/Physik/Elektrisches Feld.md", p.prev); assertEquals("Schule/Physik/Mechanik Grundlagen.md", p.next)
        assertNull(Nav.pager(paths, "Schule/Physik/Elektrisches Feld.md").prev)
        assertEquals("", Nav.pager(paths, "Eigene Notiz.md").folder)
    }

    @Test fun crumbs() {
        assertEquals(listOf("Schule" to "Schule", "Physik" to "Schule/Physik"), Nav.crumbs("Schule/Physik/Magnetismus.md"))
        assertTrue(Nav.crumbs("Eigene Notiz.md").isEmpty())
    }

    @Test fun recentsAndPins() {
        assertEquals(listOf("a.md", "b.md"), Nav.pushRecent(Nav.pushRecent(Nav.pushRecent(emptyList(), "a.md"), "b.md"), "a.md"))
        assertEquals(3, Nav.pushRecent(listOf("1", "2", "3"), "4", 3).size)
        val (pinned, list) = Nav.togglePin(emptyList(), "a.md")
        assertTrue(pinned); assertEquals(listOf("a.md"), list)
        assertEquals(false to emptyList<String>(), Nav.togglePin(list, "a.md"))
    }

    @Test fun searchOrdersTitlePathText() {
        val r = Nav.search(paths, mtime, text, "elek")
        assertEquals(listOf("Elektrisches Feld", "Elektronik", "Elektrolyse", "Ableitung"), r.map { it.title })
        assertEquals(Triple("", "Elek", "trisches Feld"), r[0].parts)
        assertEquals("text", r[3].via); assertNull(r[3].parts)
    }

    @Test fun searchScopeAndEmptyAndFolders() {
        assertTrue(Nav.search(paths, mtime, text, "elek", "Projekte").all { it.area == "Projekte" })
        assertEquals("Elektrisches Feld", Nav.search(paths, mtime, text, "")[0].title)
        val f = Nav.search(paths, mtime, text, "/phy")
        assertEquals(listOf(Nav.Kind.FOLDER), f.map { it.kind }); assertEquals("Schule/Physik", f[0].path); assertEquals(3, f[0].count)
    }

    @Test fun activityGrid() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 10, 29) // Donnerstag
        fun ms(d: LocalDate, h: Int = 10) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        val a = Nav.activity(listOf(ms(today), ms(today, 15), ms(today.minusDays(3)), ms(LocalDate.of(2020, 1, 1))), today, zone)
        assertEquals(371, a.cells.size)
        assertEquals(2, a.cells[367]!!.n); assertEquals(2, a.cells[367]!!.level) // Donnerstag der letzten Woche
        assertEquals(1, a.cells[364]!!.n) // Montag
        assertNull(a.cells[368]) // Freitag liegt in der Zukunft
        assertEquals(3, a.total)
        assertTrue(a.months.any { it.first == "Okt" })
    }

    @Test fun whenText() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 10, 29)
        fun ms(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("09:00", Nav.whenText(ms(today, 9), today, zone))
        assertEquals("gestern", Nav.whenText(ms(today.minusDays(1), 9), today, zone))
        assertEquals("20.10.", Nav.whenText(ms(today.minusDays(9), 9), today, zone))
    }

    @Test fun inboxRoundTrip() {
        var c = Inbox.append("", "Idee eins", "2026-10-29 12:31")
        c = Inbox.append(c, "Zeile A\nZeile B", "2026-10-29 12:40")
        assertEquals("# Inbox\n\n- 2026-10-29 12:31 · Idee eins\n- 2026-10-29 12:40 · Zeile A / Zeile B\n", c)
        val ideas = Inbox.parse(c)
        assertEquals(listOf("Idee eins", "Zeile A / Zeile B"), ideas.map { it.text })
        assertEquals("# Inbox\n\n- 2026-10-29 12:40 · Zeile A / Zeile B\n", Inbox.remove(c, ideas[0].raw))
        assertEquals(c, Inbox.append(c, "   ", "x"))
    }

    @Test fun dueParsing() {
        val today = LocalDate.of(2026, 10, 29) // Donnerstag
        assertEquals("30.10.", Inbox.parseDue("Freitag", today)); assertEquals("30.10.", Inbox.parseDue("bis fr", today))
        assertEquals("29.10.", Inbox.parseDue("donnerstag", today)); assertEquals("02.11.", Inbox.parseDue("Montag", today))
        assertEquals("30.10.", Inbox.parseDue("morgen", today)); assertEquals("03.11.", Inbox.parseDue("3.11.", today))
        assertNull(Inbox.parseDue("31.2.", today)); assertNull(Inbox.parseDue("", today)); assertNull(Inbox.parseDue("irgendwann", today))
    }

    @Test fun taskParsingAndChecking() {
        val line = Inbox.taskLine("Übungsblatt 5 abgeben", 45, "30.10.")
        assertEquals("- [ ] Übungsblatt 5 abgeben (~45 min · bis 30.10.)", line)
        assertEquals("- [ ] Milch (~10 min)", Inbox.taskLine("Milch", 10, null))
        assertEquals("- [ ] Nur Titel", Inbox.taskLine("Nur Titel", null, null))
        val note = "# Ableitung\n\n$line\n- [x] fertig\n```\n- [ ] im Code\n```\n- [ ] Milch (~10 min)\n- [ ] Ohne Angaben"
        val t = Inbox.tasks(note)
        assertEquals(listOf("Übungsblatt 5 abgeben", "Milch", "Ohne Angaben"), t.map { it.title })
        assertEquals(listOf(45, 10, null), t.map { it.minutes }); assertEquals("30.10.", t[0].due); assertNull(t[1].due)
        assertEquals(listOf("Übungsblatt 5 abgeben (~45 min · bis 30.10.)", "Milch (~10 min)", "Ohne Angaben"), Nav.openTasks(note))
        val done = Inbox.check(note, t[0].line, "Übungsblatt 5 abgeben")!!
        assertTrue(done.contains("- [x] Übungsblatt 5 abgeben (~45 min · bis 30.10.)"))
        assertNull(Inbox.check(note, t[0].line, "Anderer Titel"))
        assertEquals("# T\n\n- [ ] x\n", Inbox.appendLine("", "T", "- [ ] x"))
    }
}
