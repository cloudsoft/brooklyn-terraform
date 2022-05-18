package io.cloudsoft.terraform;

import org.apache.brooklyn.api.catalog.Catalog;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigConstraints;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.apache.brooklyn.util.time.Duration;

@Catalog(
        name = "TerraformEntity",
        description = "Brooklyn Terraform Entity with Custom Effectors",
        iconUrl = "classpath://io/cloudsoft/terraform/logo.png")
@ImplementedBy(TerraformEntityImpl.class)
public interface TerraformEntity extends Entity, Startable {

    @SetFromFlag("tfPollingPeriod")
    ConfigKey<Duration> POLLING_PERIOD = ConfigKeys.builder(Duration.class)
            .name("tf.polling.period")
            .description("Contents of the configuration file that will be applied by Terraform.")
            .defaultValue(Duration.seconds(15))
            .constraint(input -> !input.isShorterThan(Duration.seconds(15))) // if shorter than 30s difficulties of executing 'apply' appear
            .build();

    @SetFromFlag("tfConfigurationContents")
    ConfigKey<String> CONFIGURATION_CONTENTS = ConfigKeys.builder(String.class)
            .name("tf.configuration.contents")
            .description("Contents of the configuration file that will be applied by Terraform.")
            .constraint(ConfigConstraints.forbiddenIf("tf.configuration.url"))
            .build();

    @SetFromFlag("tfDeployment")
    ConfigKey<String> CONFIGURATION_URL = ConfigKeys.builder(String.class)
            .name("tf.configuration.url")
            .description("URL of the configuration file that will be applied by Terraform.")
            .constraint(ConfigConstraints.forbiddenIf("tf.configuration.contents"))
            .build();

    @SetFromFlag("tfVars")
    ConfigKey<String> TFVARS_FILE_URL = ConfigKeys.builder(String.class)
            .name("tf.tfvars.url") // should be part of deployed the bundle
            .description("URL of the file containing values for the Terraform variables.")
            .build();

}
