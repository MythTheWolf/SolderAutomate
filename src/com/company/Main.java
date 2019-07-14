package com.company;

import java.io.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Main {
    static int count = 1;
    static File binFolder;

    public static void main(String[] args) {
        String SQL_HOST = promptUser("Solder database host?:");
        String SQL_PORT = promptUser("Solder database port?:");
        String SQL_USER = promptUser("Solder database username?:");
        String SQL_PASS = promptUser("Solder database password?:");
        String SQL_DB = promptUser("Solder database name?:");

        try {
            Connection con = DriverManager.getConnection(
                    "jdbc:mysql://" + SQL_HOST + "/" + SQL_DB, SQL_USER, SQL_PASS);
            if (!con.isValid(20)) {
                System.err.println("Could not connect to database");
                System.exit(-1);
            }
            String MOD_PATH = promptUser("Dir containing mods to encode?:");

            final File folder = new File(MOD_PATH);
            if (!folder.exists()) {
                System.err.println("Folder is not valid");
            }

            binFolder = new File(folder.getAbsolutePath() + File.separator + "upload");
            if (binFolder.exists()) {
                Arrays.stream(Objects.requireNonNull(binFolder.listFiles())).forEach(File::delete);
            } else if (!binFolder.mkdir()) {
                System.err.println("Could not create target directory");
                System.exit(-1);
            }
            List<String> files = Arrays.stream(folder.list()).filter(s -> s.endsWith(".jar")).collect(Collectors.toList());
            int max_size = files.size();
            files.forEach(s -> {
                System.out.println("Packing mods into zip structure... (" + count + "/" + max_size);
                File mod = new File(folder.getAbsolutePath() + File.separator + s);

                File tempFolder = new File(folder.getAbsolutePath() + File.separator + "temp");
                tempFolder.mkdir();
                File tempModFolder = new File(tempFolder.getAbsolutePath() + File.separator + "mods");
                tempModFolder.mkdir();
                File zipMod = new File(tempModFolder.getAbsolutePath() + File.separator + mod.getName());
                try {
                    copyFileUsingStream(mod, zipMod);
                    List<File> fileList = new ArrayList<File>();
                    getAllFiles(tempFolder, fileList);
                    File zip = new File(binFolder.getAbsolutePath() + File.separator + mod.getName().replace(".jar", "") + ".zip");
                    writeZipFile(tempFolder, fileList, zip);
                    deleteFolder(tempFolder);
                } catch (Exception e) {
                    System.err.println("Could not copy file: " + mod.getAbsolutePath());
                    e.printStackTrace();
                    deleteFolder(tempFolder);
                    System.exit(-1);
                }
                count++;
            });
            boolean doInsert = false;
            files = Arrays.stream(binFolder.list()).filter(s -> s.endsWith(".zip")).collect(Collectors.toList());
            if (promptUser("Insert mods into the database?: (y/n)").toLowerCase().equals("y")) {
                doInsert = true;
            }
            boolean doUpdateBuild = false;
            boolean doSetNames = false;
            if (doInsert && promptUser("Set pretty names for mods? The system will use the jar name otherwise: (y/n)").toLowerCase().equals("y")) {
                doSetNames = true;
            }
            List<Integer> ids = new ArrayList<>();
            if (doInsert && promptUser("Attach newly inserted mods to specific builds?: (y/n)").toLowerCase().equals("y")) {
                doUpdateBuild = true;
                String tmpid = promptUser("IDs of builds you wish to update,separate with spaces:");
                List<String> idlist = Arrays.asList(tmpid.split(" "));
                idlist.forEach(s -> {
                    try {
                        int id = Integer.parseInt(s);
                        ids.add(id);
                    } catch (Exception e) {
                        System.err.println("Ignoring input " + s + " - NaN");
                    }
                });
                if (idlist.size() < 1) {
                    System.err.println("No ids given - exiting");
                    System.exit(-1);
                }
            }
            for (String file : files) {
                String[] parts = file.split("-");

                new File(binFolder + File.separator + parts[0]).mkdirs();
                File modDIr = new File(binFolder.getAbsolutePath() + File.separator + parts[0]);
                File mod = new File(binFolder + File.separator + file);
                File dest = new File(modDIr.getAbsolutePath() + File.separator + mod.getName());
                copyFileUsingStream(mod, dest);
                String MOD_NAME = parts[0];
                System.out.println("Processing: " + MOD_NAME + " - creating repo folder structure");
                String[] nameremoved = Arrays.copyOfRange(parts, 1, parts.length);
                String MOD_VERSION = arrayCombine(nameremoved).replace(".zip", "");
                MOD_VERSION = MOD_VERSION.substring(0, MOD_VERSION.length() - 1);
                if (doInsert) {
                    PreparedStatement chkMod = con.prepareStatement("SELECT * FROM `mods` WHERE (`name`) = ?");
                    chkMod.setString(1, MOD_NAME);
                    ResultSet test = chkMod.executeQuery();
                    long MOD_ID = -1;
                    if (!test.next()) {
                        System.out.println("Mod doesn't exist in solder - creating it now");
                        PreparedStatement ps = con.prepareStatement("INSERT INTO `mods` (`name`,`pretty_name`) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS);
                        ps.setString(1, MOD_NAME);
                        if (doSetNames) {
                            String name = promptUser("Pretty name to use for mod '" + MOD_NAME + "'?: ");
                            ps.setString(2, name);
                        } else {
                            ps.setString(2, MOD_NAME);
                        }
                        ps.executeUpdate();
                        ResultSet generatedKey = ps.getGeneratedKeys();
                        generatedKey.next();
                        MOD_ID = generatedKey.getLong(1);
                    } else {
                        System.out.println("Mod exists already in database");
                        MOD_ID = test.getInt("ID");
                    }
                    System.out.println("Processing: " + MOD_NAME + " - Inserting version");
                    String fileMD5 = getFileChecksum((MessageDigest.getInstance("MD5")), mod);
                    PreparedStatement ps2 = con.prepareStatement("SELECT * FROM `modversions` WHERE `version` = ? AND `mod_id` = ?");
                    ps2.setString(1,MOD_VERSION);
                    ps2.setString(2,MOD_ID+"");
                    ResultSet query = ps2.executeQuery();
                    long versionID;
                    if(query.next()){
                        System.out.println("Version exists already in database");
                        versionID = query.getLong("ID");
                    }else {
                        ps2 = con.prepareStatement("INSERT INTO `modversions` (`mod_id`,`version`,`md5`) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS);

                        ps2.setString(1, MOD_ID + "");
                        ps2.setString(2, MOD_VERSION);
                        ps2.setString(3, fileMD5);
                        ps2.executeUpdate();
                        ResultSet keys = ps2.getGeneratedKeys();
                        keys.next();
                        versionID = keys.getLong(1);
                    }
                    if (doUpdateBuild) {
                        System.out.println("Processing: " + MOD_NAME + " - Updating builds");
                        ids.forEach(integer -> {
                            try {
                                PreparedStatement ps3 = con.prepareStatement("INSERT INTO `build_modversion` (`modversion_id`,`build_id`) VALUES (?,?)");
                                ps3.setString(1, versionID + "");
                                ps3.setInt(2, integer);
                                ps3.executeUpdate();
                            } catch (SQLException E) {
                                E.printStackTrace();
                            }
                        });
                    }
                }
                mod.delete();
            }
            System.out.println("Processing complete, you may upload everything from " + binFolder.getAbsolutePath() + " to your repo now.");
        } catch (Exception E) {
            E.printStackTrace();
        }

    }

    public static void getAllFiles(File dir, List<File> fileList) {
        try {
            File[] files = dir.listFiles();
            for (File file : files) {
                fileList.add(file);
                if (file.isDirectory()) {
                    getAllFiles(file, fileList);
                } else {
                    //   System.out.println("     file:" + file.getCanonicalPath());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void writeZipFile(File directoryToZip, List<File> fileList, File target) {

        try {
            FileOutputStream fos = new FileOutputStream(target);
            ZipOutputStream zos = new ZipOutputStream(fos);

            for (File file : fileList) {
                if (!file.isDirectory()) { // we only zip files, not directories
                    addToZip(directoryToZip, file, zos);
                }
            }

            zos.close();
            fos.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) { //some JVMs return null for empty dirs
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteFolder(f);
                } else {
                    f.delete();
                }
            }
        }
        folder.delete();
    }

    public static void addToZip(File directoryToZip, File file, ZipOutputStream zos) throws
            IOException {

        FileInputStream fis = new FileInputStream(file);

        // we want the zipEntry's path to be a relative path that is relative
        // to the directory being zipped, so chop off the rest of the path
        String zipFilePath = file.getCanonicalPath().substring(directoryToZip.getCanonicalPath().length() + 1
        );
        ZipEntry zipEntry = new ZipEntry(zipFilePath);
        zos.putNextEntry(zipEntry);

        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zos.write(bytes, 0, length);
        }

        zos.closeEntry();
        fis.close();
    }

    private static void copyFileUsingStream(File source, File dest) throws IOException {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = new FileInputStream(source);
            os = new FileOutputStream(dest);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } catch (Exception E) {
            E.printStackTrace();
        } finally {
            is.close();
            os.close();
        }
    }


    private static String promptUser(String prompt) {
        System.out.println(prompt);
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in));
        String response = null;
        try {
            response = reader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return response;
    }

    private static String getFileChecksum(MessageDigest digest, File file) throws IOException {
        //Get file input stream for reading the file content
        FileInputStream fis = new FileInputStream(file);

        //Create byte array to read data in chunks
        byte[] byteArray = new byte[1024];
        int bytesCount = 0;

        //Read file data and update in message digest
        while ((bytesCount = fis.read(byteArray)) != -1) {
            digest.update(byteArray, 0, bytesCount);
        }

        //close the stream; We don't need it now.
        fis.close();

        //Get the hash's bytes
        byte[] bytes = digest.digest();

        //This bytes[] has bytes in decimal format;
        //Convert it to hexadecimal format
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16).substring(1));
        }

        //return complete hash
        return sb.toString();
    }

    private static String arrayCombine(String[] array) {
        // System.out.println(Arrays.toString(array));
        StringBuilder sb = new StringBuilder();
        for (String x : array) {
            sb.append(x).append("-");
        }
        return sb.toString();
    }
}
