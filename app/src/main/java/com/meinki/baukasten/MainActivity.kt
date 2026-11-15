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
    private val modelFile get() = File(filesDir, "model.litertlm")
    private val prefs get() = getSharedPreferences("ki", Context.MODE_PRIVATE)

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
        col.put(chip(if (ok) "● Modell bereit" else "○ Modell fehlt noch", if (ok) 0xFF7CFFB2.toInt() else 0xFFFFC857.toInt()), 14, w = WRAP, anim = 2)
        col.put(btn("Modell-Datei auswählen (.litertlm)", false) { pickModel() }, 12, anim = 3)
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
                val tmp = File(filesDir, "model.tmp")
                contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                engine?.close(); engine = null
                modelFile.delete(); tmp.renameTo(modelFile)
                runOnUiThread { Toast.makeText(this, "Modell bereit", Toast.LENGTH_SHORT).show(); showList() }
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
                topMargin = dp(10)
                if (mine) leftMargin = dp(48) else rightMargin = dp(48)
            }
            chat.addView(v, lp)
            appear(v, 0)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            return v
        }
        hist.forEach { bubble(it.first, it.second) }

        val input = edit("Nachricht ...", "", 1)
        val send = btn("➤") {}
        send.setOnClickListener {
            val q = input.text.toString().trim()
            if (q.isEmpty() || busy) return@setOnClickListener
            input.setText(""); hist.add(true to q); bubble(true, q)
            if (!modelFile.exists()) {
                val m = "Bitte zuerst auf der Startseite die Modell-Datei auswählen."
                hist.add(false to m); bubble(false, m); return@setOnClickListener
            }
            val wait = bubble(false, "Denke nach ...")
            busy = true
            val pulse = ObjectAnimator.ofFloat(wait, "alpha", 1f, 0.3f).apply {
                duration = 700; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            }
            wait.postDelayed({ if (busy) pulse.start() }, 450)
            thread {
                val ans = try { ask(a, q) } catch (t: Throwable) { "Fehler: " + t.message }
                runOnUiThread {
                    pulse.cancel(); wait.alpha = 1f
                    wait.text = ans; hist.add(false to ans); busy = false
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(44), dp(12), dp(4)) }
        top.put(btn("←", false) { showList() }, 0, w = WRAP)
        top.put(tv("🤖 " + a.name, 20f, true), 0, w = WRAP, left = 12)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(12)) }
        bar.put(input, 0, w = 0, weight = 1f)
        bar.put(send, 0, w = WRAP, left = 8)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(top)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(bar)
        gradient(root)
        setContentView(root)
    }

    // ---------- KI (lokal) ----------
    private fun ensureEngine(): Engine {
        engine?.let { return it }
        val cfg = EngineConfig(modelPath = modelFile.absolutePath, backend = Backend.CPU(), cacheDir = cacheDir.path)
        val e = Engine(cfg); e.initialize(); engine = e
        return e
    }

    // Wählt aus deinen Daten nur die Abschnitte, die zur Frage passen
    private fun relevant(k: String, q: String): String {
        val chunks = k.split(Regex("\n\\s*\n")).flatMap { it.chunked(600) }.filter { it.isNotBlank() }
        val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        return chunks.sortedByDescending { c -> val l = c.lowercase(); words.count { l.contains(it) } }.take(5).joinToString("\n---\n")
    }

    private fun ask(a: Agent, q: String): String {
        val info = if (a.knowledge.isBlank()) "(keine Infos hinterlegt)" else relevant(a.knowledge, q)
        val prompt = "Du bist die KI \"${a.name}\".\nRICHTLINIEN:\n${a.rules.ifBlank { "keine" }}\n\n" +
            "INFOS (antworte nur anhand dieser Infos; steht die Antwort nicht darin, sage das ehrlich):\n$info\n\nFRAGE: $q\n\nANTWORT:"
        val e = ensureEngine()
        e.createConversation().use { c -> return c.sendMessage(prompt).toString() }
    }
}
