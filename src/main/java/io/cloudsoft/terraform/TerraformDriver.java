package io.cloudsoft.terraform;

import org.apache.brooklyn.entity.software.base.SoftwareProcessDriver;

public interface TerraformDriver extends SoftwareProcessDriver {

    String makeTerraformCommand(String argument);
}
