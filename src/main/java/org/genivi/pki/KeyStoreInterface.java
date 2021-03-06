package org.genivi.pki;
/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 *
 * Copyright (c) 2016 Jaguar Land Rover.
 *
 * This program is licensed under the terms and conditions of the
 * Mozilla Public License, version 2.0. The full text of the
 * Mozilla Public License is at https://www.mozilla.org/MPL/2.0/
 *
 * File:    KeyStoreInterface.java
 * Project: PKI
 *
 * Created by Lilli Szafranski on 8/8/16.
 *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */

import android.content.Context;
import android.os.AsyncTask;
import android.security.KeyPairGeneratorSpec;
import android.util.Log;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.security.Key;

import org.spongycastle.asn1.ASN1ObjectIdentifier;

import org.spongycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.asn1.x509.AlgorithmIdentifier;
import org.spongycastle.asn1.x509.BasicConstraints;
import org.spongycastle.asn1.x509.Extension;
import org.spongycastle.asn1.x509.ExtensionsGenerator;
import org.spongycastle.operator.ContentSigner;
import org.spongycastle.operator.OperatorCreationException;
import org.spongycastle.pkcs.PKCS10CertificationRequest;
import org.spongycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.spongycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.spongycastle.util.encoders.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

/* Code from here: http://stackoverflow.com/a/37898553 */
class KeyStoreInterface
{
    private final static String TAG = "PKI/KeyStoreInterface__";

    private final static String KEYSTORE_CLIENT_ALIAS = "RVI_CLIENT_KEYSTORE_ALIAS";
    private final static String KEYSTORE_SERVER_ALIAS = "RVI_SERVER_KEYSTORE_ALIAS";
    private final static String DEFAULT_SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final static String PEM_HEADER_PATTERN = "-----BEGIN %s-----\n";
    private final static String PEM_FOOTER_PATTERN = "\n-----END %s-----";

    private final static String PEM_CERTIFICATE_SIGNING_REQUEST_HEADER_FOOTER_STRING = "CERTIFICATE REQUEST";
    private final static String PEM_CERTIFICATE_HEADER_FOOTER_STRING                 = "CERTIFICATE";
    private final static String PEM_PUBLIC_KEY_HEADER_FOOTER_STRING                  = "PUBLIC KEY";

    static void generateKeyPairAndCertificateSigningRequest(Context context, PKIManager.CertificateSigningRequestGeneratorListener listener, Integer keySize, Boolean setEncryptionRequired, Date startDate, Date endDate, String principalFormatterPattern, Object... principalFormatterArgs) {
        new CertificateSigningRequestGeneratorTask(listener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, context, keySize, setEncryptionRequired, startDate, endDate, principalFormatterPattern, principalFormatterArgs);
    }

    private static class CertificateSigningRequestGeneratorTask extends AsyncTask<Object, String, Throwable>
    {
        PKIManager.CertificateSigningRequestGeneratorListener mListener = null;

        CertificateSigningRequestGeneratorTask(PKIManager.CertificateSigningRequestGeneratorListener listener) {
            mListener = listener;
        }

        @Override
        protected Throwable doInBackground(Object... params) {
            Context  context                   = (Context)  params[0];
            Integer  keySize                   = (Integer)  params[1];
            Boolean  setEncryptionRequired     = (Boolean)  params[2];
            Date     startDate                 = (Date)     params[3];
            Date     endDate                   = (Date)     params[4];
            String   principalFormatterPattern = (String)   params[5];
            Object[] principalFormatterArgs    = (Object[]) params[6]; // TODO: Test all possibilities thoroughly!

            // TODO: Validate parameters!

            try {
                String certificateSigningRequest =
                        KeyStoreInterface.generateCertificateSigningRequest(context, keySize, setEncryptionRequired, startDate, endDate, principalFormatterPattern, principalFormatterArgs);

                publishProgress(certificateSigningRequest);

            } catch (Exception e) {
                e.printStackTrace();

                return e;
            }

            return null;
        }

        @Override
        protected void onProgressUpdate(String... params) {
            super.onProgressUpdate(params);

            if (mListener != null) mListener.generateCertificateSigningRequestSucceeded(params[0]);
        }

        @Override
        protected void onPostExecute(Throwable result) {
            super.onPostExecute(result);

            if (result != null) mListener.generateCertificateSigningRequestFailed(result);
        }
    }

    private static String generateCertificateSigningRequest(Context context, Integer keySize, Boolean setEncryptionRequired, Date startDate, Date endDate, String principalFormatterPattern, Object... principalFormatterArgs) {

        String   principal = String.format(principalFormatterPattern, principalFormatterArgs);
        KeyStore keyStore  = null;
        KeyPair  keyPair   = null;

        java.security.cert.Certificate cert = null;

        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            try {
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(KEYSTORE_CLIENT_ALIAS, null);

                if (entry != null) {
                    X509Certificate certificate = (X509Certificate) entry.getCertificate();
                    certificate.checkValidity();
                }
            } catch (CertificateExpiredException | CertificateNotYetValidException ce) {
                keyStore.deleteEntry(KEYSTORE_CLIENT_ALIAS);
            }

            if (!keyStore.containsAlias(KEYSTORE_CLIENT_ALIAS)) {

                KeyPairGeneratorSpec spec = null;

                if (setEncryptionRequired) {
                    spec = new KeyPairGeneratorSpec.Builder(context)
                                                .setAlias(KEYSTORE_CLIENT_ALIAS)
                                                .setKeySize(keySize)
                                                .setSubject(new X500Principal(principal))
                                                .setSerialNumber(BigInteger.ONE)
                                                .setStartDate(startDate)
                                                .setEndDate(endDate)
                                                //.setEncryptionRequired()
                                                .build();
                } else {
                    spec = new KeyPairGeneratorSpec.Builder(context)
                                                .setAlias(KEYSTORE_CLIENT_ALIAS)
                                                .setKeySize(keySize)
                                                .setSubject(new X500Principal(principal))
                                                .setSerialNumber(BigInteger.ONE)
                                                .setStartDate(startDate)
                                                .setEndDate(endDate)
                                                //.setEncryptionRequired()
                                                .build();
                }

                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
                generator.initialize(spec);

                keyPair = generator.generateKeyPair();

            } else {
                Key key = keyStore.getKey(KEYSTORE_CLIENT_ALIAS, null);

                cert = keyStore.getCertificate(KEYSTORE_CLIENT_ALIAS);
                PublicKey publicKey = cert.getPublicKey();

                keyPair = new KeyPair(publicKey, (PrivateKey) key);
            }

            PKCS10CertificationRequest csr = generateCSR(keyPair, principal);

            return convertToPem(PEM_CERTIFICATE_SIGNING_REQUEST_HEADER_FOOTER_STRING, csr.getEncoded());

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private static String convertToPem(String headerFooterString, byte [] derCert) {
        String pemHeader = String.format(PEM_HEADER_PATTERN, headerFooterString);
        String pemFooter = String.format(PEM_FOOTER_PATTERN, headerFooterString);

        String encodedCert = new String(Base64.encode(derCert));
        return pemHeader + encodedCert + pemFooter;
    }

    /* Lots of the code below adapted from pedrofb's answer found here: http://stackoverflow.com/a/37898553/955856 */
    private static class JCESigner implements ContentSigner {

        private static Map<String, AlgorithmIdentifier> ALGORITHMS = new HashMap<String, AlgorithmIdentifier>();

        static {
            ALGORITHMS.put("SHA256withRSA".toLowerCase(), new AlgorithmIdentifier(new ASN1ObjectIdentifier("1.2.840.113549.1.1.11")));
            ALGORITHMS.put("SHA1withRSA".toLowerCase(), new AlgorithmIdentifier(new ASN1ObjectIdentifier("1.2.840.113549.1.1.5")));
        }

        private String mAlgorithm;
        private Signature signature;
        private ByteArrayOutputStream outputStream;

        JCESigner(PrivateKey privateKey, String sigAlgorithm) {
            mAlgorithm = sigAlgorithm.toLowerCase();
            try {
                this.outputStream = new ByteArrayOutputStream();
                this.signature = Signature.getInstance(sigAlgorithm);
                this.signature.initSign(privateKey);
            } catch (GeneralSecurityException gse) {
                throw new IllegalArgumentException(gse.getMessage());
            }
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            AlgorithmIdentifier id = ALGORITHMS.get(mAlgorithm);
            if (id == null) {
                throw new IllegalArgumentException("Does not support algorithm: " + mAlgorithm);
            }
            return id;
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public byte[] getSignature() {
            try {
                signature.update(outputStream.toByteArray());
                return signature.sign();
            } catch (GeneralSecurityException gse) {
                gse.printStackTrace();
                return null;
            }
        }
    }

    //Create the certificate signing request (CSR) from private and public keys
    private static PKCS10CertificationRequest generateCSR(KeyPair keyPair, String principal) throws IOException, OperatorCreationException {
        ContentSigner signer = new JCESigner(keyPair.getPrivate(), DEFAULT_SIGNATURE_ALGORITHM);

        PKCS10CertificationRequestBuilder csrBuilder = new JcaPKCS10CertificationRequestBuilder(new X500Name(principal), keyPair.getPublic());
        ExtensionsGenerator extensionsGenerator = new ExtensionsGenerator();

        extensionsGenerator.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        csrBuilder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensionsGenerator.generate());

        PKCS10CertificationRequest csr = csrBuilder.build(signer);

        return csr;
    }

    static String createJwt(Context context, String data) {

        Log.d(TAG, "data: " + data);

        KeyStore keyStore = null;
        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            Key key = keyStore.getKey(KEYSTORE_CLIENT_ALIAS, null);

            return Jwts.builder().setSubject(data).signWith(SignatureAlgorithm.RS256, key).compact();

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    static void deleteAllKeysAndCerts(Context context) {
        KeyStore deviceKeyStore = null;

        try {
            deviceKeyStore = KeyStore.getInstance("AndroidKeyStore");

            deviceKeyStore.load(null);

            if (deviceKeyStore.containsAlias(KEYSTORE_CLIENT_ALIAS)) {
                deviceKeyStore.deleteEntry(KEYSTORE_CLIENT_ALIAS);
            }

            context.deleteFile(KEYSTORE_SERVER_ALIAS);

        } catch (FileNotFoundException ignored) {

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void deleteServerCerts(Context context) {
        try {

            context.deleteFile(KEYSTORE_SERVER_ALIAS);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static KeyStore addDeviceCertToKeyStore(Context context, X509Certificate deviceCert) {
        KeyStore keyStore = null;

        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            X509Certificate[] arr = { deviceCert };

            keyStore.setKeyEntry(KEYSTORE_CLIENT_ALIAS, keyStore.getKey(KEYSTORE_CLIENT_ALIAS, null), null, arr);

            return keyStore;

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    static KeyStore addServerCertToKeyStore(Context context, X509Certificate serverCert) {
        KeyStore keyStore = null;

        try {
            FileOutputStream outputStream = context.openFileOutput(KEYSTORE_SERVER_ALIAS, Context.MODE_PRIVATE);

            keyStore = KeyStore.getInstance("BKS", "BC");
            keyStore.load(null, null);

            keyStore.setCertificateEntry(KEYSTORE_SERVER_ALIAS, serverCert); // TODO: Will using non-unique identifier cause the cert to get overwritten?

            keyStore.store(outputStream, null);

            return keyStore;

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    static Boolean hasValidDeviceCert(Context context) {
        KeyStore keyStore = null;

        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");

            keyStore.load(null);

            if (!keyStore.containsAlias(KEYSTORE_CLIENT_ALIAS)) {
                return false;
            }

            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(KEYSTORE_CLIENT_ALIAS, null);
            X509Certificate certificate = (X509Certificate) entry.getCertificate();

            certificate.checkValidity();

        } catch (FileNotFoundException | CertificateNotYetValidException | CertificateExpiredException ce) {

            return false;
        } catch (Exception e) {
            e.printStackTrace();

            return false;
        }

        return true;
    }

    static Boolean hasValidServerCert(Context context) {
        KeyStore keyStore = null;

        try {
            FileInputStream inputStream = context.openFileInput(KEYSTORE_SERVER_ALIAS);

            keyStore = KeyStore.getInstance("BKS", "BC");
            keyStore.load(inputStream, null);

            if (!keyStore.containsAlias(KEYSTORE_SERVER_ALIAS)) {
                return false;
            }

            KeyStore.TrustedCertificateEntry entry = (KeyStore.TrustedCertificateEntry) keyStore.getEntry(KEYSTORE_SERVER_ALIAS, null);
            X509Certificate certificate = (X509Certificate) entry.getTrustedCertificate();

            certificate.checkValidity();

        } catch (FileNotFoundException | CertificateNotYetValidException | CertificateExpiredException ce) {

            return false;
        } catch (Exception e) {
            e.printStackTrace();

            return false;
        }

        return true;
    }

    static KeyStore getDeviceKeyStore(Context context) {
        KeyStore keyStore = null;

        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return keyStore;
    }

    static KeyStore getServerKeyStore(Context context) {

        KeyStore keyStore = null;

        try {
            FileInputStream inputStream = context.openFileInput(KEYSTORE_SERVER_ALIAS);

            keyStore = KeyStore.getInstance("BKS", "BC");
            keyStore.load(inputStream, null);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return keyStore;
    }

    static String getPublicKey(Context context) {
        KeyStore keyStore = null;

        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            if (!keyStore.containsAlias(KEYSTORE_CLIENT_ALIAS)) {
                return null;
            } else {
                Key key = keyStore.getKey(KEYSTORE_CLIENT_ALIAS, null);

                java.security.cert.Certificate cert = keyStore.getCertificate(KEYSTORE_CLIENT_ALIAS);
                PublicKey publicKey = cert.getPublicKey();

                byte [] bytes = publicKey.getEncoded();

                return convertToPem(PEM_PUBLIC_KEY_HEADER_FOOTER_STRING, bytes);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    static void printPemEncodedDeviceCertificate(Context context) {
        KeyStore keyStore = null;

        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");

            keyStore.load(null);

            if (!keyStore.containsAlias(KEYSTORE_CLIENT_ALIAS)) {
                Log.i(TAG, "Cannot print device certificate; certificate not found.");
                return;
            }

            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(KEYSTORE_CLIENT_ALIAS, null);
            X509Certificate certificate = (X509Certificate) entry.getCertificate();

            Log.d(TAG, convertToPem(PEM_CERTIFICATE_HEADER_FOOTER_STRING, certificate.getEncoded()));

        } catch (Exception e) {
            Log.i(TAG, "Cannot print device certificate:");

            e.printStackTrace();
        }
    }

    static void printPemEncodedServerCertificate(Context context) {
        KeyStore keyStore = null;

        try {
            FileInputStream inputStream = context.openFileInput(KEYSTORE_SERVER_ALIAS);

            keyStore = KeyStore.getInstance("BKS", "BC");
            keyStore.load(inputStream, null);

            if (!keyStore.containsAlias(KEYSTORE_SERVER_ALIAS)) {
                Log.i(TAG, "Cannot print server certificate; certificate not found.");
                return;
            }

            KeyStore.TrustedCertificateEntry entry = (KeyStore.TrustedCertificateEntry) keyStore.getEntry(KEYSTORE_SERVER_ALIAS, null);
            X509Certificate certificate = (X509Certificate) entry.getTrustedCertificate();

            Log.d(TAG, convertToPem(PEM_CERTIFICATE_HEADER_FOOTER_STRING, certificate.getEncoded()));

        } catch (Exception e) {
            Log.i(TAG, "Cannot print device certificate:");

            e.printStackTrace();
        }
    }
}
