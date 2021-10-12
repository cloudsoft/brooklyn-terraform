package io.cloudsoft.terraform;

import org.apache.brooklyn.util.text.Strings;
import org.testng.annotations.Test;
import java.io.IOException;

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

}
