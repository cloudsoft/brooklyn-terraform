package io.cloudsoft.terraform;

import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.util.text.Strings;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
public class TerraformSimpleTest {


    @Test
    public void xorTest() throws IOException {
        // nulls
        assertFalse(Strings.isNonBlank("1") ^ Strings.isNonBlank("1"));
        assertTrue(Strings.isNonBlank("1") ^ Strings.isNonBlank(null));
        assertTrue(Strings.isNonBlank(null) ^ Strings.isNonBlank("1"));
        assertFalse(Strings.isNonBlank(null) ^ Strings.isNonBlank(null));

        // empty strings
        assertFalse(Strings.isNonBlank("1") ^ Strings.isNonBlank("1"));
        assertTrue(Strings.isNonBlank("1") ^ Strings.isNonBlank(""));
        assertTrue(Strings.isNonBlank("") ^ Strings.isNonBlank("1"));
        assertFalse(Strings.isNonBlank("") ^ Strings.isNonBlank(""));
    }

    @Test(groups = "Local")
    public void readManagedResources() throws IOException {
        final String state =  new String(
                Files.readAllBytes(new File("[your workspace]/brooklyn-terraform/src/test/resources/state/state.json").toPath()));

        Map<String,Object> resources = StateParser.parseResources(state);

        Assert.assertTrue(1 == resources.size());
        Assert.assertTrue(resources.containsKey("aws_instance.example1"));
        Assert.assertTrue(((Map<String,Object>)(resources.get("aws_instance.example1"))).containsKey("resource.status"));
    }

}