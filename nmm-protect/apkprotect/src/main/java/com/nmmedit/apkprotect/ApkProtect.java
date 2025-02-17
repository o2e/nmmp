package com.nmmedit.apkprotect;

import com.nmmedit.apkprotect.andres.AxmlEdit;
import com.nmmedit.apkprotect.data.Prefs;
import com.nmmedit.apkprotect.dex2c.Dex2c;
import com.nmmedit.apkprotect.dex2c.DexConfig;
import com.nmmedit.apkprotect.dex2c.GlobalDexConfig;
import com.nmmedit.apkprotect.dex2c.converter.instructionrewriter.InstructionRewriter;
import com.nmmedit.apkprotect.dex2c.converter.structs.RegisterNativesUtilClassDef;
import com.nmmedit.apkprotect.dex2c.filters.ClassAndMethodFilter;
import com.nmmedit.apkprotect.sign.ApkVerifyCodeGenerator;
import com.nmmedit.apkprotect.util.ApkUtils;
import com.nmmedit.apkprotect.util.FileUtils;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.*;

public class ApkProtect {

    public static final String ANDROID_MANIFEST_XML = "AndroidManifest.xml";
    public static final String ANDROID_APP_APPLICATION = "android.app.Application";
    private final ApkFolders apkFolders;
    private final InstructionRewriter instructionRewriter;
    private final ApkVerifyCodeGenerator apkVerifyCodeGenerator;
    private final ClassAndMethodFilter filter;

    private ApkProtect(ApkFolders apkFolders,
                       InstructionRewriter instructionRewriter,
                       ApkVerifyCodeGenerator apkVerifyCodeGenerator,
                       ClassAndMethodFilter filter
    ) {
        this.apkFolders = apkFolders;

        this.instructionRewriter = instructionRewriter;

        this.apkVerifyCodeGenerator = apkVerifyCodeGenerator;
        this.filter = filter;

    }

    public void run() throws IOException {
        final File apkFile = apkFolders.getInApk();
        final File zipExtractDir = apkFolders.getZipExtractTempDir();

        try {
            byte[] manifestBytes = ApkUtils.getFile(apkFile, ANDROID_MANIFEST_XML);
            if (manifestBytes == null) {
                //错误apk文件
                throw new RuntimeException("Not is apk");
            }
            String applicationClass = AxmlEdit.getApplicationName(manifestBytes);
            if (applicationClass.equals("")) {
                applicationClass = ANDROID_APP_APPLICATION;
            }
            final String packageName = AxmlEdit.getPackageName(manifestBytes);

            //生成一些需要改变的c代码(随机opcode后的头文件及apk验证代码等)
            generateCSources(packageName);

            //解压得到所有classesN.dex
            List<File> files = getClassesFiles(apkFile, zipExtractDir);
            if (files.isEmpty()) {
                throw new RuntimeException("No classes.dex");
            }
            //globalConfig里面configs顺序和classesN.dex文件列表一样
            final GlobalDexConfig globalConfig = Dex2c.handleDexes(files,
                    filter,
                    instructionRewriter,
                    apkFolders.getCodeGeneratedDir());


            //需要放在主dex里的类
            final Set<String> mainDexClassTypeSet = new HashSet<>();
            //todo 可能需要通过外部配置来保留主dex需要的class

            //application class 继承关系
            final List<String> appClassTypes = getApplicationClassesFromMainDex(globalConfig, applicationClass);

            mainDexClassTypeSet.addAll(appClassTypes);

            //在处理过的class的静态初始化方法里插入调用注册本地方法的指令
            //static {
            //    NativeUtils.initClass(0);
            //}

            final ArrayList<File> outDexFiles = injectInstructionAndWriteToFile(
                    globalConfig,
                    mainDexClassTypeSet,
                    60000,
                    apkFolders.getTempDexDir());

            //Application对应的
            final String appName;
            if (appClassTypes.isEmpty()) {
                appName = ANDROID_APP_APPLICATION;
            } else {
                final String s = appClassTypes.get(appClassTypes.size() - 1);
                appName = s.substring(1, s.length() - 1).replace('/', '.');
            }


            //处理AndroidManifest.xml文件
            final File newManifestFile = handleApplicationClass(
                    manifestBytes,
                    appName,
                    outDexFiles,
                    globalConfig,
                    apkFolders.getOutRootDir());

            final Map<String, List<File>> nativeLibs = generateNativeLibs(apkFolders);


            try (
                    final ZipInputStream zipInput = new ZipInputStream(new FileInputStream(apkFile));
                    final ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(apkFolders.getOutputApk()));
            ) {
                zipCopy(zipInput, apkFolders.getZipExtractTempDir(), zipOutput);

                //add AndroidManifest.xml
                addFileToZip(zipOutput, newManifestFile, new ZipEntry(ANDROID_MANIFEST_XML));

                //add classesX.dex
                for (File file : outDexFiles) {
                    final ZipEntry zipEntry = new ZipEntry(file.getName());

                    addFileToZip(zipOutput, file, zipEntry);
                }

                //add native libs
                for (Map.Entry<String, List<File>> entry : nativeLibs.entrySet()) {
                    final String abi = entry.getKey();
                    for (File file : entry.getValue()) {
                        final ZipEntry zipEntry = new ZipEntry("lib/" + abi + "/" + file.getName());

                        addFileToZip(zipOutput, file, zipEntry);
                    }
                }
            }
        } finally {
            //删除解压缓存目录
            deleteFile(zipExtractDir);
        }
    }

    private void addFileToZip(ZipOutputStream zipOutput, File file, ZipEntry zipEntry) throws IOException {
        zipOutput.putNextEntry(zipEntry);
        try (FileInputStream input = new FileInputStream(file);) {
            FileUtils.copyStream(input, zipOutput);
        }
        zipOutput.closeEntry();
    }

    private static Map<String, List<File>> generateNativeLibs(ApkFolders apkFolders) throws IOException {
        String cmakePath = System.getenv("CMAKE_PATH");
        if (isEmpty(cmakePath)) {
            System.err.println("No CMAKE_PATH");
            cmakePath = Prefs.cmakePath();
        }
        String sdkHome = System.getenv("ANDROID_SDK_HOME");
        if (isEmpty(sdkHome)) {
            sdkHome = Prefs.sdkPath();
            System.err.println("No ANDROID_SDK_HOME. Default is " + sdkHome);
        }
        String ndkHome = System.getenv("ANDROID_NDK_HOME");
        if (isEmpty(ndkHome)) {
            ndkHome = Prefs.ndkPath();
            System.err.println("No ANDROID_NDK_HOME. Default is " + ndkHome);
        }

        final File outRootDir = apkFolders.getOutRootDir();
        final File apkFile = apkFolders.getInApk();

        final Map<String, List<File>> allLibs = new HashMap<>();

        final List<String> abis = getAbis(apkFile);
        for (String abi : abis) {
            final BuildNativeLib.CMakeOptions cmakeOptions = new BuildNativeLib.CMakeOptions(cmakePath,
                    sdkHome,
                    ndkHome, 21,
                    outRootDir.getAbsolutePath(),
                    BuildNativeLib.CMakeOptions.BuildType.RELEASE,
                    abi);

            //删除上次创建的目录
            deleteFile(new File(cmakeOptions.getBuildPath()));

            final List<File> files = BuildNativeLib.build(cmakeOptions);
            allLibs.put(abi, files);
        }
        return allLibs;

    }

    private static boolean isEmpty(String cmakePath) {
        return cmakePath == null || "".equals(cmakePath);
    }

    //根据apk里文件得到abi，如果没有本地库则返回所有
    private static List<String> getAbis(File apk) throws IOException {
        final Pattern pattern = Pattern.compile("lib/(.*)/.*\\.so");
        final ZipFile zipFile = new ZipFile(apk);
        final Enumeration<? extends ZipEntry> entries = zipFile.entries();
        Set<String> abis = new HashSet<>();
        while (entries.hasMoreElements()) {
            final ZipEntry entry = entries.nextElement();
            final Matcher matcher = pattern.matcher(entry.getName());
            if (matcher.matches()) {
                abis.add(matcher.group(1));
            }
        }
        //不支持armeabi，可能还要删除mips相关
        abis.remove("armeabi");
        if (abis.isEmpty()) {
            //默认只生成armeabi-v7a
            ArrayList<String> abi = new ArrayList<>();
            if (Prefs.isArm()) {
                abi.add("armeabi-v7a");
            }
            if (Prefs.isArm64()) {
                abi.add("arm64-v8a");
            }

            if (Prefs.isX86()) {
                abi.add("x86");
            }

            if (Prefs.isX64()) {
                abi.add("x86_64");
            }
            return abi;
        }
        return new ArrayList<>(abis);
    }

    private void generateCSources(String packageName) throws IOException {
        final File vmsrcFile = new File(FileUtils.getHomePath(), "tools/vmsrc.zip");
        if (!vmsrcFile.exists()) {
            vmsrcFile.getParentFile().mkdirs();
            //copy vmsrc.zip to external directory
            try (
                    InputStream inputStream = ApkProtect.class.getResourceAsStream("/vmsrc.zip");
                    final FileOutputStream outputStream = new FileOutputStream(vmsrcFile);
            ) {
                FileUtils.copyStream(inputStream, outputStream);
            }
        }
        final List<File> cSources = ApkUtils.extractFiles(vmsrcFile, ".*", apkFolders.getDex2cSrcDir());

        //处理指令及apk验证,生成新的c文件
        for (File source : cSources) {
            if (source.getName().endsWith("DexOpcodes.h")) {
                //根据指令重写规则重新生成DexOpcodes.h文件
                writeOpcodeHeaderFile(source, instructionRewriter);
            } else if (source.getName().endsWith("apk_verifier.c")) {
                //根据公钥数据生成签名验证代码
                writeApkVerifierFile(packageName, source, apkVerifyCodeGenerator);
            }
        }
    }

    private static List<File> getClassesFiles(File apkFile, File zipExtractDir) throws IOException {
        List<File> files = ApkUtils.extractFiles(apkFile, "classes(\\d+)*\\.dex", zipExtractDir);
        //根据classes索引大小排序
        files.sort((file, t1) -> {
            final String numb = file.getName().replace("classes", "").replace(".dex", "");
            final String numb2 = t1.getName().replace("classes", "").replace(".dex", "");
            int n, n2;
            if ("".equals(numb)) {
                n = 0;
            } else {
                n = Integer.parseInt(numb);
            }
            if ("".equals(numb2)) {
                n2 = 0;
            } else {
                n2 = Integer.parseInt(numb2);
            }
            return n - n2;
        });
        return files;
    }

    //根据指令重写规则,重新生成新的opcode
    private static void writeOpcodeHeaderFile(File source, InstructionRewriter instructionRewriter) throws IOException {
        final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8));

        final String collect = bufferedReader.lines().collect(Collectors.joining("\n"));
        final Pattern opcodePattern = Pattern.compile(
                "enum Opcode \\{.*?};",
                Pattern.MULTILINE | Pattern.DOTALL);
        final StringWriter opcodeContent = new StringWriter();
        final StringWriter gotoTableContent = new StringWriter();
        instructionRewriter.generateConfig(opcodeContent, gotoTableContent);
        String headerContent = opcodePattern
                .matcher(collect)
                .replaceAll(String.format("enum Opcode {\n%s};\n", opcodeContent.toString()));

        //根据opcode生成goto表
        final Pattern patternGotoTable = Pattern.compile(
                "_name\\[kNumPackedOpcodes\\] = \\{.*?};",
                Pattern.MULTILINE | Pattern.DOTALL);
        headerContent = patternGotoTable
                .matcher(headerContent)
                .replaceAll(String.format("_name[kNumPackedOpcodes] = {        \\\\\n%s};\n", gotoTableContent));

        try (FileWriter fileWriter = new FileWriter(source)) {
            fileWriter.write(headerContent);
        }
    }

    //读取证书信息,并把公钥写入签名验证文件里,运行时对apk进行签名校验
    private static void writeApkVerifierFile(String packageName, File source, ApkVerifyCodeGenerator apkVerifyCodeGenerator) throws IOException {
        if (apkVerifyCodeGenerator == null) {
            return;
        }
        final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8));

        final String lines = bufferedReader.lines().collect(Collectors.joining("\n"));
        String dataPlaceHolder = "#define publicKeyPlaceHolder";

        String content = lines.replaceAll(dataPlaceHolder, dataPlaceHolder + apkVerifyCodeGenerator.generate());
        content = content.replaceAll("(#define PACKAGE_NAME) .*\n", "$1 \"" + packageName + "\"\n");

        try (FileWriter fileWriter = new FileWriter(source)) {
            fileWriter.write(content);
        }
    }

    private static File dexWriteToFile(DexPool dexPool, int index, File dexOutDir) throws IOException {
        if (!dexOutDir.exists()) dexOutDir.mkdirs();

        File outDexFile;
        if (index == 0) {
            outDexFile = new File(dexOutDir, "classes.dex");
        } else {
            outDexFile = new File(dexOutDir, String.format("classes%d.dex", index + 1));
        }
        dexPool.writeTo(new FileDataStore(outDexFile));

        return outDexFile;
    }

    private static List<String> getApplicationClassesFromMainDex(GlobalDexConfig globalConfig, String applicationClass) throws IOException {
        final List<String> mainDexClassList = new ArrayList<>();
        String tmpType = classDotNameToType(applicationClass);
        mainDexClassList.add(tmpType);
        for (DexConfig config : globalConfig.getConfigs()) {
            DexBackedDexFile dexFile = DexBackedDexFile.fromInputStream(
                    Opcodes.getDefault(),
                    new BufferedInputStream(new FileInputStream(config.getShellDexFile())));
            final Set<? extends DexBackedClassDef> classes = dexFile.getClasses();
            ClassDef classDef;
            while (true) {
                classDef = getClassDefFromType(classes, tmpType);
                if (classDef == null) {
                    break;
                }
                if (classDotNameToType(ANDROID_APP_APPLICATION).equals(classDef.getSuperclass())) {
                    return mainDexClassList;
                }
                tmpType = classDef.getSuperclass();
                mainDexClassList.add(tmpType);
            }


        }
        return mainDexClassList;
    }

    private static ClassDef getClassDefFromType(Set<? extends ClassDef> classDefSet, String type) {
        for (ClassDef classDef : classDefSet) {
            if (classDef.getType().equals(type)) {
                return classDef;
            }
        }
        return null;
    }

    /**
     * 给处理过的class注入静态初始化方法,同时dex适当拆分防止dex索引异常
     * 不缓存写好的dexpool,每次切换dexpool时马上把失效的dexpool写入文件,减小内存占用
     *
     * @param globalConfig
     * @param mainClassSet
     * @param maxPoolSize
     * @param dexOutDir
     * @return
     * @throws IOException
     */
    private static ArrayList<File> injectInstructionAndWriteToFile(GlobalDexConfig globalConfig,
                                                                   Set<String> mainClassSet,
                                                                   int maxPoolSize,
                                                                   File dexOutDir
    ) throws IOException {

        final ArrayList<File> dexFiles = new ArrayList<>();

        DexPool lastDexPool = new DexPool(Opcodes.getDefault());

        final List<DexConfig> configs = globalConfig.getConfigs();
        //第一个dex为main dex
        //提前处理主dex里的类
        for (DexConfig config : configs) {

            DexBackedDexFile dexNativeFile = DexBackedDexFile.fromInputStream(
                    Opcodes.getDefault(),
                    new BufferedInputStream(new FileInputStream(config.getShellDexFile())));

            for (ClassDef classDef : dexNativeFile.getClasses()) {
                if (mainClassSet.contains(classDef.getType())) {
                    Dex2c.internClass(config, lastDexPool, classDef);
                }
            }

        }

        for (int i = 0; i < configs.size(); i++) {
            DexConfig config = configs.get(i);
            final List<DexPool> retPools = Dex2c.injectCallRegisterNativeInsns(config, lastDexPool, mainClassSet, maxPoolSize);
            if (retPools.isEmpty()) {
                throw new RuntimeException("Dex inject instruction error");
            }
            if (retPools.size() > 1) {
                for (int k = 0; k < retPools.size() - 1; k++) {
                    final int size = dexFiles.size();
                    final File file = dexWriteToFile(retPools.get(k), size, dexOutDir);
                    dexFiles.add(file);
                }

                lastDexPool = retPools.get(retPools.size() - 1);
                if (i == configs.size() - 1) {
                    final int size = dexFiles.size();
                    final File file = dexWriteToFile(lastDexPool, size, dexOutDir);
                    dexFiles.add(file);
                }
            } else {
                final int size = dexFiles.size();
                final File file = dexWriteToFile(retPools.get(0), size, dexOutDir);
                dexFiles.add(file);


                lastDexPool = new DexPool(Opcodes.getDefault());
            }
        }

        return dexFiles;
    }

    /**
     * @param manifestBytes        二进制androidManifest.xml文件内容
     * @param applicationClassName application对应的class全限定名
     * @param dexList              dex列表，索引0为主dex,索引0位置会被修改
     * @param outDir               处理后输出androidManifest.xml的目录
     * @return 返回新二进制xml文件
     * @throws IOException
     */
    private static File handleApplicationClass(byte[] manifestBytes,
                                               String applicationClassName,
                                               ArrayList<File> dexList,
                                               GlobalDexConfig globalConfig,
                                               File outDir) throws IOException {
        String newAppClassName;
        byte[] newManifest = manifestBytes;
        if (applicationClassName.equals(ANDROID_APP_APPLICATION)) {
            newAppClassName = "com.nmmedit.protect.LoadLibApp";

            //修改AndroidManifest.xml里application对应的class路径
            byte[] bytes = AxmlEdit.renameApplicationName(manifestBytes, newAppClassName);
            if (bytes != null) {
                newManifest = bytes;
            }
        } else {
            newAppClassName = applicationClassName;
        }

        //添加android.app.Application的子类

        //主dex里处理so加载问题
        File mainDex = dexList.get(0);

        DexFile mainDexFile = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(),
                new BufferedInputStream(new FileInputStream(mainDex)));

        DexPool newDex = new DexPool(Opcodes.getDefault());

        Dex2c.addApplicationClass(
                mainDexFile,
                newDex,
                classDotNameToType(newAppClassName));

        final ArrayList<String> nativeMethodNames = new ArrayList<>();
        for (DexConfig config : globalConfig.getConfigs()) {
            nativeMethodNames.add(config.getRegisterNativesMethodName());
        }


        newDex.internClass(
                new RegisterNativesUtilClassDef("L" + globalConfig.getConfigs().get(0).getRegisterNativesClassName() + ";",
                        nativeMethodNames));

        //不知道为什么windows下删除mainDex会报错，所以改为不删除mainDex，而是把新的dex写入新文件，之后再替换原本mainDex文件
        //处理后的dex写入新的文件中，同时替换主dex，dexList索引0为主dex

        final File injectLoadLib = new File(mainDex.getParent(), "injectLoadLib");
        if (!injectLoadLib.exists()) injectLoadLib.mkdirs();

        final File newFile = new File(injectLoadLib, mainDex.getName());
        newDex.writeTo(new FileDataStore(newFile));
        //替换原本的mainDex文件
        dexList.set(0, newFile);

        //写入新manifest文件
        File newManifestFile = new File(outDir, ANDROID_MANIFEST_XML);
        try (
                FileOutputStream output = new FileOutputStream(newManifestFile);
        ) {
            output.write(newManifest);
        }
        return newManifestFile;
    }

    //只解压不需要处理的文件
    private static HashMap<ZipEntry, File> zipExtractNeedCopy(ZipInputStream zipInputStream, File outDir) throws IOException {
        //除去一些需要修改的文件
        final Pattern regex = Pattern.compile(
                "classes(\\d)*\\.dex" +
                        "|META-INF/.*\\.(RSA|DSA|EC|SF|MF)" +
                        "|AndroidManifest\\.xml");
        final HashMap<ZipEntry, File> entryNameFileMap = new HashMap<>();
        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
            if (entry.isDirectory()
                    || "".equals(entry.getName())) {
                continue;
            }
            if (regex.matcher(entry.getName()).matches()) {
                continue;
            }

            final File file = new File(outDir, entry.getName());
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            try (final FileOutputStream out = new FileOutputStream(file)) {
                FileUtils.copyStream(zipInputStream, out);
                entryNameFileMap.put(entry, file);
            }
        }

        return entryNameFileMap;
    }

    private static void zipCopy(ZipInputStream zipInputStream, File tempDir, ZipOutputStream zipOutputStream) throws IOException {

        //解压需要从旧zip复制到新zip的文件
        final HashMap<ZipEntry, File> entries = zipExtractNeedCopy(zipInputStream, tempDir);
        for (Map.Entry<ZipEntry, File> entryFile : entries.entrySet()) {
            final ZipEntry entry = entryFile.getKey();
            final File file = entryFile.getValue();


            final ZipEntry zipEntry = new ZipEntry(entry.getName());
            if (entry.getMethod() == ZipEntry.STORED) {//不压缩只储存数据
                final long length = file.length();
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setCrc(calcCrc32(file));
                zipEntry.setSize(length);
                zipEntry.setCompressedSize(length);
            }

            zipOutputStream.putNextEntry(zipEntry);

            try (final FileInputStream fileIn = new FileInputStream(file)) {
                FileUtils.copyStream(fileIn, zipOutputStream);
            }
            zipOutputStream.closeEntry();
        }
    }

    private static long calcCrc32(File file) throws IOException {
        try (InputStream inputStream = new FileInputStream(file)) {
            final byte[] buf = new byte[1024 * 4];
            final CRC32 crc32 = new CRC32();
            int len;
            while ((len = inputStream.read(buf, 0, buf.length)) != -1) {
                crc32.update(buf, 0, len);
            }
            return crc32.getValue();
        }
    }

    //递归删除目录
    private static void deleteFile(File file) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            final File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteFile(child);
                }
            }
        }
        file.delete();
    }

    private static String classDotNameToType(String classDotName) {
        return "L" + classDotName.replace('.', '/') + ";";
    }


    public static class Builder {
        private final ApkFolders apkFolders;
        private InstructionRewriter instructionRewriter;
        private ApkVerifyCodeGenerator apkVerifyCodeGenerator;
        private ClassAndMethodFilter filter;


        public Builder(ApkFolders apkFolders) {
            this.apkFolders = apkFolders;
        }

        public Builder setInstructionRewriter(InstructionRewriter instructionRewriter) {
            this.instructionRewriter = instructionRewriter;
            return this;
        }

        public Builder setApkVerifyCodeGenerator(ApkVerifyCodeGenerator apkVerifyCodeGenerator) {
            this.apkVerifyCodeGenerator = apkVerifyCodeGenerator;
            return this;
        }

        public Builder setFilter(ClassAndMethodFilter filter) {
            this.filter = filter;
            return this;
        }

        public ApkProtect build() {
            if (instructionRewriter == null) {
                throw new RuntimeException("instructionRewriter == null");
            }
            return new ApkProtect(apkFolders, instructionRewriter, apkVerifyCodeGenerator, filter);
        }
    }
}
