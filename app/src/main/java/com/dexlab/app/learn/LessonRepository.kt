package com.dexlab.app.learn

import android.content.Context
import org.json.JSONObject

data class LessonSection(val heading: String, val body: String, val code: String?)

data class Lesson(
    val id: String,
    val title: String,
    val minutes: Int,
    val summary: String,
    val sections: List<LessonSection>,
    val task: String,
    val answer: String,
    val tryTool: String?
)

data class LessonModule(
    val id: String,
    val title: String,
    val subtitle: String,
    val lessons: List<Lesson>
)

class LessonRepository(private val context: Context) {
    val modules: List<LessonModule> by lazy { load() }
    val allLessons: List<Lesson> get() = modules.flatMap { it.lessons }

    fun lesson(id: String): Lesson? = allLessons.firstOrNull { it.id == id }

    fun isCompleted(id: String): Boolean = completed().contains(id)

    fun completedCount(): Int = allLessons.count { isCompleted(it.id) }

    fun markCompleted(id: String, done: Boolean) {
        val current = completed().toMutableSet()
        if (done) current.add(id) else current.remove(id)
        prefs().edit().putStringSet(KEY, current).apply()
    }

    fun resetProgress() {
        prefs().edit().remove(KEY).apply()
    }

    private fun completed(): Set<String> =
        prefs().getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    private fun prefs() = context.getSharedPreferences("dexlab_learn", Context.MODE_PRIVATE)

    private fun load(): List<LessonModule> {
        return runCatching {
            val text = context.assets.open("lessons.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val modules = root.getJSONArray("modules")
            val out = ArrayList<LessonModule>(modules.length())
            for (i in 0 until modules.length()) {
                val m = modules.getJSONObject(i)
                val lessonsJson = m.getJSONArray("lessons")
                val lessons = ArrayList<Lesson>(lessonsJson.length())
                for (j in 0 until lessonsJson.length()) {
                    val l = lessonsJson.getJSONObject(j)
                    val sectionsJson = l.getJSONArray("sections")
                    val sections = ArrayList<LessonSection>(sectionsJson.length())
                    for (k in 0 until sectionsJson.length()) {
                        val s = sectionsJson.getJSONObject(k)
                        val code = s.optString("code", "")
                        sections.add(LessonSection(s.getString("heading"), s.getString("body"), code.ifBlank { null }))
                    }
                    lessons.add(
                        Lesson(
                            id = l.getString("id"),
                            title = l.getString("title"),
                            minutes = l.optInt("minutes", 5),
                            summary = l.optString("summary", ""),
                            sections = sections,
                            task = l.optString("task", ""),
                            answer = l.optString("answer", ""),
                            tryTool = l.optString("tryTool", "").ifBlank { null }
                        )
                    )
                }
                out.add(LessonModule(m.getString("id"), m.getString("title"), m.optString("subtitle", ""), lessons))
            }
            out
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "completed"
    }
}
