package br.com.minhatv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : ComponentActivity() {
    private lateinit var msg: TextView
    private lateinit var srv: EditText
    private lateinit var usr: EditText
    private lateinit var pwd: EditText
    private lateinit var btn: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(C.bg) }
        // parede de capas à direita
        val wall = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; rotation = -8f; alpha = 0.45f }
        for (c in 0 until 6) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (r in 0 until 5) col.addView(android.view.View(this).apply { background = rounded(C.pal[(c * 3 + r * 7) % C.pal.size], dpf(8f)) }, llp(dp(110), dp(160)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            wall.addView(col)
        }
        root.addView(wall, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END).apply { setMargins(0, -dp(60), -dp(60), 0) })

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(64), dp(24), dp(64), dp(24))
            setBackgroundColor(C.bg)
        }
        form.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.logo_brtv); adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_START }, llp(WRAP, dp(90)))
        form.addView(text("Entre com a sua lista", 28f, Color.WHITE, true), llp(WRAP, WRAP).apply { topMargin = dp(14) })
        form.addView(text("Use os dados de acesso que o seu provedor enviou.", 14f, C.tx2), llp(WRAP, WRAP).apply { topMargin = dp(8) })
        srv = field("SERVIDOR", "http://servidor.com:8080", form, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        usr = field("USUÁRIO", "", form, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        pwd = field("SENHA", "", form, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pwd.imeOptions = EditorInfo.IME_ACTION_DONE
        pwd.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { doLogin(); true } else false }
        btn = button("Entrar", true) { doLogin() }.apply {
            background = rounded(C.ac, dpf(8f))
            setOnFocusChangeListener { v, f ->
                v.background = if (f) rounded(C.ac, dpf(8f), dp(3), Color.WHITE) else rounded(C.ac, dpf(8f))
                v.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(120).start()
            }
        }
        form.addView(btn, llp(dp(380), dp(48)).apply { topMargin = dp(20) })
        msg = text(intent.getStringExtra("msg") ?: "Seus dados ficam salvos só nesta TV.", 13f, if (intent.hasExtra("msg")) 0xFFFF9B9B.toInt() else C.tx3).apply { setLineSpacing(0f, 1.3f) }
        form.addView(msg, llp(dp(380), WRAP).apply { topMargin = dp(14) })
        root.addView(form, FrameLayout.LayoutParams(dp(520), FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        Store.str("cred")?.let {
            try {
                val o = org.json.JSONObject(it)
                srv.setText(o.s("server")); usr.setText(o.s("user")); pwd.setText(o.s("pass"))
            } catch (e: Exception) {}
        }
        (if (srv.text.isEmpty()) srv else btn).requestFocus()
    }

    private fun field(label: String, hint: String, parent: LinearLayout, type: Int): EditText {
        parent.addView(text(label, 12f, C.tx2, true).apply { letterSpacing = 0.06f }, llp(WRAP, WRAP).apply { topMargin = dp(16) })
        val e = EditText(this).apply {
            this.hint = hint
            inputType = type
            setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(C.tx3); textSize = 16f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            imeOptions = EditorInfo.IME_ACTION_NEXT
            val n = rounded(0xFF171A22.toInt(), dpf(8f), dp(2), C.line)
            val f = rounded(0xFF171A22.toInt(), dpf(8f), dp(2), C.ac)
            background = n
            setOnFocusChangeListener { v, has -> v.background = if (has) f else n }
        }
        parent.addView(e, llp(dp(380), WRAP).apply { topMargin = dp(6) })
        return e
    }

    private fun doLogin() {
        if (busy) return
        val (server, u, p) = Xt.parseServer(srv.text.toString())
        val user = u ?: usr.text.toString().trim()
        val pass = p ?: pwd.text.toString().trim()
        if (srv.text.isBlank() || user.isEmpty() || pass.isEmpty()) { err("Preencha servidor, usuário e senha."); return }
        Xt.server = server; Xt.user = user; Xt.pass = pass
        busy = true
        msg.setTextColor(C.tx2); msg.text = "Conectando…"
        lifecycleScope.launch {
            val e = withContext(Dispatchers.IO) { Repo.login() }
            if (e != null) { busy = false; err(e); return@launch }
            Repo.saveCredentials()
            Store.put("trend", null)
            Repo.logoutCacheOnly()
            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
            finish()
        }
    }

    private fun err(t: String) {
        msg.setTextColor(0xFFFF9B9B.toInt()); msg.text = t
    }
}
