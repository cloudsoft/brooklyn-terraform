package io.cloudsoft.terraform;

import com.google.common.collect.ImmutableMap;
import org.apache.brooklyn.api.objs.Configurable;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigConstraints;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.apache.brooklyn.util.time.Duration;

import java.util.Map;
import java.util.Set;

public interface TerraformCommons {

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

    @SetFromFlag("tfExecutionMode")
    ConfigKey<String> TF_EXECUTION_MODE = ConfigKeys.builder(String.class)
            .name("tf.execution.mode") // should be part of deployed the bundle
            .description("If Terraform should run in a location ('ssh'), or in a container managed by a Kubernetes instance('kube').")
            .defaultValue("kube")
            .build();

    String SSH_MODE = "ssh";
    /**
     * I added this because commands can also be used by directly calling {@code docker run ...},
     * in case we even want to add a driver for that as well
     */
    String KUBE_MODE = "kube";
    /**
     * This method converts any brooklyn configuration starting with tf_var. into TERRAFORM environment variables
     */
    static void convertConfigToTerraformEnvVar(Configurable entity) {
        Set<ConfigKey<?>> terraformVars =  entity.config().findKeysPresent(k -> k.getName().startsWith("tf_var"));
        final Map<String,Object> env = MutableMap.copyOf(entity.getConfig(SoftwareProcess.SHELL_ENVIRONMENT));
        terraformVars.forEach(c -> {
            final String bcName = c.getName();
            final String tfName = bcName.replace("tf_var.", "TF_VAR_");
            final Object value = entity.getConfig(ConfigKeys.newConfigKey(Object.class, bcName));
            env.put(tfName, value);
        });
        entity.config().set(SoftwareProcess.SHELL_ENVIRONMENT, ImmutableMap.copyOf(env));
    }
}
