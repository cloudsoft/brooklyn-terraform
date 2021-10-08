package io.cloudsoft.terraform.util;

import org.apache.brooklyn.util.core.file.ArchiveUtils;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.ZipFile;

public enum SafeGrabber {
    ;
    public static final Long MAX_FILE_SIZE = 536_870_912L; // 512MB

    /**
     * Utility method for reading remote files up to a certain size. We do this for performance reasons and to avoid somebody providing an invalid file that will fill all the memory and freeze AMP.
     */
    public static File downloadZip(final String urlStr){
        File tempZipFile;
        try {
            URL website = new URL(urlStr);
            URLConnection connection = website.openConnection();
            tempZipFile = File.createTempFile("tf-pack-",".zip");
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(tempZipFile)) {
                byte[] b = new byte[1024];
                int count;
                int size = 0;
                while ((count = in.read(b)) >= 0) {
                    out.write(b, 0, count);
                    size +=count;
                    if (size > MAX_FILE_SIZE) {
                        throw new IllegalStateException("The zip is too big to be processed. ");
                    }
                }
            }
        } catch (IOException e){
            throw new IllegalStateException("Cannot read zip from " + urlStr, e);
        }
        return tempZipFile;
    }

    public static String grabTerrformConfiguration(final File tfArchivePath) {
        final StringBuilder configBuilder = new StringBuilder();
        try {
            final String tmpUnpackedDir = Files.createTempDirectory("tf-unpecked").toFile().getAbsolutePath();
            ArchiveUtils.extractZip(new ZipFile(tfArchivePath),tmpUnpackedDir);
            System.out.println(tmpUnpackedDir);
            Arrays.stream(Objects.requireNonNull(new File(tmpUnpackedDir).listFiles(pathname -> pathname.getName().endsWith(".tf")))).forEach(cfgFile -> {
                try {
                    Files.readAllLines(cfgFile.toPath()).forEach(line -> configBuilder.append(line).append("\n"));
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read configuration file: " + cfgFile + "!", e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read terraform configuration", e);
        }
        return configBuilder.toString();
    }
}
