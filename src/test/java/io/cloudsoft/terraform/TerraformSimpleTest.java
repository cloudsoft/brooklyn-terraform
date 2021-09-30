package io.cloudsoft.terraform;

import org.testng.annotations.Test;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.assertFalse;

public class TerraformSimpleTest {

    @Test
    public void testPass() {
        assertTrue(true);
    }

    @Test
    public void testFail() {
        assertFalse(true, "This intentionally fails to prove the CircleCI notification works!");
    }

}
