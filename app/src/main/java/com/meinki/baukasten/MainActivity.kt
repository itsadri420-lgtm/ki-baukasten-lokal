package com.meinki.baukasten

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

class Agent(var id: String, var name: String, var rules: String, var knowledge: String)

class MainActivity : Activity() {
    private val C_TOP = 0xFF1A1033.toInt()
    private val C_BOT = 0xFF3B1F7A.toInt()
    private val C_ACC = 0xFFFF7A59.toInt()
    private val C_TXT = 0xFFFFFFFF.toInt()
    private val C_SUB = 0xFFCFC6F5.toInt()
    private val C_CARD = 0x26FFFFFF
    private val C_GLASS = 0x33FFFFFF
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val agents = mutableListOf<Agent>()
    private val chats = HashMap<String, MutableList<Pair<Boolean, String>>>()
    private var engine: Engine? = null
    private var busy = false
    private var onBack: () -> Unit = {}
    private val prefs get() = getSharedPreferences("ki", Context.MODE_PRIVATE)
    private val modelFile get() = File(filesDir, prefs.getString("model", "model.litertlm") ?: "model.litertlm")
    private val cacheLm get() = File(cacheDir, "lm")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = C_TOP
        window.navigationBarColor = C_BOT
        load()
        showList()
    }

    @Deprecated("ok")
    override fun onBackPressed() = onBack()

    // ---------- Speicher ----------
    private fun load() {
        val s = prefs.getString("agents", null)
        if (s != null) {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                agents.add(Agent(o.getString("id"), o.getString("name"), o.getString("rules"), o.getString("knowledge")))
            }
        }
        if (agents.isEmpty()) agents.add(Agent("a1", "Mein erster Assistent", "Antworte freundlich, kurz und auf Deutsch.", ""))
    }

    private fun save() {
        val arr = JSONArray()
        agents.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("rules", it.rules).put("knowledge", it.knowledge)) }
        prefs.edit().putString("agents", arr.toString()).apply()
    }

    // ---------- Modell-Verwaltung ----------
    private fun modelInfo(): String {
        val f = modelFile
        if (!f.exists()) return "Modell fehlt noch"
        val head = try {
            f.inputStream().use { s ->
                val b = ByteArray(8)
                val n = s.read(b)
                String(b, 0, maxOf(n, 0), Charsets.ISO_8859_1).filter { it.code in 32..126 }
            }
        } catch (e: Throwable) { "?" }
        return "Modell: " + (f.length() / 1048576) + " MB, Kopf: " + head
    }

    private fun deleteModel() {
        try { engine?.close() } catch (e: Throwable) { }
        engine = null
        filesDir.listFiles()?.filter { it.name.startsWith("model") }?.forEach { it.delete() }
        cacheLm.deleteRecursively()
        prefs.edit().remove("model").apply()
    }

    // ---------- Design-Helfer ----------
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun gradient(v: View) {
        v.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C_TOP, C_BOT))
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(250).start()
    }

    private fun appear(v: View, i: Int) {
        v.alpha = 0f
        v.translationY = dp(24).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay(i * 70L).setDuration(350).start()
    }

    private fun tv(t: String, sp: Float = 16f, bold: Boolean = false, color: Int = C_TXT) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun chip(t: String, color: Int) = tv(t, 13f, true, color).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6)); background = rounded(C_CARD, 20)
    }

    private fun btn(t: String, accent: Boolean = true, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; setTextColor(C_TXT)
        background = rounded(if (accent) C_ACC else C_GLASS, 14)
        stateListAnimator = null
        setOnClickListener { v ->
            v.animate().setStartDelay(0).scaleX(0.92f).scaleY(0.92f).setDuration(90).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                f()
            }.start()
        }
    }

    private fun edit(h: String, v: String, lines: Int) = EditText(this).apply {
        hint = h; setText(v)
        setTextColor(0xFF1A1033.toInt()); setHintTextColor(0xFF8A82A8.toInt())
        background = rounded(0xFFFFFFFF.toInt(), 12)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (lines > 1) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = lines; gravity = Gravity.TOP
        } else setSingleLine(true)
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(48), dp(18), dp(24))
    }

    private fun LinearLayout.put(v: View, top: Int = 10, w: Int = MATCH, weight: Float = 0f, left: Int = 0, anim: Int = -1): View {
        addView(v, LinearLayout.LayoutParams(w, WRAP, weight).apply { topMargin = dp(top); leftMargin = dp(left) })
        if (anim >= 0) appear(v, anim)
        return v
    }

    private fun get(id: String) = agents.first { it.id == id }

    // ---------- Startseite ----------
    private fun showList() {
        onBack = { finish() }
        val col = column()
        col.put(tv("🤖 KI-Baukasten", 30f, true), 0, anim = 0)
        col.put(tv("Deine eigenen KIs. Alles läuft offline auf deinem Handy.", 14f, false, C_SUB), 4, anim = 1)
        val ok = modelFile.exists()
        col.put(chip(if (ok) "● " + modelInfo() else "○ Modell fehlt noch", if (ok) 0xFF7CFFB2.toInt() else 0xFFFFC857.toInt()), 14, w = WRAP, anim = 2)
        col.put(btn("Modell-Datei auswählen (.litertlm)", false) { pickModel() }, 12, anim = 3)
        if (ok) col.put(btn("Modell löschen", false) { deleteModel(); showList() }, 8, anim = 3)
        var i = 4
        agents.forEach { a ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(10), dp(10))
                background = rounded(C_CARD, 20)
            }
            row.put(tv(a.name, 18f, true), 0, w = 0, weight = 1f)
            row.put(btn("Chat") { showChat(a.id) }, 0, w = WRAP, left = 6)
            row.put(btn("⚙︎", false) { showEdit(a.id) }, 0, w = WRAP, left = 6)
            col.put(row, 12, anim = i++)
        }
        col.put(btn("+ Neue KI erstellen") {
            val a = Agent("a" + System.currentTimeMillis(), "Neue KI", "", "")
            agents.add(a); save(); showEdit(a.id)
        }, 18, anim = i)
        setContentView(ScrollView(this).apply { addView(col); gradient(this) })
    }

    private fun pickModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(i, 1)
    }

    @Deprecated("ok")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (req != 1 || res != RESULT_OK) return
        Toast.makeText(this, "Modell wird kopiert, das kann einige Minuten dauern ...", Toast.LENGTH_LONG).show()
        thread {
            try {
                val tmp = File(filesDir, "copy.tmp")
                contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                deleteModel()
                val name = "model_" + System.currentTimeMillis() + ".litertlm"
                if (!tmp.renameTo(File(filesDir, name))) throw Exception("Umbenennen fehlgeschlagen")
                prefs.edit().putString("model", name).apply()
                runOnUiThread { Toast.makeText(this, "Modell kopiert", Toast.LENGTH_SHORT).show(); showList() }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Fehler: " + t.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------- Einstellungen ----------
    private fun showEdit(id: String) {
        val a = get(id)
        val name = edit("Name", a.name, 1)
        val rules = edit("z. B. Antworte immer kurz und höflich.", a.rules, 4)
        val know = edit("Hier deine Daten einfügen ...", a.knowledge, 10)
        val store = { a.name = name.text.toString(); a.rules = rules.text.toString(); a.knowledge = know.text.toString(); save() }
        onBack = { store(); showList() }
        val col = column()
        col.put(tv("Einstellungen", 28f, true), 0, anim = 0)
        col.put(tv("1. Name der KI", 16f, true), 16, anim = 1)
        col.put(name, 6, anim = 1)
        col.put(tv("2. Richtlinien", 16f, true), 18, anim = 2)
        col.put(tv("Wie soll sich die KI verhalten?", 13f, false, C_SUB), 2, anim = 2)
        col.put(rules, 6, anim = 2)
        col.put(tv("3. Wissen und Daten", 16f, true), 18, anim = 3)
        col.put(tv("Alles, was die KI kennen soll. Einfach einfügen.", 13f, false, C_SUB), 2, anim = 3)
        col.put(know, 6, anim = 3)
        col.put(btn("Speichern und zurück") { store(); showList() }, 22, anim = 4)
        col.put(btn("Diese KI löschen", false) { agents.remove(a); chats.remove(id); save(); showList() }, 8, anim = 5)
        setContentView(ScrollView(this).apply { addView(col); gradient(this) })
    }

    // ---------- Chat ----------
    private fun showChat(id: String) {
        val a = get(id)
        onBack = { showList() }
        val hist = chats.getOrPut(id) { mutableListOf() }
        val chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val scroll = ScrollView(this).apply { addView(chat) }

        fun bubble(mine: Boolean, t: String): TextView {
            val v = tv(t, 16f).apply {
                setTextIsSelectable(true)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(if (mine) C_ACC else C_GLASS, 18)
            }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = if (mine) Gravity.END else Gravity.START
