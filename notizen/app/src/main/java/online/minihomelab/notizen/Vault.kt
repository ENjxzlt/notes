package online.minihomelab.notizen

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Lesezugriff auf alle Notizen als relative Pfade ("Schule/Physik/Feld.md") für Navigation, Übersichten und Ideen-Dump. */
class Vault(val store: NoteStore) {
    data class Note(val path: String, val mtime: Long)

    private val texts = HashMap<String, Pair<Long, String>>() // Pfad -> (mtime, Text), für die Volltextsuche

    fun file(path: String) = File(store.root, path)

    fun notes(): List<Note> =
        store.root.walkTopDown()
            .onEnter { !it.name.startsWith(".") || it == store.root }
            .filter { it.isFile && it.extension == "md" && !it.name.startsWith(".") }
            .map { Note(it.relativeTo(store.root).path.replace(File.separatorChar, '/'), it.lastModified()) }
            .toList()

    fun read(path: String): String = try { file(path).readText() } catch (e: Exception) { "" }

    fun text(path: String): String? {
        val f = file(path)
        val mt = f.lastModified()
        texts[path]?.let { if (it.first == mt) return it.second }
        val t = read(path)
        texts[path] = mt to t
        return t
    }

    fun write(path: String, text: String) = store.write(file(path), text)

    // ---- Inbox ----

    fun ideas(): List<Inbox.Idea> = Inbox.parse(read(Inbox.FILE))

    fun addIdea(text: String): Boolean {
        if (text.isBlank()) return false
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        write(Inbox.FILE, Inbox.append(read(Inbox.FILE), text, stamp))
        return true
    }

    fun removeIdea(raw: String) = write(Inbox.FILE, Inbox.remove(read(Inbox.FILE), raw))

    // ---- Aufgaben ----

    data class TaskRef(val path: String, val task: Inbox.Task)

    fun tasks(notes: Collection<Note> = notes()): List<TaskRef> =
        notes.sortedByDescending { it.mtime }.flatMap { n -> Inbox.tasks(read(n.path)).map { TaskRef(n.path, it) } }

    fun checkTask(ref: TaskRef): Boolean {
        val updated = Inbox.check(read(ref.path), ref.task.line, ref.task.title) ?: return false
        write(ref.path, updated)
        return true
    }

    /** Legt die Aufgabe in `<Bereich>/Aufgaben.md` ab. */
    fun addTask(area: String, title: String, minutes: Int?, due: String?): String {
        val path = "$area/Aufgaben.md"
        write(path, Inbox.appendLine(read(path), "Aufgaben", Inbox.taskLine(title, minutes, due)))
        return path
    }

    /** Neue Notiz aus einer Idee (Titel = erste Wörter); der Name wird bei Bedarf hochgezählt. */
    fun noteFromIdea(area: String, text: String): String {
        val base = store.cleanName(text.take(48).trim().trimEnd('.', ',', ' ')).ifEmpty { "Idee" }
        var name = base
        var n = 2
        while (file("$area/$name.md").exists()) name = "$base ${n++}"
        val path = "$area/$name.md"
        write(path, "# $name\n\n$text\n")
        return path
    }
}
