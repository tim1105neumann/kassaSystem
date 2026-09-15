package at.heuriger.kassa.net.contract

import org.junit.Assume

/**
 * Steuert, ob die Vertragstests gegen den echten Server laufen.
 *
 * Ohne Guard waere ein stiller Skip die gefaehrlichste Variante: die Suite
 * bliebe gruen, obwohl nie eine einzige Zeile gegen den Server lief. Deshalb
 * zwei Schalter — `KASSA_CONTRACT_URL` sagt *wohin*, `KASSA_CONTRACT_REQUIRED=1`
 * sagt *es muss*. Die Gradle-Task `contractTest` setzt beide.
 */
object ContractEnv {

    val baseUrl: String? = System.getenv("KASSA_CONTRACT_URL")?.takeIf { it.isNotBlank() }

    private val required: Boolean = System.getenv("KASSA_CONTRACT_REQUIRED") == "1"

    val password: String = System.getenv("KASSA_CONTRACT_PASSWORD")?.takeIf { it.isNotBlank() } ?: "test"

    /**
     * Liefert die Server-URL oder ueberspringt den Test sauber. Ist
     * `KASSA_CONTRACT_REQUIRED=1` gesetzt, ist eine fehlende URL ein harter
     * Fehler statt eines Skips.
     */
    fun requireBaseUrl(): String {
        if (required) {
            return baseUrl ?: throw AssertionError(
                "KASSA_CONTRACT_REQUIRED=1, aber KASSA_CONTRACT_URL ist nicht gesetzt. " +
                    "Die Vertragstests haetten hier still uebersprungen werden koennen — " +
                    "genau das soll der Schalter verhindern.",
            )
        }
        Assume.assumeTrue(
            "KASSA_CONTRACT_URL nicht gesetzt — Vertragstests uebersprungen " +
                "(mit ./gradlew :app:contractTest gegen den laufenden Server ausfuehren).",
            baseUrl != null,
        )
        return baseUrl!!
    }
}
