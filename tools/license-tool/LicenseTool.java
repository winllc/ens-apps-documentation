import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Issues and checks ENS Solutions license files. Needs only a JDK (17+):
 *
 * <pre>
 *   java LicenseTool.java keygen  --out keys/ens-license
 *   java LicenseTool.java sign    --key keys/ens-license.private.pem --product cert-alert \
 *                                 --licensee "Acme Corp" --expires 2027-12-31 [--features "*"] \
 *                                 --out acme-cert-alert.lic
 *   java LicenseTool.java verify  --key keys/ens-license.public.pem --license acme-cert-alert.lic
 * </pre>
 *
 * A license file is a properties file. Every line but {@code signature} is signed: the keys are
 * sorted, written as {@code key=value} lines joined by {@code \n}, and signed with Ed25519.
 * The applications verify the same way with the public key built into their jars.
 */
public class LicenseTool {

    static final List<String> PRODUCTS = List.of(
            "cert-alert", "readyroom", "rsa-operations-portal", "directory-services-portal");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Map<String, String> opts = options(args);
        switch (args[0]) {
            case "keygen" -> keygen(required(opts, "out"));
            case "sign" -> sign(opts);
            case "verify" -> verify(required(opts, "key"), required(opts, "license"));
            default -> usage();
        }
    }

    static void keygen(String prefix) throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path priv = Path.of(prefix + ".private.pem");
        Path pub = Path.of(prefix + ".public.pem");
        if (priv.getParent() != null) {
            Files.createDirectories(priv.getParent());
        }
        if (Files.exists(priv)) {
            throw new IllegalStateException(priv + " already exists; refusing to overwrite it");
        }
        Files.writeString(priv, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(pub, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        System.out.println("Wrote " + priv + " (keep it secret, never commit it)");
        System.out.println("Wrote " + pub + " (copy it into each application as license-public.pem)");
    }

    static void sign(Map<String, String> opts) throws Exception {
        String product = required(opts, "product");
        if (!PRODUCTS.contains(product)) {
            throw new IllegalArgumentException("--product must be one of " + PRODUCTS);
        }
        String expires = opts.getOrDefault("expires", "never");
        if (!expires.equals("never")) {
            LocalDate.parse(expires);
        }
        Map<String, String> fields = new TreeMap<>();
        fields.put("licenseId", opts.getOrDefault("id", UUID.randomUUID().toString()));
        fields.put("product", product);
        fields.put("licensee", required(opts, "licensee"));
        fields.put("issued", LocalDate.now().toString());
        fields.put("expires", expires);
        fields.put("features", opts.getOrDefault("features", "*").replace(" ", ""));

        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(readPem(Path.of(required(opts, "key")))));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(canonical(fields));

        StringBuilder out = new StringBuilder("# ENS Solutions license. Any edit invalidates the signature.\n");
        fields.forEach((k, v) -> out.append(k).append('=').append(escape(v)).append('\n'));
        out.append("signature=").append(Base64.getEncoder().encodeToString(signer.sign())).append('\n');

        String target = opts.getOrDefault("out", product + ".lic");
        Files.writeString(Path.of(target), out.toString());
        System.out.println("Wrote " + target);
    }

    static void verify(String keyPath, String licensePath) throws Exception {
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(readPem(Path.of(keyPath))));
        Properties props = new Properties();
        props.load(new StringReader(Files.readString(Path.of(licensePath))));
        String sig = props.getProperty("signature");
        if (sig == null) {
            throw new IllegalArgumentException("no signature line");
        }
        Map<String, String> fields = new TreeMap<>();
        props.stringPropertyNames().stream().filter(k -> !k.equals("signature"))
                .forEach(k -> fields.put(k, props.getProperty(k).trim()));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(canonical(fields));
        boolean ok = verifier.verify(Base64.getDecoder().decode(sig.trim()));
        System.out.println(ok ? "VALID" : "INVALID SIGNATURE");
        fields.forEach((k, v) -> System.out.println("  " + k + " = " + v));
        if (!ok) {
            System.exit(1);
        }
    }

    static byte[] canonical(Map<String, String> sorted) {
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String escape(String v) {
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            if (c == '\\' || c == '\n' || c == '\r') {
                throw new IllegalArgumentException("values may not contain backslashes or line breaks");
            }
            if (c > 0x7e) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    static byte[] readPem(Path path) throws IOException {
        String body = Files.readString(path).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));
        for (int i = 0; i < rest.size(); i++) {
            String a = rest.get(i);
            if (!a.startsWith("--") || i + 1 >= rest.size()) {
                throw new IllegalArgumentException("expected --name value, got " + a);
            }
            opts.put(a.substring(2), rest.get(++i));
        }
        return opts;
    }

    static String required(Map<String, String> opts, String name) {
        String v = opts.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return v;
    }

    static void usage() {
        System.out.println("""
                usage:
                  java LicenseTool.java keygen --out <prefix>
                  java LicenseTool.java sign   --key <private.pem> --product <product> --licensee <name>
                                               [--expires YYYY-MM-DD|never] [--features "*"|a,b,c]
                                               [--id ID] [--out file.lic]
                  java LicenseTool.java verify --key <public.pem> --license <file.lic>
                products: """ + PRODUCTS);
    }
}
