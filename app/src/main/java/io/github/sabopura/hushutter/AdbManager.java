package io.github.sabopura.hushutter;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;

/** 自分自身の adbd（ワイヤレスデバッグ）に接続するためのクライアント。鍵はアプリの内部領域に保存する。 */
final class AdbManager extends AbsAdbConnectionManager {
    private static final String END_MARKER = "__HUSHUTTER_END__";
    private static AdbManager sInstance;

    static synchronized AdbManager get(Context context) throws Exception {
        if (sInstance == null) sInstance = new AdbManager(context.getApplicationContext());
        return sInstance;
    }

    private final PrivateKey mPrivateKey;
    private final Certificate mCertificate;

    private AdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        // 既定では応答を無制限に待つため、返事がないと止まったままになる
        setTimeout(20, TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
        File keyFile = new File(context.getFilesDir(), "adb.key");
        File certFile = new File(context.getFilesDir(), "adb.crt");
        if (keyFile.exists() && certFile.exists()) {
            mPrivateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            mCertificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            mPrivateKey = pair.getPrivate();
            mCertificate = createCertificate(pair);
            write(keyFile, mPrivateKey.getEncoded());
            write(certFile, mCertificate.getEncoded());
        }
    }

    private static Certificate createCertificate(KeyPair pair) throws Exception {
        X500Name name = new X500Name("CN=Hushutter");
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + 30L * 365 * 24 * 60 * 60 * 1000);
        BigInteger serial = BigInteger.valueOf(new SecureRandom().nextInt() & Integer.MAX_VALUE);
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, pair.getPublic());
        return new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    /** 接続する。時間内に接続できなければ例外にする。 */
    @Override
    public boolean connect(@NonNull String host, int port)
            throws IOException, InterruptedException, AdbPairingRequiredException {
        if (!super.connect(host, port) && !isConnected()) {
            throw new IOException("No response from adb (" + host + ":" + port + ")");
        }
        return true;
    }

    /**
     * shell コマンドを実行し、出力（標準出力と標準エラー）を返す。
     * <p>
     * libadb は、出力を読み切る前に adbd がストリームを閉じると、次の read() で永久に待ってしまう。
     * そのため終了を待たず、コマンドの最後に出す目印を受け取った時点で読むのをやめる。
     */
    String exec(String command) throws Exception {
        AdbStream stream = openStream("shell:" + command + " 2>&1; echo " + END_MARKER);
        try {
            InputStream in = stream.openInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (true) {
                String text = out.toString(StandardCharsets.UTF_8.name());
                int end = text.indexOf(END_MARKER);
                if (end >= 0) return text.substring(0, end).trim();
                int n = in.read(buffer);
                if (n == -1) return text.trim();
                out.write(buffer, 0, n);
            }
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "Hushutter";
    }
}
