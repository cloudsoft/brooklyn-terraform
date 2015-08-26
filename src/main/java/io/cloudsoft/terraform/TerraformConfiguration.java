package io.cloudsoft.terraform;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.sensor.BasicAttributeSensorAndConfigKey;
import org.apache.brooklyn.core.sensor.BasicAttributeSensorAndConfigKey.StringAttributeSensorAndConfigKey;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

@ImplementedBy(TerraformConfigurationImpl.class)
public interface TerraformConfiguration extends SoftwareProcess {

    @SetFromFlag("version")
    ConfigKey<String> SUGGESTED_VERSION = ConfigKeys.newConfigKeyWithDefault(SoftwareProcess.SUGGESTED_VERSION, "0.6.3");

    @SetFromFlag("downloadUrl")
    BasicAttributeSensorAndConfigKey<String> DOWNLOAD_URL = new StringAttributeSensorAndConfigKey(
            Attributes.DOWNLOAD_URL, "https://dl.bintray.com/mitchellh/terraform/terraform_${version}_darwin_amd64.zip");

    @SetFromFlag("configurationContents")
    ConfigKey<String> CONFIGURATION_CONTENTS = ConfigKeys.newStringConfigKey(
            "configuration.contents",
            "Contents of the configuration file that will be applied by Terraform.",
            "");

    @SetFromFlag("configurationUrl")
    ConfigKey<String> CONFIGURATION_URL = ConfigKeys.newStringConfigKey(
            "configuration.url",
            "URL of the configuration file that will be applied by Terraform.",
            "");

    AttributeSensor<Boolean> CONFIGURATION_IS_APPLIED = Sensors.newBooleanSensor("configuration.isApplied",
            "Whether the supplied Terraform configuration has been successfully applied.");
}
