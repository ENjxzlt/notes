package online.minihomelab.notizen

import java.io.File

/** Notizen liegen als normale .md-Dateien in Ordnern; die Ordnerstruktur ist die einzige Ordnung. */
class NoteStore(val root: File) {

    init {
        root.mkdirs()
        // Nur in einen ganz leeren Ordner Startordner legen, sonst kämen gelöschte immer wieder
        if (root.list().isNullOrEmpty()) {
            for (n in listOf("Schule", "Projekte", "Alltag")) File(root, n).mkdirs()
        }
    }

    fun list(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") && (it.isDirectory || it.extension == "md") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    fun search(query: String): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "md" && it.nameWithoutExtension.contains(query, ignoreCase = true) }
            .sortedBy { it.nameWithoutExtension.lowercase() }
            .toList()

    fun cleanName(raw: String): String =
        raw.trim().replace(Regex("""[\\/:*?"<>|]"""), "-").trimStart('.').trim()

    /** Lesbarer Pfad ohne Endung, z. B. "Schule › Mathe › Ableitungen". */
    fun rel(f: File): String =
        f.relativeTo(root).path.removeSuffix(".md").replace(File.separator, " › ")

    fun createFolder(parent: File, name: String): File? {
        val n = cleanName(name)
        if (n.isEmpty()) return null
        val f = File(parent, n)
        if (f.exists()) return null
        return if (f.mkdirs()) f else null
    }

    fun createNote(parent: File, name: String): File? {
        val n = cleanName(name).removeSuffix(".md")
        if (n.isEmpty()) return null
        val f = File(parent, "$n.md")
        if (f.exists()) return null
        write(f, "# $n\n\n")
        return f
    }

    fun rename(f: File, newName: String): File? {
        val n = cleanName(newName).let { if (f.isFile) it.removeSuffix(".md") else it }
        if (n.isEmpty()) return null
        val target = File(f.parentFile, if (f.isFile) "$n.md" else n)
        if (target.exists()) return null
        if (!f.renameTo(target)) return null
        if (f.isFile || target.isFile) moveOverlay(target.parentFile, f.nameWithoutExtension, n)
        return target
    }

    /** Die Zeichenebene (.ink/_page_<Name>.json/.svg) folgt der Notiz. */
    private fun moveOverlay(dir: File?, oldName: String, newName: String) {
        if (dir == null) return
        for (ext in listOf("json", "svg")) {
            val from = File(Ink.dir(dir), "${Ink.overlayId(oldName)}.$ext")
            if (from.isFile) from.renameTo(File(Ink.dir(dir), "${Ink.overlayId(newName)}.$ext"))
        }
    }

    fun delete(f: File): Boolean {
        if (f.isFile) f.parentFile?.let { Ink.delete(it, Ink.overlayId(f.nameWithoutExtension)) }
        return f.deleteRecursively()
    }

    fun read(f: File): String = f.readText()

    /** Erst in eine Hilfsdatei schreiben, dann umbenennen: ein Absturz mittendrin lässt die alte Notiz heil. */
    fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}
