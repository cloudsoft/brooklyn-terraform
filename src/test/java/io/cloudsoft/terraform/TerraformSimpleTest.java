package io.cloudsoft.terraform;

import io.cloudsoft.terraform.util.SafeGrabber;
import org.apache.brooklyn.util.core.file.ArchiveUtils;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.testng.Assert.assertTrue;

public class TerraformSimpleTest {


    @Test
    public void testPass() throws IOException {
        final File tempZipFile = SafeGrabber.downloadZip("https://artifactory.cloudsoftcorp.com/artifactory/libs-release-local/io/cloudsoft/packs/tf-deployment.zip");



        assertTrue(true);
    }

}
