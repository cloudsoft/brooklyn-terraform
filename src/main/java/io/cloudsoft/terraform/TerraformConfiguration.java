package io.cloudsoft.terraform;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

@ImplementedBy(TerraformConfigurationImpl.class)
public interface TerraformConfiguration extends SoftwareProcess {

    @SetFromFlag("configurationContents")
    public static final ConfigKey<String> CONFIGURATION_CONTENTS = ConfigKeys.newStringConfigKey(
            "configuration.contents",
            "Contents of the configuration file that will be applied by Terraform.",
            "");

    @SetFromFlag("configurationUrl")
    public static final ConfigKey<String> CONFIGURATION_URL = ConfigKeys.newStringConfigKey(
            "configuration.url",
            "URL of the configuration file that will be applied by Terraform.",
            "");
}
