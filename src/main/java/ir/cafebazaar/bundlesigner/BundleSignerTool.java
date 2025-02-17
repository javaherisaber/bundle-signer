/*
 *
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications Copyright (C) 2021 Cafe Bazaar
 */

package ir.cafebazaar.bundlesigner;

import com.android.tools.build.bundletool.commands.BuildApksCommand;
import com.android.tools.build.bundletool.device.AdbServer;
import com.android.tools.build.bundletool.device.DdmlibAdbServer;
import com.android.tools.build.bundletool.flags.FlagParser;
import com.android.tools.build.bundletool.flags.ParsedFlags;
import com.android.tools.build.bundletool.model.exceptions.InvalidBundleException;
import ir.cafebazaar.apksig.ApkSigner;
import ir.cafebazaar.apksig.ApkVerifier;
import ir.cafebazaar.apksig.apk.ApkFormatException;
import ir.cafebazaar.apksig.apk.MinSdkVersionException;
import org.conscrypt.OpenSSLProvider;
import shadow.bundletool.com.android.utils.FileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAKey;
import java.security.interfaces.DSAParams;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


/**
 * Command-line tool for signing APKs and for checking whether an APK's signature are expected to
 * verify on Android devices.
 */
public class BundleSignerTool {

    private static final String VERSION = "0.1";
    private static final String HELP_PAGE_GENERAL = "/help.txt";
    private static final String HELP_PAGE_GET_CONTENT_DIGESTS = "/help_get_content_digests.txt";
    private static final String HELP_PAGE_SIGN_BUNDLE = "/help_sign_bundle.txt";
    private static final String HELP_PAGE_VERIFY = "/help_verify.txt";

    private static String TMP_DIR_PATH;

    private static MessageDigest sha256 = null;
    private static MessageDigest sha1 = null;
    private static MessageDigest md5 = null;


    static {
        try {
            Path tmpDirectory = Files.createTempDirectory("bundle_signer");
            TMP_DIR_PATH = tmpDirectory.toAbsolutePath().toString();
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> {
                        try {
                            FileUtils.deleteRecursivelyIfExists(tmpDirectory.toFile());
                        } catch (IOException e) {
                            System.out.println("Warning: Failed to remove tmp dir.");
                            System.exit(8);
                        }
                    }));
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    public static void main(String[] params) throws Exception {
        if ((params.length == 0) || ("--help".equals(params[0])) || ("-h".equals(params[0]))) {
            printUsage(HELP_PAGE_GENERAL);
            return;
        } else if ("--version".equals(params[0])) {
            System.out.println(VERSION);
            return;
        }

        addProviders();

        int exitCode = 0;
        String exitMessage = "";

        String cmd = params[0];
        try {
            switch (cmd) {
                case "genbin":
                    getContentDigest(Arrays.copyOfRange(params, 1, params.length));
                    break;
                case "signbundle":
                    signBundle(Arrays.copyOfRange(params, 1, params.length));
                    break;
                case "verify":
                    verify(Arrays.copyOfRange(params, 1, params.length));
                    break;
                case "help":
                    printUsage(HELP_PAGE_GENERAL);
                    break;
                case "version":
                    System.out.println(VERSION);
                    break;
                default:
                    throw new ParameterException(
                            "Unsupported command: " + cmd + ". See --help for supported commands");
            }
        } catch (ParameterException | OptionsParser.OptionsException e) {
            exitMessage = e.getMessage();
            exitCode = 2;
        } catch (MinSdkVersionException e) {
            exitMessage = "Failed to determine APK's minimum supported platform version"
                    + ". Use --min-sdk-version to override";
            exitCode = 3;
        } catch (InvalidBundleException e) {
            exitMessage = e.getMessage();
            exitCode = 5;
        } catch (BundleToolIOException e) {
            exitMessage = e.getMessage();
            exitCode = 6;

        } catch (ApkFormatException e) {
            exitMessage = e.getMessage();
            exitCode = 7;

        } catch (RuntimeException e) {
            exitMessage = e.getMessage();
            exitCode = 4;
        } finally {
            if (!exitMessage.isEmpty())
                System.err.println(exitMessage);
            System.exit(exitCode);
        }
    }

    /**
     * Adds additional security providers to add support for signature algorithms not covered by
     * the default providers.
     */
    private static void addProviders() {
        try {
            Security.addProvider(new OpenSSLProvider());
        } catch (UnsatisfiedLinkError e) {
            // This is expected if the library path does not include the native conscrypt library;
            // the default providers support all but PSS algorithms.
        }
    }

    private static void getContentDigest(String[] params) throws Exception {
        if (params.length == 0) {
            printUsage(HELP_PAGE_GET_CONTENT_DIGESTS);
            return;
        }

        File finalBin;
        File inputBundle = null;
        String binFilePath = null;
        boolean verbose = false;
        boolean v2SigningEnabled = false;
        boolean v3SigningEnabled = false;
        boolean debuggableApkPermitted = true;
        int minSdkVersion = 1;
        boolean minSdkVersionSpecified = false;
        List<SignerParams> signers = new ArrayList<>(1);
        SignerParams signerParams = new SignerParams();
        List<ProviderInstallSpec> providers = new ArrayList<>();
        ProviderInstallSpec providerParams = new ProviderInstallSpec();
        int maxSdkVersion = Integer.MAX_VALUE;
        OptionsParser optionsParser = new OptionsParser(params);
        String optionName;
        String optionOriginalForm;


        while ((optionName = optionsParser.nextOption()) != null) {
            optionOriginalForm = optionsParser.getOptionOriginalForm();
            switch (optionName) {
                case "help":
                case "h":
                    printUsage(HELP_PAGE_GET_CONTENT_DIGESTS);
                    return;
                case "bin":
                    binFilePath = optionsParser.getRequiredValue("Output file path");
                    break;
                case "bundle":
                    inputBundle = new File(optionsParser.getRequiredValue("Input file name"));
                    break;
                case "min-sdk-version":
                    minSdkVersion = optionsParser.getRequiredIntValue("Mininimum API Level");
                    minSdkVersionSpecified = true;
                    break;
                case "max-sdk-version":
                    maxSdkVersion = optionsParser.getRequiredIntValue("Maximum API Level");
                    break;
                case "v2-signing-enabled":
                    v2SigningEnabled = optionsParser.getOptionalBooleanValue(true);
                    break;
                case "v3-signing-enabled":
                    v3SigningEnabled = optionsParser.getOptionalBooleanValue(true);
                    break;
                case "debuggable-apk-permitted":
                    debuggableApkPermitted = optionsParser.getOptionalBooleanValue(true);
                    break;
                case "v":
                case "verbose":
                    verbose = optionsParser.getOptionalBooleanValue(true);
                    break;
                case "ks":
                    signerParams.setKeystoreFile(optionsParser.getRequiredValue("KeyStore file"));
                    break;
                case "ks-key-alias":
                    signerParams.setKeystoreKeyAlias(
                            optionsParser.getRequiredValue("KeyStore key alias"));
                    break;
                case "ks-pass":
                    signerParams.setKeystorePasswordSpec(
                            optionsParser.getRequiredValue("KeyStore password"));
                    break;
                case "key-pass":
                    signerParams.setKeyPasswordSpec(optionsParser.getRequiredValue("Key password"));
                    break;
                case "pass-encoding":
                    String charsetName =
                            optionsParser.getRequiredValue("Password character encoding");
                    try {
                        signerParams.setPasswordCharset(
                                PasswordRetriever.getCharsetByName(charsetName));
                    } catch (IllegalArgumentException e) {
                        throw new ParameterException(
                                "Unsupported password character encoding requested using"
                                        + " --pass-encoding: " + charsetName);
                    }
                    break;
                case "v1-signer-name":
                    signerParams.setV1SigFileBasename(
                            optionsParser.getRequiredValue("JAR signature file basename"));
                    break;
                case "ks-type":
                    signerParams.setKeystoreType(optionsParser.getRequiredValue("KeyStore type"));
                    break;
                case "ks-provider-name":
                    signerParams.setKeystoreProviderName(
                            optionsParser.getRequiredValue("JCA KeyStore Provider name"));
                    break;
                case "ks-provider-class":
                    signerParams.setKeystoreProviderClass(
                            optionsParser.getRequiredValue("JCA KeyStore Provider class name"));
                    break;
                case "ks-provider-arg":
                    signerParams.setKeystoreProviderArg(
                            optionsParser.getRequiredValue(
                                    "JCA KeyStore Provider constructor argument"));
                    break;
                case "key":
                    signerParams.setKeyFile(optionsParser.getRequiredValue("Private key file"));
                    break;
                case "cert":
                    signerParams.setCertFile(optionsParser.getRequiredValue("Certificate file"));
                    break;
                default:
                    throw new ParameterException(
                            "Unsupported option: " + optionOriginalForm + ". See --help for supported"
                                    + " options.");
            }
        }

        if (inputBundle == null) {
            throw new ParameterException("Missing input Bundle file path");
        }

        if (binFilePath == null) {
            throw new ParameterException("Missing output Bin file path");
        } else {
            String binFileName = inputBundle.getName().split("\\.")[0] + ".bin";
            finalBin = new File(binFilePath + File.separator + binFileName);
        }

        if (!Files.exists(inputBundle.toPath())) {
            throw new ParameterException("Input bundle file does not exist");
        }


        if (!signerParams.isEmpty()) {
            signers.add(signerParams);
        }

        if (!providerParams.isEmpty()) {
            providers.add(providerParams);
        }

        if (signers.isEmpty()) {
            throw new ParameterException("At least one signer must be specified");
        }

        if ((minSdkVersionSpecified) && (minSdkVersion > maxSdkVersion)) {
            throw new ParameterException(
                    "Min API Level (" + minSdkVersion + ") > max API Level (" + maxSdkVersion
                            + ")");
        }

        // Install additional JCA Providers
        for (ProviderInstallSpec providerInstallSpec : providers) {
            providerInstallSpec.installProvider();
        }

        List<ApkSigner.SignerConfig> signerConfigs = new ArrayList<>(signers.size());
        int signerNumber = 0;

        try (PasswordRetriever passwordRetriever = new PasswordRetriever()) {
            for (SignerParams signer : signers) {
                signerNumber++;
                signer.setName("signer #" + signerNumber);
                ApkSigner.SignerConfig signerConfig = getSignerConfig(signer, passwordRetriever);
                if (signerConfig == null) {
                    return;
                }
                signerConfigs.add(signerConfig);
            }
        }

        File keyStore = loadDefaultKeyStore();

        String apksPath = buildApkSet(inputBundle, keyStore, TMP_DIR_PATH, false);
        String universalPath = buildApkSet(inputBundle, keyStore, TMP_DIR_PATH, true);


        File binV1 = new File(TMP_DIR_PATH + File.separator + "binv1");
        File binV2V3 = new File(TMP_DIR_PATH + File.separator + "binv2_v3");
        File tmpBin = new File(TMP_DIR_PATH + File.separator + "tmp_bin");

        signApkSet(v2SigningEnabled, v3SigningEnabled, debuggableApkPermitted, minSdkVersion, minSdkVersionSpecified,
                signerConfigs, apksPath, binV1, binV2V3, tmpBin);

        signApkSet(v2SigningEnabled, v3SigningEnabled, debuggableApkPermitted, minSdkVersion, minSdkVersionSpecified,
                signerConfigs, universalPath, binV1, binV2V3, tmpBin);


        generateFinalBinFile(binV1, binV2V3, finalBin, v2SigningEnabled, v3SigningEnabled);


        if (verbose) {
            System.out.println("Digest content generated");
        }
    }

    private static String buildApkSet(File bundle, File keyStore, String outputPath, boolean universalMode)
            throws BundleToolIOException {

        String bundleName;
        if (universalMode) {
            bundleName = "universal";
        } else {
            bundleName = bundle.getName().split("\\.")[0];
        }
        String apksPath = outputPath + File.separator + bundleName + ".apks";

        ArrayList<String> args = new ArrayList<>();
        args.add("--bundle");
        args.add(bundle.getAbsolutePath());
        args.add("--output");
        args.add(apksPath);
        args.add("--ks");
        args.add(keyStore.getAbsolutePath());
        args.add("--ks-key-alias=default");
        args.add("--ks-pass=pass:defaultpass");
        if (universalMode) {
            args.add("--mode=universal");
        }

        try (AdbServer adbServer = DdmlibAdbServer.getInstance()) {
            final ParsedFlags flags;
            flags = new FlagParser().parse(args.toArray(new String[args.size()]));
            BuildApksCommand.fromFlags(flags, adbServer).execute();
        } catch (UncheckedIOException e) {
            throw new BundleToolIOException(e.getMessage());
        }
        return apksPath;
    }

    private static void appendFiles(File src, File dest) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(src));
        PrintWriter writer = new PrintWriter(new FileWriter(dest, true));

        String line;
        while ((line = reader.readLine()) != null) {
            writer.println(line);
        }

        reader.close();
        writer.close();

    }

    private static void signApkSet(boolean v2SigningEnabled, boolean v3SigningEnabled, boolean debuggableApkPermitted,
                                   int minSdkVersion, boolean minSdkVersionSpecified,
                                   List<ApkSigner.SignerConfig> signerConfigs, String apksPath, File binV1,
                                   File binV2V3, File tmpBin) throws IOException, ApkFormatException, NoSuchAlgorithmException,
            InvalidKeyException, SignatureException, ClassNotFoundException {
        FileInputStream apksStream = new FileInputStream(apksPath);
        ZipInputStream zis = new ZipInputStream(apksStream);
        ZipEntry zipEntry = zis.getNextEntry();

        while (zipEntry != null) {
            if (!zipEntry.getName().contains(".apk")) {
                zipEntry = zis.getNextEntry();
                continue;
            }

            String fileName = zipEntry.getName();
            File apk = new File(TMP_DIR_PATH + File.separator + fileName);
            new File(apk.getParent()).mkdirs();

            FileOutputStream fos = new FileOutputStream(apk);
            int len;
            byte[] buffer = new byte[1024];
            while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            fos.close();

            String apkName = zipEntry.getName().split(".apk")[0] + ".apk";
            apkName = apkName.replace("/", "_");

            calculateSignOfApk(v2SigningEnabled, v3SigningEnabled, debuggableApkPermitted, minSdkVersion,
                    minSdkVersionSpecified, signerConfigs, apkName, binV1, binV2V3, tmpBin, apk);

            zis.closeEntry();
            zipEntry = zis.getNextEntry();
        }
        zis.closeEntry();
        zis.close();
        apksStream.close();
    }

    private static void calculateSignOfApk(boolean v2SigningEnabled, boolean v3SigningEnabled, boolean debuggableApkPermitted,
                                           int minSdkVersion, boolean minSdkVersionSpecified, List<ApkSigner.SignerConfig> signerConfigs,
                                           String apkName, File binV1, File binV2V3, File tmpBin, File apk) throws IOException,
            ApkFormatException, NoSuchAlgorithmException, InvalidKeyException, SignatureException, ClassNotFoundException {

        ApkSigner.Builder apkSignerBuilder =
                new ApkSigner.Builder(new ArrayList<>(0), true)
                        .setInputApk(apk)
                        .setOutputBin(tmpBin)
                        .setOtherSignersSignaturesPreserved(false)
                        .setV1SigningEnabled(true)
                        .setV2SigningEnabled(v2SigningEnabled)
                        .setV3SigningEnabled(v3SigningEnabled)
                        .setDebuggableApkPermitted(debuggableApkPermitted);
        if (minSdkVersionSpecified) {
            apkSignerBuilder.setMinSdkVersion(minSdkVersion);
        }

        ApkSigner apkSigner = apkSignerBuilder.build();
        apkSigner.genV1Bin();

        apkSignerBuilder = new ApkSigner.Builder(signerConfigs)
                .setInputBin(tmpBin)
                .setOutputBin(tmpBin);

        apkSigner = apkSignerBuilder.build();
        apkSigner.signV1();
        appendFiles(tmpBin, binV1);

        if (v2SigningEnabled || v3SigningEnabled) {
            // sign version 1
            File outputApk = new File(TMP_DIR_PATH + File.separator + "out_" + apkName);

            apkSignerBuilder = new ApkSigner.Builder(signerConfigs, true)
                    .setInputApk(apk)
                    .setOutputApk(outputApk)
                    .setInputBin(tmpBin);
            apkSigner = apkSignerBuilder.build();
            apkSigner.addSignV1ToApk();

            // generate v2 v3
            apkSignerBuilder =
                    new ApkSigner.Builder(new ArrayList<>(0), true)
                            .setInputApk(outputApk)
                            .setOutputBin(tmpBin)
                            .setOtherSignersSignaturesPreserved(false)
                            .setV1SigningEnabled(true)
                            .setV2SigningEnabled(v2SigningEnabled)
                            .setV3SigningEnabled(v3SigningEnabled)
                            .setV4SigningEnabled(false)
                            .setForceSourceStampOverwrite(false)
                            .setVerityEnabled(false)
                            .setV4ErrorReportingEnabled(false)
                            .setDebuggableApkPermitted(debuggableApkPermitted)
                            .setSigningCertificateLineage(null);
            apkSigner = apkSignerBuilder.build();
            apkSigner.getContentDigestsV2V3Cafebazaar();

            apkSignerBuilder =
                    new ApkSigner.Builder(signerConfigs)
                            .setInputBin(tmpBin)
                            .setOutputBin(tmpBin)
                            .setOtherSignersSignaturesPreserved(false)
                            .setV1SigningEnabled(true)
                            .setV2SigningEnabled(v2SigningEnabled)
                            .setV3SigningEnabled(v3SigningEnabled)
                            .setV4SigningEnabled(false)
                            .setForceSourceStampOverwrite(false)
                            .setVerityEnabled(false)
                            .setV4ErrorReportingEnabled(false)
                            .setDebuggableApkPermitted(debuggableApkPermitted)
                            .setSigningCertificateLineage(null);
            if (minSdkVersionSpecified) {
                apkSignerBuilder.setMinSdkVersion(minSdkVersion);
            }
            apkSigner = apkSignerBuilder.build();

            apkSigner.signContentDigestsV2V3Cafebazaar();

            appendFiles(tmpBin, binV2V3);
        }
    }

    private static void signBundle(String[] params) throws Exception {
        if (params.length == 0) {
            printUsage(HELP_PAGE_SIGN_BUNDLE);
            return;
        }

        // params
        File bundle = null;
        File binFile = null;
        String outputPath = null;

        // parse params
        OptionsParser optionsParser = new OptionsParser(params);
        String optionName;

        while ((optionName = optionsParser.nextOption()) != null) {

            switch (optionName) {
                case "help":
                case "h":
                    printUsage(HELP_PAGE_SIGN_BUNDLE);
                    return;
                case "bundle":
                    bundle = new File(optionsParser.getRequiredValue("Path to bundle file"));
                    break;
                case "bin":
                    binFile = new File(optionsParser.getRequiredValue("Path to bin file"));
                    break;
                case "out":
                    outputPath = optionsParser.getRequiredValue("Output files path");
                    break;
            }
        }

        if (bundle == null) {
            throw new ParameterException("Missing bundle file");
        }

        if (binFile == null) {
            throw new ParameterException("Missing bin file");
        }

        if (outputPath == null) {
            throw new ParameterException("Missing output path");
        }

        if (!Files.exists(binFile.toPath())) {
            throw new ParameterException("Passed Bin file does not exist");
        }

        if (!Files.exists(bundle.toPath())) {
            throw new ParameterException("Bundle file does not exist");
        }

        // parse bin file
        BufferedReader binReader = new BufferedReader(new FileReader(binFile));
        binReader.readLine();
        String line = binReader.readLine();

        String[] signVersionInfo = line.split(",");
        boolean signV2Enabled = signVersionInfo[0].split(":")[1].trim().equals("true");
        boolean signV3Enabled = signVersionInfo[0].split(":")[1].trim().equals("true");

        Map<String, String> apkSignV1 = new HashMap<>();
        Map<String, String> apkSignV2V3 = new HashMap<>();

        String apkName = null;
        int expectedBinVersion = 1;
        while ((line = binReader.readLine()) != null) {
            if (line.contains(".apk")) {
                apkName = line;
            } else if (expectedBinVersion == 1) {

                apkSignV1.put(apkName, line);
                if (signV2Enabled || signV3Enabled)
                    expectedBinVersion = 2;

            } else {
                apkSignV2V3.put(apkName, line);
                expectedBinVersion = 1;
            }
        }

        File keyStore = loadDefaultKeyStore();
        String apksPath = buildApkSet(bundle, keyStore, TMP_DIR_PATH, false);
        String universalPath = buildApkSet(bundle, keyStore, TMP_DIR_PATH, true);
        extractAndSignApks(outputPath, signV2Enabled, signV3Enabled, apkSignV1, apkSignV2V3, apksPath);
        extractAndSignApks(outputPath, signV2Enabled, signV3Enabled, apkSignV1, apkSignV2V3, universalPath);

        FileUtils.copyFile(new File(apksPath),
                new File(outputPath + File.separator + bundle.getName() + ".apks"));
    }

    private static void extractAndSignApks(String outputPath, boolean signV2Enabled, boolean signV3Enabled,
                                           Map<String, String> apkSignV1, Map<String, String> apkSignV2V3,
                                           String apksPath) throws Exception {
        FileInputStream apksStream = new FileInputStream(apksPath);
        ZipInputStream zis = new ZipInputStream(apksStream);
        ZipEntry zipEntry = zis.getNextEntry();

        while (zipEntry != null) {
            if (!zipEntry.getName().contains(".apk")) {
                zipEntry = zis.getNextEntry();
                continue;
            }

            // write zip entry to apk file
            String fileName = zipEntry.getName();

            File apk = new File(TMP_DIR_PATH + File.separator + fileName);
            new File(apk.getParent()).mkdirs();

            FileOutputStream fos = new FileOutputStream(apk);
            int len;
            byte[] buffer = new byte[1024];
            while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            fos.close();

            // write sign to tmp bin file
            File tmpBin = new File(TMP_DIR_PATH + File.separator + "tmp_bin");
            PrintWriter writer = new PrintWriter(tmpBin);
            writer.println(apk.toPath());
            writer.println(apkSignV1.get(apk.getName()));
            writer.close();

            List<ApkSigner.SignerConfig> signerConfigs = new ArrayList<>(0);

            File v1SignedApk;

            String[] parentDirs = apk.getParent().split(File.separator);
            String apkType = parentDirs[parentDirs.length - 1];

            if (signV2Enabled || signV3Enabled) {
                v1SignedApk = new File(TMP_DIR_PATH + File.separator + "v1_" + apk.getName());
            } else {
                if(apk.getName().contains("universal"))
                    v1SignedApk = new File(outputPath + File.separator + apk.getName());
                else
                    v1SignedApk = new File(outputPath + File.separator + apkType + "_" + apk.getName());
            }

            ApkSigner.Builder apkSignerBuilder =
                    new ApkSigner.Builder(signerConfigs, true)
                            .setInputApk(apk)
                            .setOutputApk(v1SignedApk)
                            .setInputBin(tmpBin);

            ApkSigner apkSigner = apkSignerBuilder.build();
            apkSigner.addSignV1ToApk();

            if (signV2Enabled || signV3Enabled) {

                // write sign to tmp bin file
                tmpBin = new File(TMP_DIR_PATH + File.separator + "tmp_bin");
                writer = new PrintWriter(tmpBin);
                writer.println(apk.toPath());
                writer.println(apkSignV2V3.get(apk.getName()));
                writer.close();

                File V2V3SignedApk;
                if(apk.getName().contains("universal"))
                    V2V3SignedApk = new File(outputPath + File.separator  + apk.getName());
                else
                    V2V3SignedApk = new File(outputPath + File.separator + apkType + "_" + apk.getName());

                apkSignerBuilder =
                        new ApkSigner.Builder(new ArrayList<>(0), true)
                                .setInputApk(v1SignedApk)
                                .setOutputApk(V2V3SignedApk)
                                .setInputBin(tmpBin)
                                .setOtherSignersSignaturesPreserved(false)
                                .setV1SigningEnabled(false)
                                .setV2SigningEnabled(signV2Enabled)
                                .setV3SigningEnabled(signV3Enabled)
                                .setV4SigningEnabled(false)
                                .setForceSourceStampOverwrite(false)
                                .setVerityEnabled(false)
                                .setV4ErrorReportingEnabled(false)
                                .setDebuggableApkPermitted(true)
                                .setSigningCertificateLineage(null);

                apkSigner = apkSignerBuilder.build();

                apkSigner.addSignedContentDigestsV2V3Cafebazaar();

            }

            zis.closeEntry();
            zipEntry = zis.getNextEntry();
        }
        zis.closeEntry();
        zis.close();
        apksStream.close();
    }

    private static File loadDefaultKeyStore() throws IOException {
        String keyStoreName = "default.keystore";
        InputStream inputStream = ApkSigner.class.getClassLoader().getResourceAsStream(keyStoreName);
        File keyStore = new File(BundleSignerTool.TMP_DIR_PATH + File.separator + keyStoreName);
        Files.copy(inputStream, keyStore.toPath());
        return keyStore;
    }

    private static void generateFinalBinFile(File binV1, File binV2V3, File finalBin, boolean v2Enabled,
                                             boolean v3Enabled) throws IOException {
        PrintWriter writer = new PrintWriter(new FileWriter(finalBin));
        writer.println("version: 0.1.4");
        writer.println("v2:" + v2Enabled + ",v3:" + v3Enabled);
        if (!v2Enabled && !v3Enabled) {
            BufferedReader reader = new BufferedReader(new FileReader(binV1));

            String line;
            int lineCounter = 0;
            String apkName;
            while ((line = reader.readLine()) != null) {

                if (lineCounter == 0) {
                    File apk = new File(line);
                    apkName = apk.getName();
                    writer.println(apkName);
                } else {
                    writer.println(line);
                }
                lineCounter = (lineCounter + 1) % 2;
            }

            reader.close();
            writer.close();
        } else {
            BufferedReader readerV1 = new BufferedReader(new FileReader(binV1));
            BufferedReader readerV2V3 = new BufferedReader(new FileReader(binV2V3));

            String lineV1;
            String lineV2V3;
            int lineCounter = 0;
            String apkName;

            while ((lineV1 = readerV1.readLine()) != null && (lineV2V3 = readerV2V3.readLine()) != null) {
                if (lineCounter == 0) {
                    File apk = new File(lineV1);
                    apkName = apk.getName();
                    writer.println(apkName);
                } else {
                    writer.println(lineV1);
                    writer.println(lineV2V3);
                }

                lineCounter = (lineCounter + 1) % 2;
            }

            readerV1.close();
            readerV2V3.close();
            writer.close();
        }
    }

    private static ApkSigner.SignerConfig getSignerConfig(
            SignerParams signer, PasswordRetriever passwordRetriever) {
        try {
            signer.loadPrivateKeyAndCerts(passwordRetriever);
        } catch (ParameterException e) {
            System.err.println(
                    "Failed to load signer \"" + signer.getName() + "\": " + e.getMessage());
            System.exit(2);
            return null;
        } catch (Exception e) {
            System.err.println("Failed to load signer \"" + signer.getName() + "\"");
            e.printStackTrace();
            System.exit(2);
            return null;
        }
        String v1SigBasename;
        if (signer.getV1SigFileBasename() != null) {
            v1SigBasename = signer.getV1SigFileBasename();
        } else if (signer.getKeystoreKeyAlias() != null) {
            v1SigBasename = signer.getKeystoreKeyAlias();
        } else if (signer.getKeyFile() != null) {
            String keyFileName = new File(signer.getKeyFile()).getName();
            int delimiterIndex = keyFileName.indexOf('.');
            if (delimiterIndex == -1) {
                v1SigBasename = keyFileName;
            } else {
                v1SigBasename = keyFileName.substring(0, delimiterIndex);
            }
        } else {
            throw new RuntimeException("Neither KeyStore key alias nor private key file available");
        }
        ApkSigner.SignerConfig signerConfig =
                new ApkSigner.SignerConfig.Builder(
                        v1SigBasename, signer.getPrivateKey(), signer.getCerts())
                        .build();
        return signerConfig;
    }

    private static void verify(String[] params) throws Exception {
        if (params.length == 0) {
            printUsage(HELP_PAGE_VERIFY);
            return;
        }

        File inputApk = null;
        int minSdkVersion = 1;
        boolean minSdkVersionSpecified = false;
        int maxSdkVersion = Integer.MAX_VALUE;
        boolean maxSdkVersionSpecified = false;
        boolean printCerts = false;
        boolean verbose = false;
        boolean warningsTreatedAsErrors = false;
        boolean verifySourceStamp = false;
        File v4SignatureFile = null;
        OptionsParser optionsParser = new OptionsParser(params);
        String optionName;
        String optionOriginalForm = null;
        String sourceCertDigest = null;
        while ((optionName = optionsParser.nextOption()) != null) {
            optionOriginalForm = optionsParser.getOptionOriginalForm();
            if ("min-sdk-version".equals(optionName)) {
                minSdkVersion = optionsParser.getRequiredIntValue("Mininimum API Level");
                minSdkVersionSpecified = true;
            } else if ("max-sdk-version".equals(optionName)) {
                maxSdkVersion = optionsParser.getRequiredIntValue("Maximum API Level");
                maxSdkVersionSpecified = true;
            } else if ("print-certs".equals(optionName)) {
                printCerts = optionsParser.getOptionalBooleanValue(true);
            } else if (("v".equals(optionName)) || ("verbose".equals(optionName))) {
                verbose = optionsParser.getOptionalBooleanValue(true);
            } else if ("Werr".equals(optionName)) {
                warningsTreatedAsErrors = optionsParser.getOptionalBooleanValue(true);
            } else if (("help".equals(optionName)) || ("h".equals(optionName))) {
                printUsage(HELP_PAGE_VERIFY);
                return;
            } else if ("v4-signature-file".equals(optionName)) {
                v4SignatureFile = new File(optionsParser.getRequiredValue(
                        "Input V4 Signature File"));
            } else if ("in".equals(optionName)) {
                inputApk = new File(optionsParser.getRequiredValue("Input APK file"));
            } else if ("verify-source-stamp".equals(optionName)) {
                verifySourceStamp = optionsParser.getOptionalBooleanValue(true);
            } else if ("stamp-cert-digest".equals(optionName)) {
                sourceCertDigest = optionsParser.getRequiredValue(
                        "Expected source stamp certificate digest");
            } else {
                throw new ParameterException(
                        "Unsupported option: " + optionOriginalForm + ". See --help for supported"
                                + " options.");
            }
        }
        params = optionsParser.getRemainingParams();

        if (inputApk != null) {
            // Input APK has been specified in preceding parameters. We don't expect any more
            // parameters.
            if (params.length > 0) {
                throw new ParameterException(
                        "Unexpected parameter(s) after " + optionOriginalForm + ": " + params[0]);
            }
        } else {
            // Input APK has not been specified in preceding parameters. The next parameter is
            // supposed to be the input APK.
            if (params.length < 1) {
                throw new ParameterException("Missing APK");
            } else if (params.length > 1) {
                throw new ParameterException(
                        "Unexpected parameter(s) after APK (" + params[1] + ")");
            }
            inputApk = new File(params[0]);
        }

        if ((minSdkVersionSpecified) && (maxSdkVersionSpecified)
                && (minSdkVersion > maxSdkVersion)) {
            throw new ParameterException(
                    "Min API Level (" + minSdkVersion + ") > max API Level (" + maxSdkVersion
                            + ")");
        }

        ApkVerifier.Builder apkVerifierBuilder = new ApkVerifier.Builder(inputApk);
        if (minSdkVersionSpecified) {
            apkVerifierBuilder.setMinCheckedPlatformVersion(minSdkVersion);
        }
        if (maxSdkVersionSpecified) {
            apkVerifierBuilder.setMaxCheckedPlatformVersion(maxSdkVersion);
        }
        if (v4SignatureFile != null) {
            if (!v4SignatureFile.exists()) {
                throw new ParameterException("V4 signature file does not exist: "
                        + v4SignatureFile.getCanonicalPath());
            }
            apkVerifierBuilder.setV4SignatureFile(v4SignatureFile);
        }

        ApkVerifier apkVerifier = apkVerifierBuilder.build();
        ApkVerifier.Result result;
        try {
            result = verifySourceStamp
                    ? apkVerifier.verifySourceStamp(sourceCertDigest)
                    : apkVerifier.verify();
        } catch (MinSdkVersionException e) {
            String msg = e.getMessage();
            if (!msg.endsWith(".")) {
                msg += '.';
            }
            throw new MinSdkVersionException(
                    "Failed to determine APK's minimum supported platform version"
                            + ". Use --min-sdk-version to override",
                    e);
        }

        boolean verified = result.isVerified();
        ApkVerifier.Result.SourceStampInfo sourceStampInfo = result.getSourceStampInfo();
        boolean warningsEncountered = false;
        if (verified) {
            List<X509Certificate> signerCerts = result.getSignerCertificates();
            if (verbose) {
                System.out.println("Verifies");
                System.out.println(
                        "Verified using v1 scheme (JAR signing): "
                                + result.isVerifiedUsingV1Scheme());
                System.out.println(
                        "Verified using v2 scheme (APK Signature Scheme v2): "
                                + result.isVerifiedUsingV2Scheme());
                System.out.println(
                        "Verified using v3 scheme (APK Signature Scheme v3): "
                                + result.isVerifiedUsingV3Scheme());
                System.out.println(
                        "Verified using v4 scheme (APK Signature Scheme v4): "
                                + result.isVerifiedUsingV4Scheme());
                System.out.println("Verified for SourceStamp: " + result.isSourceStampVerified());
                if (!verifySourceStamp) {
                    System.out.println("Number of signers: " + signerCerts.size());
                }
            }
            if (printCerts) {
                int signerNumber = 0;
                for (X509Certificate signerCert : signerCerts) {
                    signerNumber++;
                    printCertificate(signerCert, "Signer #" + signerNumber, verbose);
                }
                if (sourceStampInfo != null) {
                    printCertificate(sourceStampInfo.getCertificate(), "Source Stamp Signer",
                            verbose);
                }
            }
        } else {
            System.err.println("DOES NOT VERIFY");
        }

        for (ApkVerifier.IssueWithParams error : result.getErrors()) {
            System.err.println("ERROR: " + error);
        }

        @SuppressWarnings("resource") // false positive -- this resource is not opened here
        PrintStream warningsOut = warningsTreatedAsErrors ? System.err : System.out;
        for (ApkVerifier.IssueWithParams warning : result.getWarnings()) {
            warningsEncountered = true;
            warningsOut.println("WARNING: " + warning);
        }
        for (ApkVerifier.Result.V1SchemeSignerInfo signer : result.getV1SchemeSigners()) {
            String signerName = signer.getName();
            for (ApkVerifier.IssueWithParams error : signer.getErrors()) {
                System.err.println("ERROR: JAR signer " + signerName + ": " + error);
            }
            for (ApkVerifier.IssueWithParams warning : signer.getWarnings()) {
                warningsEncountered = true;
                warningsOut.println("WARNING: JAR signer " + signerName + ": " + warning);
            }
        }
        for (ApkVerifier.Result.V2SchemeSignerInfo signer : result.getV2SchemeSigners()) {
            String signerName = "signer #" + (signer.getIndex() + 1);
            for (ApkVerifier.IssueWithParams error : signer.getErrors()) {
                System.err.println(
                        "ERROR: APK Signature Scheme v2 " + signerName + ": " + error);
            }
            for (ApkVerifier.IssueWithParams warning : signer.getWarnings()) {
                warningsEncountered = true;
                warningsOut.println(
                        "WARNING: APK Signature Scheme v2 " + signerName + ": " + warning);
            }
        }
        for (ApkVerifier.Result.V3SchemeSignerInfo signer : result.getV3SchemeSigners()) {
            String signerName = "signer #" + (signer.getIndex() + 1);
            for (ApkVerifier.IssueWithParams error : signer.getErrors()) {
                System.err.println(
                        "ERROR: APK Signature Scheme v3 " + signerName + ": " + error);
            }
            for (ApkVerifier.IssueWithParams warning : signer.getWarnings()) {
                warningsEncountered = true;
                warningsOut.println(
                        "WARNING: APK Signature Scheme v3 " + signerName + ": " + warning);
            }
        }

        if (sourceStampInfo != null) {
            for (ApkVerifier.IssueWithParams error : sourceStampInfo.getErrors()) {
                System.err.println("ERROR: SourceStamp: " + error);
            }
            for (ApkVerifier.IssueWithParams warning : sourceStampInfo.getWarnings()) {
                warningsOut.println("WARNING: SourceStamp: " + warning);
            }
        }

        if (!verified) {
            System.exit(1);
            return;
        }
        if ((warningsTreatedAsErrors) && (warningsEncountered)) {
            System.exit(1);
            return;
        }
    }

    private static void printUsage(String page) {
        try (BufferedReader in =
                     new BufferedReader(
                             new InputStreamReader(
                                     BundleSignerTool.class.getResourceAsStream(page),
                                     StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + page + " resource");
        }
    }

    /**
     * Prints details from the provided certificate to stdout.
     *
     * @param cert    the certificate to be displayed.
     * @param name    the name to be used to identify the certificate.
     * @param verbose boolean indicating whether public key details from the certificate should be
     *                displayed.
     * @throws NoSuchAlgorithmException     if an instance of MD5, SHA-1, or SHA-256 cannot be
     *                                      obtained.
     * @throws CertificateEncodingException if an error is encountered when encoding the
     *                                      certificate.
     */
    public static void printCertificate(X509Certificate cert, String name, boolean verbose)
            throws NoSuchAlgorithmException, CertificateEncodingException {
        if (cert == null) {
            throw new NullPointerException("cert == null");
        }
        if (sha256 == null || sha1 == null || md5 == null) {
            sha256 = MessageDigest.getInstance("SHA-256");
            sha1 = MessageDigest.getInstance("SHA-1");
            md5 = MessageDigest.getInstance("MD5");
        }
        System.out.println(name + " certificate DN: " + cert.getSubjectDN());
        byte[] encodedCert = cert.getEncoded();
        System.out.println(name + " certificate SHA-256 digest: " + HexEncoding.encode(
                sha256.digest(encodedCert)));
        System.out.println(name + " certificate SHA-1 digest: " + HexEncoding.encode(
                sha1.digest(encodedCert)));
        System.out.println(
                name + " certificate MD5 digest: " + HexEncoding.encode(md5.digest(encodedCert)));
        if (verbose) {
            PublicKey publicKey = cert.getPublicKey();
            System.out.println(name + " key algorithm: " + publicKey.getAlgorithm());
            int keySize = -1;
            if (publicKey instanceof RSAKey) {
                keySize = ((RSAKey) publicKey).getModulus().bitLength();
            } else if (publicKey instanceof ECKey) {
                keySize = ((ECKey) publicKey).getParams()
                        .getOrder().bitLength();
            } else if (publicKey instanceof DSAKey) {
                // DSA parameters may be inherited from the certificate. We
                // don't handle this case at the moment.
                DSAParams dsaParams = ((DSAKey) publicKey).getParams();
                if (dsaParams != null) {
                    keySize = dsaParams.getP().bitLength();
                }
            }
            System.out.println(
                    name + " key size (bits): " + ((keySize != -1) ? String.valueOf(keySize)
                            : "n/a"));
            byte[] encodedKey = publicKey.getEncoded();
            System.out.println(name + " public key SHA-256 digest: " + HexEncoding.encode(
                    sha256.digest(encodedKey)));
            System.out.println(name + " public key SHA-1 digest: " + HexEncoding.encode(
                    sha1.digest(encodedKey)));
            System.out.println(
                    name + " public key MD5 digest: " + HexEncoding.encode(md5.digest(encodedKey)));
        }
    }

    private static class ProviderInstallSpec {
        String className;
        String constructorParam;
        Integer position;

        private boolean isEmpty() {
            return (className == null) && (constructorParam == null) && (position == null);
        }

        private void installProvider() throws Exception {
            if (className == null) {
                throw new ParameterException(
                        "JCA Provider class name (--provider-class) must be specified");
            }

            Class<?> providerClass = Class.forName(className);
            if (!Provider.class.isAssignableFrom(providerClass)) {
                throw new ParameterException(
                        "JCA Provider class " + providerClass + " not subclass of "
                                + Provider.class.getName());
            }
            Provider provider;
            if (constructorParam != null) {
                try {
                    // Single-arg Provider constructor
                    provider =
                            (Provider) providerClass.getConstructor(String.class)
                                    .newInstance(constructorParam);
                } catch (NoSuchMethodException e) {
                    // Starting from JDK 9 the single-arg constructor accepting the configuration
                    // has been replaced by a configure(String) method to be invoked after
                    // instantiating the Provider with the no-arg constructor.
                    provider = (Provider) providerClass.getConstructor().newInstance();
                    provider = (Provider) providerClass.getMethod("configure", String.class)
                            .invoke(provider, constructorParam);
                }
            } else {
                // No-arg Provider constructor
                provider = (Provider) providerClass.getConstructor().newInstance();
            }

            if (position == null) {
                Security.addProvider(provider);
            } else {
                Security.insertProviderAt(provider, position);
            }
        }

    }

}
