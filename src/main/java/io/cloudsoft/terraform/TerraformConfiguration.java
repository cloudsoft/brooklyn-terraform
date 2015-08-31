package io.cloudsoft.terraform;

import java.util.Map;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.effector.MethodEffector;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
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

    AttributeSensor<String> SHOW = Sensors.newStringSensor("show",
            "The contents of the Terraform show command which provides a human-readable view of the state of the configuration.");

    AttributeSensor<String> PLAN = Sensors.newStringSensor("plan",
            "The contents of the Terraform plan command which specifies exactly what actions will be taken upon applying the configuration.");

    @SuppressWarnings({ "rawtypes", "unchecked" })
    AttributeSensor<Map<String, Object>> STATE = new BasicAttributeSensor(Map.class, "state",
            "A map constructed from the state file on disk which contains the state of all managed infrastructure.");

    MethodEffector<Void> APPLY = new MethodEffector<Void>(TerraformConfiguration.class, "apply");

    @Effector(description="Performs the Terraform apply command which will create all of the infrastructure specified by the configuration.")
    void apply();

    @Effector(description="Performs the Terraform destroy command which will destroy all of the infrastructure that has been previously created by the configuration.")
    void destroy();
}
