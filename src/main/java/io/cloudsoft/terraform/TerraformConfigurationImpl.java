package io.cloudsoft.terraform;

import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;

public class TerraformConfigurationImpl extends SoftwareProcessImpl implements TerraformConfiguration {

    @Override
    public Class<?> getDriverInterface() {
        return TerraformConfiguration.class;
    }
}
