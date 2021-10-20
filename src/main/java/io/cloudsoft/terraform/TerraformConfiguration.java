package io.cloudsoft.terraform;

import java.util.Map;

import io.cloudsoft.terraform.entity.ManagedResource;
import org.apache.brooklyn.api.catalog.Catalog;
import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.effector.MethodEffector;
import org.apache.brooklyn.core.sensor.AttributeSensorAndConfigKey;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

@Catalog(
        name = "TerraformConfiguration",
        description = "Brooklyn Terraform entity for lifecycle management of a Terraform configuration",
        iconUrl = "classpath://io/cloudsoft/terraform/logo.png")
@ImplementedBy(TerraformConfigurationImpl.class)
public interface TerraformConfiguration extends SoftwareProcess {
    public static final String TERRAFORM_DOWNLOAD_URL = "https://releases.hashicorp.com/terraform/${version}/terraform_${version}_${driver.osTag}.zip";

    public enum TerraformStatus {
        SYNC, // plan and configuration match
        DESYNCHRONIZED, // plan and configuration to not match,
        DRIFT,  // resources have changed outside terraform
        ERROR // configuration was edited manually and it is incorrect
    }

    // Update reference.json when changing this value.
    @SetFromFlag("version")
    ConfigKey<String> SUGGESTED_VERSION = ConfigKeys.newConfigKeyWithDefault(SoftwareProcess.SUGGESTED_VERSION, "1.0.8");

    @SetFromFlag("downloadUrl")
    AttributeSensorAndConfigKey<String,String> DOWNLOAD_URL = ConfigKeys.newSensorAndConfigKeyWithDefault(SoftwareProcess.DOWNLOAD_URL, TERRAFORM_DOWNLOAD_URL);

    @SetFromFlag("tfConfigurationContents")
    ConfigKey<String> CONFIGURATION_CONTENTS = ConfigKeys.newStringConfigKey(
            "tf.configuration.contents",
            "Contents of the configuration file that will be applied by Terraform.");

    @SetFromFlag("tfDeployment")
    ConfigKey<String> CONFIGURATION_URL = ConfigKeys.builder(String.class)
            .name("tf.configuration.url")
            .description("URL of the configuration file that will be applied by Terraform.")
            .build();

    @SetFromFlag("tfVars")
    ConfigKey<String> TFVARS_FILE_URL = ConfigKeys.builder(String.class)
            .name("tf.tfvars.url") // should be part of deployed the bundle
            .description("URL of the file containing values for the Terraform variables.")
            .build();

    AttributeSensor<Boolean> CONFIGURATION_IS_APPLIED = Sensors.newBooleanSensor("tf.configuration.isApplied",
            "Whether the supplied Terraform configuration has been successfully applied.");

    AttributeSensor<String> PLAN = Sensors.newStringSensor("tf.plan",
            "The contents of the Terraform plan command which specifies exactly what actions will be taken upon applying the configuration.");

    AttributeSensor<String> OUTPUT = Sensors.newStringSensor("tf.output",
            "The contents of the Terraform output command which inspects Terraform state or plan.");

    @SuppressWarnings({ "rawtypes", "unchecked" })
    AttributeSensor<Map<String, Object>> STATE = new BasicAttributeSensor(Map.class, "tf.state",
            "A map constructed from the state file on disk which contains the state of all managed infrastructure.");

    MethodEffector<Void> APPLY = new MethodEffector<>(TerraformConfiguration.class, "apply");

    MethodEffector<Void> DESTROY = new MethodEffector<>(TerraformConfiguration.class, "destroy");

    @Effector(description="Performs the Terraform apply command which will create all of the infrastructure specified by the configuration.")
    void apply();

    @Effector(description="Performs the Terraform destroy command which will destroy all of the infrastructure that has been previously created by the configuration.")
    void destroy();

    void destroyTarget(ManagedResource child);

    boolean isConfigurationApplied();

    TerraformDriver getDriver();
}
