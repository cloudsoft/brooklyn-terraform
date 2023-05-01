package io.cloudsoft.terraform;

import java.time.Instant;
import java.util.Map;

import org.apache.brooklyn.api.catalog.Catalog;
import org.apache.brooklyn.api.catalog.CatalogConfig;
import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigInheritance;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.annotation.EffectorParam;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.sensor.AttributeSensorAndConfigKey;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.workflow.steps.CustomWorkflowStep;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

import javax.annotation.Nullable;

@Catalog(
        name = "TerraformConfiguration",
        description = "Brooklyn Terraform entity for lifecycle management of a Terraform configuration",
        iconUrl = "classpath://io/cloudsoft/terraform/logo.png")
@ImplementedBy(TerraformConfigurationImpl.class)
public interface TerraformConfiguration extends SoftwareProcess, TerraformCommons {
    String TERRAFORM_DOWNLOAD_URL = "https://releases.hashicorp.com/terraform/${version}/terraform_${version}_${driver.osTag}.zip";

    enum TerraformStatus {
        SYNC, // plan and configuration match
        DESYNCHRONIZED, // plan and configuration to not match,
        DRIFT,  // resources have changed outside terraform
        ERROR // configuration was edited manually and it is incorrect
    }

    // Update reference.json in the root when changing this value
    @SetFromFlag("version")
    ConfigKey<String> SUGGESTED_VERSION = ConfigKeys
            .newConfigKeyWithDefault(SoftwareProcess.SUGGESTED_VERSION, "1.2.5");


    @SetFromFlag("downloadUrl")
    AttributeSensorAndConfigKey<String,String> DOWNLOAD_URL = ConfigKeys.newSensorAndConfigKeyWithDefault(SoftwareProcess.DOWNLOAD_URL, TERRAFORM_DOWNLOAD_URL);

    @SetFromFlag("tfPath")
    ConfigKey<String> TERRAFORM_PATH = ConfigKeys.builder(String.class)
            .name("tf.path")
            .description("Path to the Terraform executable")
            .defaultValue("")
            .build();
    @SetFromFlag("tfSearch")
    ConfigKey<Boolean> LOOK_FOR_TERRAFORM_INSTALLED = ConfigKeys.builder(Boolean.class)
            .name("tf.search")
            .description("Allow to look for the terraform binary in the system if not fount in explicit path" +
                    " (`" + TERRAFORM_PATH.getName() + "` config key) or the property wasn't supplied")
            .defaultValue(false)
            .build();

    @SetFromFlag("tfDriftCheck")
    ConfigKey<Boolean> TERRAFORM_DRIFT_CHECK = ConfigKeys.builder(Boolean.class)
            .name("tf.drift.check")
            .description("Allow to look skip drift checking for the deployment by setting it to 'false'")
            .defaultValue(true)
            .build();

    ConfigKey<CustomWorkflowStep> PRE_PLAN_WORKFLOW = ConfigKeys.builder(CustomWorkflowStep.class, "pre_plan.workflow")
            .description("workflow to run prior to any plan")
            .runtimeInheritance(ConfigInheritance.NONE)
            .build();

    ConfigKey<CustomWorkflowStep> PRE_APPLY_WORKFLOW = ConfigKeys.builder(CustomWorkflowStep.class, "pre_apply.workflow")
            .description("workflow to run prior to any apply")
            .runtimeInheritance(ConfigInheritance.NONE)
            .build();

    ConfigKey<CustomWorkflowStep> POST_APPLY_WORKFLOW = ConfigKeys.builder(CustomWorkflowStep.class, "post_apply.workflow")
            .description("workflow to run after an apply")
            .runtimeInheritance(ConfigInheritance.NONE)
            .build();

    ConfigKey<CustomWorkflowStep> PRE_DESTROY_WORKFLOW = ConfigKeys.builder(CustomWorkflowStep.class, "pre_destroy.workflow")
            .description("workflow to run prior to destroy")
            .runtimeInheritance(ConfigInheritance.NONE)
            .build();

    ConfigKey<CustomWorkflowStep> POST_DESTROY_WORKFLOW = ConfigKeys.builder(CustomWorkflowStep.class, "post_destroy.workflow")
            .description("workflow to run after destroy")
            .runtimeInheritance(ConfigInheritance.NONE)
            .build();

    AttributeSensor<Instant> CONFIGURATION_APPLIED = Sensors.newSensor(Instant.class, "tf.configuration.applied",
            "The most recent time a Terraform configuration has been successfully applied.");

    @SuppressWarnings({"rawtypes", "unchecked"})
    AttributeSensor<Map<String, Object>> PLAN = new BasicAttributeSensor(Map.class, "tf.plan",
            "The contents of the Terraform plan command which specifies exactly what actions will be taken upon applying the configuration.");

    AttributeSensor<String> OUTPUT = Sensors.newStringSensor("tf.output",
            "The contents of the Terraform output command which inspects Terraform state or plan.");

    @SuppressWarnings({ "rawtypes", "unchecked" })
    AttributeSensor<Map<String, Object>> STATE = new BasicAttributeSensor(Map.class, "tf.state",
            "A map constructed from the state file on disk which contains the state of all managed infrastructure.");

    AttributeSensor<TerraformStatus> DRIFT_STATUS = Sensors.newSensor(TerraformStatus.class,"tf.drift.status",
            "Drift status of the configuration" );

    void removeDiscoveredResources();

    @Effector(description="Performs the Terraform apply command which will create all of the infrastructure specified by the configuration.")
    void apply();

    @Effector(description="Performs the Terraform plan command to show what would change (and refresh sensors).")
    void plan();

    @Effector(description = "Force a re-discovery of resources (clearing all first)")
    void rediscoverResources();

    @Effector(description = "Delete any terraform lock file (may be needed if management server interrupted; done automatically for stop, as we manage mutex locking)")
    public void clearTerraformLock();

    @Effector(description="Performs the Terraform destroy command to destroy all of the infrastructure that has been previously created by the configuration.")
    void destroyTerraform();

    @Effector(description="Performs Terraform apply again with the configuration provided via the provided URL. If an URL is not provided the original URL provided when this blueprint was deployed will be used." +
            "This is useful when the URL points to a GitHub or Artifactory release.")
    void reinstallConfig(@EffectorParam(name = "configUrl", description = "URL pointing to the terraform configuration") @Nullable String configUrl);

    TerraformDriver getDriver();

    Boolean isApplyDriftComplianceToResources();
    void setApplyDriftComplianceToResources(Boolean doApply);
}
