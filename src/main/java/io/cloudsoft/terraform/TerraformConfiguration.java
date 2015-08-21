package io.cloudsoft.terraform;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;

@ImplementedBy(TerraformConfigurationImpl.class)
public interface TerraformConfiguration extends SoftwareProcess {

}
