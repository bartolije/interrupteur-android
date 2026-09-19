package com.example.interrupteur

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors

/**
 * Un interrupteur pilotable : un nom, un type, et selon le type soit une IP/canal
 * (Shelly), soit deux URL saisies a la main (Generique).
 */
data class Interrupteur(
    val nom: String,
    val type: TypeInterrupteur,
    val ip: String = "",
    val canal: Int = 0,
    val urlOn: String = "",
    val urlOff: String = ""
)

/**
 * Les types d'interrupteurs geres par l'appli. Chaque type sait construire ses propres
 * URL (allumer / eteindre / lire l'etat) a partir des infos stockees dans l'Interrupteur.
 */
enum class TypeInterrupteur(val libelle: String) {

    // Deux URL saisies a la main (toujours en GET) : marche avec n'importe quel appareil
    // qui se pilote via une simple url, mais on ne peut pas connaitre son etat actuel.
    GENERIQUE("Generique") {
        override fun urlAllumer(i: Interrupteur) = i.urlOn
        override fun urlEteindre(i: Interrupteur) = i.urlOff
        override fun urlEtat(i: Interrupteur): String? = null
    },

    // Shelly generation 2 (Plus/Pro/Mini) : API RPC, un canal par relais (0, 1, ...).
    SHELLY("Shelly") {
        override fun urlAllumer(i: Interrupteur) = "http://${i.ip}/rpc/Switch.Set?id=${i.canal}&on=true"
        override fun urlEteindre(i: Interrupteur) = "http://${i.ip}/rpc/Switch.Set?id=${i.canal}&on=false"
        override fun urlEtat(i: Interrupteur): String? = "http://${i.ip}/rpc/Switch.GetStatus?id=${i.canal}"
    };

    abstract fun urlAllumer(i: Interrupteur): String
    abstract fun urlEteindre(i: Interrupteur): String

    /** Retourne null si ce type d'appareil ne permet pas de connaitre son etat actuel. */
    abstract fun urlEtat(i: Interrupteur): String?
}

/** Ce qu'on retrouve en interrogeant un Shelly sur le reseau (nom, modele, nombre de relais). */
data class InfoShelly(val ip: String, val nom: String, val modele: String, val nombreCanaux: Int)

private const val PREFS_NOM = "interrupteurs_prefs"
private const val PREFS_CLE_LISTE = "liste_interrupteurs"

class MainActivity : AppCompatActivity() {

    private lateinit var conteneur: LinearLayout
    private var interrupteurs = mutableListOf<Interrupteur>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        conteneur = findViewById(R.id.conteneurInterrupteurs)
        findViewById<MaterialButton>(R.id.boutonOuvrirFormulaire).setOnClickListener {
            ouvrirFormulaireAjout()
        }
        findViewById<MaterialButton>(R.id.boutonRafraichir).setOnClickListener {
            afficherListe()
        }

        interrupteurs = chargerInterrupteurs()
        afficherListe()
    }

    /**
     * Boite de dialogue "Ajouter un interrupteur" : type (Spinner), nom, puis les champs
     * qui changent selon le type choisi (IP+canal+scan pour Shelly, ou URL ON/OFF pour Generique).
     */
    private fun ouvrirFormulaireAjout() {
        val vueFormulaire = LayoutInflater.from(this).inflate(R.layout.dialog_ajouter_interrupteur, null)

        val spinnerType = vueFormulaire.findViewById<Spinner>(R.id.spinnerType)
        val champNom = vueFormulaire.findViewById<EditText>(R.id.champNomDialog)
        val blocIp = vueFormulaire.findViewById<LinearLayout>(R.id.blocIpDialog)
        val champIp = vueFormulaire.findViewById<EditText>(R.id.champIpDialog)
        val boutonDecouvrir = vueFormulaire.findViewById<MaterialButton>(R.id.boutonDecouvrir)
        val champCanal = vueFormulaire.findViewById<EditText>(R.id.champCanalDialog)
        val boutonScannerReseau = vueFormulaire.findViewById<MaterialButton>(R.id.boutonScannerReseau)
        val labelUrlOn = vueFormulaire.findViewById<TextView>(R.id.labelUrlOn)
        val champUrlOn = vueFormulaire.findViewById<EditText>(R.id.champUrlOnDialog)
        val labelUrlOff = vueFormulaire.findViewById<TextView>(R.id.labelUrlOff)
        val champUrlOff = vueFormulaire.findViewById<EditText>(R.id.champUrlOffDialog)

        spinnerType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            TypeInterrupteur.values().map { it.libelle }
        )

        // Les champs affiches dependent du type choisi.
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, vue: View?, position: Int, id: Long) {
                val estShelly = TypeInterrupteur.values()[position] == TypeInterrupteur.SHELLY
                blocIp.visibility = if (estShelly) View.VISIBLE else View.GONE
                champCanal.visibility = if (estShelly) View.VISIBLE else View.GONE
                boutonScannerReseau.visibility = if (estShelly) View.VISIBLE else View.GONE
                labelUrlOn.visibility = if (estShelly) View.GONE else View.VISIBLE
                champUrlOn.visibility = if (estShelly) View.GONE else View.VISIBLE
                labelUrlOff.visibility = if (estShelly) View.GONE else View.VISIBLE
                champUrlOff.visibility = if (estShelly) View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Valeurs pre-remplies pour aller plus vite a la saisie, curseur place a la fin.
        champIp.setText("192.168.0.")
        champIp.setSelection(champIp.text.length)
        champUrlOn.setText("http://192.168.0.")
        champUrlOn.setSelection(champUrlOn.text.length)
        champUrlOff.setText("http://192.168.0.")
        champUrlOff.setSelection(champUrlOff.text.length)

        // "Decouvrir" : interroge l'IP deja saisie pour recuperer nom/modele/nombre de canaux.
        boutonDecouvrir.setOnClickListener {
            val ip = champIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Renseigne d'abord une adresse IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            boutonDecouvrir.text = "..."
            Thread {
                val info = interrogerShelly(ip)
                runOnUiThread {
                    boutonDecouvrir.text = "Decouvrir"
                    if (info == null) {
                        Toast.makeText(this, "Aucun Shelly trouve a cette adresse", Toast.LENGTH_SHORT).show()
                    } else {
                        if (champNom.text.isBlank()) champNom.setText(info.nom)
                        Toast.makeText(
                            this,
                            "Trouve : ${info.modele} - ${info.nombreCanaux} canal(aux) (0${if (info.nombreCanaux > 1) " a ${info.nombreCanaux - 1}" else ""})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }

        // "Scanner le reseau" : cherche tous les Shelly repondant sur le sous-reseau actuel.
        boutonScannerReseau.setOnClickListener {
            boutonScannerReseau.text = "Scan en cours..."
            boutonScannerReseau.isEnabled = false
            scannerReseauShelly { resultats ->
                boutonScannerReseau.text = "Scanner le reseau"
                boutonScannerReseau.isEnabled = true

                if (resultats.isEmpty()) {
                    Toast.makeText(this, "Aucun Shelly trouve sur le reseau", Toast.LENGTH_SHORT).show()
                    return@scannerReseauShelly
                }

                val libelles = resultats.map { "${it.modele}  (${it.ip})  - ${it.nombreCanaux} canal(aux)" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Shelly trouves")
                    .setItems(libelles) { _, position ->
                        val choisi = resultats[position]
                        champIp.setText(choisi.ip)
                        if (champNom.text.isBlank()) champNom.setText(choisi.nom)
                    }
                    .show()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Ajouter un interrupteur")
            .setView(vueFormulaire)
            .setPositiveButton("Ajouter") { _, _ ->
                val nom = champNom.text.toString().trim()
                val type = TypeInterrupteur.values()[spinnerType.selectedItemPosition]

                if (nom.isEmpty()) {
                    Toast.makeText(this, "Renseigne un nom", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val nouvel = when (type) {
                    TypeInterrupteur.SHELLY -> {
                        val ip = champIp.text.toString().trim()
                        val canal = champCanal.text.toString().trim().toIntOrNull() ?: 0
                        if (ip.isEmpty()) {
                            Toast.makeText(this, "Renseigne une adresse IP", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        Interrupteur(nom = nom, type = type, ip = ip, canal = canal)
                    }
                    TypeInterrupteur.GENERIQUE -> {
                        val urlOn = champUrlOn.text.toString().trim()
                        val urlOff = champUrlOff.text.toString().trim()
                        if (urlOn.isEmpty() || urlOff.isEmpty()) {
                            Toast.makeText(this, "Renseigne les URL ON et OFF", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        Interrupteur(nom = nom, type = type, urlOn = urlOn, urlOff = urlOff)
                    }
                }

                interrupteurs.add(nouvel)
                sauvegarderInterrupteurs(interrupteurs)
                afficherListe()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /**
     * Reconstruit entierement la liste affichee a partir de la liste en memoire
     * (et relit au passage l'etat de chaque Shelly). Methode simple (pas de
     * RecyclerView) : suffisant pour quelques interrupteurs.
     */
    private fun afficherListe() {
        conteneur.removeAllViews()

        for (interrupteur in interrupteurs) {
            val vue = LayoutInflater.from(this).inflate(R.layout.item_interrupteur, conteneur, false)

            val texteNom = vue.findViewById<TextView>(R.id.texteNomIp)
            val texteEtat = vue.findViewById<TextView>(R.id.texteEtat)
            val boutonAllumer = vue.findViewById<MaterialButton>(R.id.boutonAllumer)
            val boutonEteindre = vue.findViewById<MaterialButton>(R.id.boutonEteindre)
            val texteStatut = vue.findViewById<TextView>(R.id.texteStatut)
            val boutonSupprimer = vue.findViewById<TextView>(R.id.boutonSupprimer)

            texteNom.text = when (interrupteur.type) {
                TypeInterrupteur.SHELLY -> "${interrupteur.nom}  (${interrupteur.ip}) - canal ${interrupteur.canal}"
                TypeInterrupteur.GENERIQUE -> interrupteur.nom
            }

            rafraichirEtat(interrupteur, texteEtat)

            boutonAllumer.setOnClickListener {
                val url = interrupteur.type.urlAllumer(interrupteur)
                envoyerCommande(url, texteStatut) { rafraichirEtat(interrupteur, texteEtat) }
            }
            boutonEteindre.setOnClickListener {
                val url = interrupteur.type.urlEteindre(interrupteur)
                envoyerCommande(url, texteStatut) { rafraichirEtat(interrupteur, texteEtat) }
            }
            boutonSupprimer.setOnClickListener {
                interrupteurs.remove(interrupteur)
                sauvegarderInterrupteurs(interrupteurs)
                afficherListe()
            }

            conteneur.addView(vue)
        }
    }

    /**
     * Interroge l'etat actuel (allume / eteint) si le type d'appareil le permet,
     * et met a jour le texte correspondant (avec une couleur qui va avec).
     */
    private fun rafraichirEtat(interrupteur: Interrupteur, texteEtat: TextView) {
        val url = interrupteur.type.urlEtat(interrupteur)
        if (url == null) {
            texteEtat.text = "État : inconnu"
            texteEtat.setTextColor(0xFF999999.toInt())
            return
        }

        texteEtat.text = "État : ..."
        texteEtat.setTextColor(0xFF999999.toInt())

        Thread {
            val allume = try {
                JSONObject(lireCorpsHttp(url)).getBoolean("output")
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                when (allume) {
                    true -> {
                        texteEtat.text = "État : allumé"
                        texteEtat.setTextColor(ContextCompat.getColor(this, R.color.boutonAllumer))
                    }
                    false -> {
                        texteEtat.text = "État : éteint"
                        texteEtat.setTextColor(ContextCompat.getColor(this, R.color.boutonEteindre))
                    }
                    null -> {
                        texteEtat.text = "État : inconnu (erreur)"
                        texteEtat.setTextColor(0xFF999999.toInt())
                    }
                }
            }
        }.start()
    }

    /**
     * Lance la requete http dans un thread separe, affiche le resultat, puis
     * execute apresEnvoi() (utilise pour rafraichir l'etat une fois la commande envoyee).
     */
    private fun envoyerCommande(url: String, texteStatut: TextView, apresEnvoi: () -> Unit) {
        texteStatut.text = "Envoi en cours..."

        // Un appel reseau ne doit JAMAIS se faire sur le thread principal (thread UI),
        // sinon Android bloque l'appli (NetworkOnMainThreadException).
        Thread {
            val resultat = envoyerRequeteHttp(url)
            runOnUiThread {
                texteStatut.text = resultat
                apresEnvoi()
            }
        }.start()
    }

    private fun envoyerRequeteHttp(urlString: String): String {
        return try {
            val url = URL(urlString)
            val connexion = url.openConnection() as HttpURLConnection
            connexion.requestMethod = "GET"
            connexion.connectTimeout = 5000
            connexion.readTimeout = 5000

            val codeReponse = connexion.responseCode
            connexion.disconnect()

            "OK - code reponse : $codeReponse"
        } catch (e: Exception) {
            "Erreur : ${e.message}"
        }
    }

    /** Fait un GET et renvoie le corps de la reponse (texte), pour pouvoir le parser en JSON. */
    private fun lireCorpsHttp(urlString: String, timeoutMs: Int = 1500): String {
        val url = URL(urlString)
        val connexion = url.openConnection() as HttpURLConnection
        connexion.requestMethod = "GET"
        connexion.connectTimeout = timeoutMs
        connexion.readTimeout = timeoutMs
        val corps = connexion.inputStream.bufferedReader().readText()
        connexion.disconnect()
        return corps
    }

    // ---- Decouverte des Shelly (une IP precise, ou scan de tout le sous-reseau) ----

    /**
     * Interroge un Shelly Gen2 a une IP donnee : nom/modele via Shelly.GetDeviceInfo,
     * et nombre de canaux (relais) via Shelly.GetStatus (en comptant les cles "switch:N").
     * Renvoie null si rien ne repond a cette adresse (ou si ce n'est pas un Shelly Gen2).
     */
    private fun interrogerShelly(ip: String, timeoutMs: Int = 400): InfoShelly? {
        return try {
            val infosDevice = JSONObject(lireCorpsHttp("http://$ip/rpc/Shelly.GetDeviceInfo", timeoutMs))
            val modele = infosDevice.optString("app", infosDevice.optString("model", "Shelly"))
            // Le champ "name" peut valoir JSON null (aucun nom configure sur l'appareil) :
            // isNull() le detecte, alors que optString() le renverrait comme la chaine "null".
            val nom = if (infosDevice.isNull("name")) modele else infosDevice.optString("name").ifBlank { modele }

            val statut = JSONObject(lireCorpsHttp("http://$ip/rpc/Shelly.GetStatus", timeoutMs))
            var nombreCanaux = 0
            while (statut.has("switch:$nombreCanaux")) nombreCanaux++

            InfoShelly(ip = ip, nom = nom, modele = modele, nombreCanaux = nombreCanaux.coerceAtLeast(1))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Scanne tout le sous-reseau local (les 254 adresses de la forme x.x.x.1 a x.x.x.254)
     * a la recherche de Shelly, en parallele (sinon 254 appels les uns apres les autres
     * serait beaucoup trop lent). Le resultat est renvoye sur le thread principal.
     */
    private fun scannerReseauShelly(surResultat: (List<InfoShelly>) -> Unit) {
        Thread {
            val prefixe = trouverPrefixeReseauLocal()
            if (prefixe == null) {
                runOnUiThread { surResultat(emptyList()) }
                return@Thread
            }

            val executeur = Executors.newFixedThreadPool(32)
            val resultats = java.util.Collections.synchronizedList(mutableListOf<InfoShelly>())

            val taches = (1..254).map { dernierOctet ->
                executeur.submit {
                    interrogerShelly("$prefixe$dernierOctet")?.let { resultats.add(it) }
                }
            }
            taches.forEach { it.get() }
            executeur.shutdown()

            runOnUiThread { surResultat(resultats.sortedBy { it.ip }) }
        }.start()
    }

    /** Cherche l'adresse IPv4 locale du telephone (wifi) et renvoie son prefixe "192.168.0." */
    private fun trouverPrefixeReseauLocal(): String? {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { reseau ->
            reseau.inetAddresses?.toList()?.forEach { adresse ->
                if (!adresse.isLoopbackAddress && adresse is Inet4Address) {
                    val morceaux = adresse.hostAddress?.split(".")
                    if (morceaux?.size == 4) {
                        return "${morceaux[0]}.${morceaux[1]}.${morceaux[2]}."
                    }
                }
            }
        }
        return null
    }

    // ---- Sauvegarde / chargement (SharedPreferences + JSON), pour garder la liste apres fermeture ----

    private fun chargerInterrupteurs(): MutableList<Interrupteur> {
        val prefs = getSharedPreferences(PREFS_NOM, Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_CLE_LISTE, null)

        if (json == null) {
            // Premier lancement : on part avec les deux interrupteurs deja connus.
            val listeParDefaut = mutableListOf(
                Interrupteur(
                    nom = "Lumiere",
                    type = TypeInterrupteur.GENERIQUE,
                    urlOn = "http://192.168.0.61/lc=on",
                    urlOff = "http://192.168.0.61/lc=off"
                ),
                Interrupteur(
                    nom = "Shelly 1 PM Mini",
                    type = TypeInterrupteur.SHELLY,
                    ip = "192.168.0.147",
                    canal = 0
                )
            )
            sauvegarderInterrupteurs(listeParDefaut)
            return listeParDefaut
        }

        val tableau = JSONArray(json)
        val liste = mutableListOf<Interrupteur>()
        for (i in 0 until tableau.length()) {
            val objet = tableau.getJSONObject(i)
            liste.add(
                Interrupteur(
                    nom = objet.getString("nom"),
                    type = TypeInterrupteur.valueOf(objet.getString("type")),
                    ip = objet.optString("ip", ""),
                    canal = objet.optInt("canal", 0),
                    urlOn = objet.optString("urlOn", ""),
                    urlOff = objet.optString("urlOff", "")
                )
            )
        }
        return liste
    }

    private fun sauvegarderInterrupteurs(liste: List<Interrupteur>) {
        val tableau = JSONArray()
        for (interrupteur in liste) {
            val objet = JSONObject()
            objet.put("nom", interrupteur.nom)
            objet.put("type", interrupteur.type.name)
            objet.put("ip", interrupteur.ip)
            objet.put("canal", interrupteur.canal)
            objet.put("urlOn", interrupteur.urlOn)
            objet.put("urlOff", interrupteur.urlOff)
            tableau.put(objet)
        }

        val prefs = getSharedPreferences(PREFS_NOM, Context.MODE_PRIVATE)
        prefs.edit().putString(PREFS_CLE_LISTE, tableau.toString()).apply()
    }
}
