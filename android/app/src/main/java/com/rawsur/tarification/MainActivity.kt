package com.rawsur.tarification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.MailTo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File

class MainActivity : Activity() {

    private lateinit var webView: WebView

    /** Dernier fichier produit : joint automatiquement à l'e-mail préparé par l'outil. */
    private var dernier: Fichier? = null

    private class Fichier(val nom: String, val mime: String, val partage: Uri, val quand: Long)

    private val loader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        // Android 15 affiche l'appli bord à bord : on réserve la place des barres système et du clavier.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage : historique des cotations, préférences
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        webView.addJavascriptInterface(Pont(), "LrAndroid")

        val shim = assets.open("android-bridge.js").bufferedReader().use { it.readText() }
        val auDemarrage = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (auDemarrage) WebViewCompat.addDocumentStartJavaScript(webView, shim, setOf("https://$HOTE"))

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                return when {
                    u.host == HOTE -> false
                    u.scheme == "mailto" -> { ouvrirMail(u.toString()); true }
                    else -> { lancer(Intent(Intent.ACTION_VIEW, u)); true }
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!auDemarrage) view.evaluateJavascript(shim, null)
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl("https://$HOTE/assets/index.html")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Deprecated("Navigation retour de la WebView")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /* ---------- Fichiers ---------- */

    private inner class Pont {
        @JavascriptInterface
        fun recevoir(nom: String, mime: String, base64: String, action: String) {
            val octets = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: IllegalArgumentException) {
                runOnUiThread { toast("Fichier illisible : ${e.message}") }; return
            }
            runOnUiThread { traiter(nettoyer(nom), mime, octets, action) }
        }

        @JavascriptInterface
        fun erreur(message: String) = runOnUiThread { toast("Fichier non transmis : $message") }
    }

    private fun nettoyer(nom: String) = nom.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "document" }

    private fun traiter(nom: String, mime: String, octets: ByteArray, action: String) {
        // Copie privée exposée par FileProvider, pour ouvrir / partager / joindre à un e-mail.
        val dossier = File(cacheDir, "partage").apply { mkdirs() }
        val local = File(dossier, nom).apply { writeBytes(octets) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fichiers", local)
        val f = Fichier(nom, mime.ifBlank { "application/octet-stream" }, uri, System.currentTimeMillis())
        dernier = f

        if (action == "ouvrir") { ouvrir(f); return }

        val ou = try { enregistrerDansTelechargements(nom, f.mime, octets) } catch (e: Exception) { null }
        val texte = if (ou != null) "« $nom » est enregistré dans $ou." else
            "« $nom » n'a pas pu être copié dans Téléchargements, mais vous pouvez l'ouvrir ou le partager."
        AlertDialog.Builder(this)
            .setTitle("Fichier prêt")
            .setMessage(texte)
            .setPositiveButton("Ouvrir") { _, _ -> ouvrir(f) }
            .setNeutralButton("Partager") { _, _ -> partager(f) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun enregistrerDansTelechargements(nom: String, mime: String, octets: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, nom)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: error("insertion refusée")
            contentResolver.openOutputStream(uri)!!.use { it.write(octets) }
            return "Téléchargements"
        }
        @Suppress("DEPRECATION")
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            dl.mkdirs()
            var cible = File(dl, nom)
            var i = 1
            while (cible.exists()) cible = File(dl, nom.replaceFirst(Regex("(\\.[^.]*)?$"), " (${i++})$1"))
            cible.writeBytes(octets)
            return "Téléchargements"
        }
        val prive = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("stockage indisponible")
        File(prive, nom).writeBytes(octets)
        return "le dossier de l'application"
    }

    private fun ouvrir(f: Fichier) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(f.partage, f.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: ActivityNotFoundException) {
            toast("Aucune application ne peut ouvrir ce fichier : choisissez où l'envoyer.")
            partager(f)
        }
    }

    private fun partager(f: Fichier) {
        val i = Intent(Intent.ACTION_SEND).setType(f.mime)
            .putExtra(Intent.EXTRA_STREAM, f.partage)
            .putExtra(Intent.EXTRA_SUBJECT, f.nom)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lancer(Intent.createChooser(i, "Partager « ${f.nom} »"))
    }

    /* ---------- E-mail : le fichier qui vient d'être produit est joint d'office ---------- */

    private fun ouvrirMail(lien: String) {
        val m = try { MailTo.parse(lien) } catch (e: Exception) { lancer(Intent(Intent.ACTION_SENDTO, Uri.parse(lien))); return }
        val pj = dernier?.takeIf { System.currentTimeMillis() - it.quand < 30 * 60 * 1000 }
        if (pj == null) { lancer(Intent(Intent.ACTION_SENDTO, Uri.parse(lien))); return }
        val i = Intent(Intent.ACTION_SEND).setType(pj.mime)
            .putExtra(Intent.EXTRA_EMAIL, m.to?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toTypedArray() ?: emptyArray())
            .putExtra(Intent.EXTRA_SUBJECT, m.subject ?: "")
            .putExtra(Intent.EXTRA_TEXT, (m.body ?: "").replace("Le fichier a été téléchargé sur mon appareil : je le joins à ce message.", "Le fichier est joint à ce message."))
            .putExtra(Intent.EXTRA_STREAM, pj.partage)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) // n'affiche que les messageries
        try { startActivity(i) } catch (e: ActivityNotFoundException) {
            lancer(Intent.createChooser(i.apply { selector = null }, "Envoyer avec…"))
        }
    }

    private fun lancer(i: Intent) {
        try { startActivity(i) } catch (e: ActivityNotFoundException) { toast("Aucune application disponible pour cette action.") }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object { private const val HOTE = "appassets.androidplatform.net" }
}
