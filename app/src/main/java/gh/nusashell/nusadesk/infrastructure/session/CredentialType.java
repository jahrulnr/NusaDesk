package gh.nusashell.nusadesk.infrastructure.session;

/**
 * Kind of secret held by {@link KeystoreCredentialVault}. The vault stores the
 * encrypted bytes only; the type is kept so a future reader can route the
 * material to the right consumer (password auth vs. public-key auth).
 */
public enum CredentialType {
    PASSWORD,
    PRIVATE_KEY
}
